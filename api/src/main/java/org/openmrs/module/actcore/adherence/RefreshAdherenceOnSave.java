/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.adherence;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.IntConsumer;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.openmrs.Encounter;
import org.openmrs.api.context.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionStatus;

/**
 * Advice on EncounterService that recomputes a patient's adherence rows once an encounter of theirs
 * is saved, voided or purged.
 */
public class RefreshAdherenceOnSave implements MethodInterceptor {
	
	private static final Logger log = LoggerFactory.getLogger(RefreshAdherenceOnSave.class);
	
	private static final Set<String> METHODS = new HashSet<String>(
	        Arrays.asList("saveEncounter", "voidEncounter", "unvoidEncounter", "purgeEncounter"));
	
	private final IntConsumer refresher;
	
	public RefreshAdherenceOnSave() {
		this(new IntConsumer() {
			
			@Override
			public void accept(int patientId) {
				refreshInNewTransaction(patientId);
			}
		});
	}
	
	RefreshAdherenceOnSave(IntConsumer refresher) {
		this.refresher = refresher;
	}
	
	@Override
	public Object invoke(MethodInvocation invocation) throws Throwable {
		Object[] args = invocation.getArguments();
		boolean watched = METHODS.contains(invocation.getMethod().getName()) && args.length > 0
		        && args[0] instanceof Encounter;
		// Read before proceeding, as a purge leaves no encounter to read it from.
		Integer patientId = watched && ((Encounter) args[0]).getPatient() != null
		        ? ((Encounter) args[0]).getPatient().getPatientId()
		        : null;
		Object result = invocation.proceed();
		if (patientId != null) {
			afterCommit(patientId);
		}
		return result;
	}
	
	/**
	 * Refreshes once the save commits, so a failed refresh cannot roll the save back, and once per
	 * patient however many encounters the transaction saves.
	 */
	private void afterCommit(int patientId) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			refresh(patientId);
			return;
		}
		pending().patientIds.add(patientId);
	}
	
	// Not a bound resource, which a REQUIRES_NEW suspend leaves bound to the outer transaction.
	private Pending pending() {
		for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
			if (synchronization instanceof Pending && ((Pending) synchronization).of(this)) {
				return (Pending) synchronization;
			}
		}
		Pending pending = new Pending();
		TransactionSynchronizationManager.registerSynchronization(pending);
		return pending;
	}
	
	private void refresh(int patientId) {
		try {
			refresher.accept(patientId);
		}
		catch (RuntimeException e) {
			// The save stands; the next full refresh writes the row.
			log.error("Could not refresh the prophylaxis adherence of patient {}", patientId, e);
		}
	}
	
	/**
	 * After a commit the old transaction's resources are still bound, so the rows need a transaction of
	 * their own.
	 */
	private static void refreshInNewTransaction(final int patientId) {
		TransactionTemplate template = new TransactionTemplate(
		        Context.getRegisteredComponent("transactionManager", PlatformTransactionManager.class));
		template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		template.execute(new TransactionCallbackWithoutResult() {
			
			@Override
			protected void doInTransactionWithoutResult(TransactionStatus status) {
				new AdherenceRefresh().refreshPatient(patientId, LocalDate.now());
			}
		});
	}
	
	private class Pending extends TransactionSynchronizationAdapter {
		
		private final Set<Integer> patientIds = new LinkedHashSet<Integer>();
		
		private boolean of(RefreshAdherenceOnSave advice) {
			return advice == RefreshAdherenceOnSave.this;
		}
		
		@Override
		public void afterCommit() {
			for (Integer patientId : patientIds) {
				refresh(patientId);
			}
		}
	}
}
