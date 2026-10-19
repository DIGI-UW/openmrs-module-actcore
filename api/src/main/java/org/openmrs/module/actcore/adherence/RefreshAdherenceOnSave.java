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
import java.util.Set;
import java.util.function.IntConsumer;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.openmrs.Encounter;
import org.openmrs.module.actcore.Transactions;
import org.openmrs.module.actcore.task.PatientFlagRefreshTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Advice on EncounterService that recomputes a patient's adherence rows, then their flags, once an
 * encounter of theirs is saved, voided or purged; patientflags' own pass may precede the commit.
 */
public class RefreshAdherenceOnSave implements MethodInterceptor {
	
	private static final Logger log = LoggerFactory.getLogger(RefreshAdherenceOnSave.class);
	
	private static final Set<String> METHODS = new HashSet<String>(
	        Arrays.asList("saveEncounter", "voidEncounter", "unvoidEncounter", "purgeEncounter"));
	
	private final IntConsumer refresher;
	
	private final IntConsumer flagRefresher;
	
	public RefreshAdherenceOnSave() {
		this(new IntConsumer() {
			
			@Override
			public void accept(final int patientId) {
				Transactions.inNewTransaction(new Runnable() {
					
					@Override
					public void run() {
						new AdherenceRefresh().refreshPatient(patientId, LocalDate.now());
					}
				});
			}
		}, new IntConsumer() {
			
			// Not in one transaction: it runs each flag in its own, so one that fails loses no other.
			@Override
			public void accept(int patientId) {
				PatientFlagRefreshTask.refreshPatient(patientId);
			}
		});
	}
	
	RefreshAdherenceOnSave(IntConsumer refresher, IntConsumer flagRefresher) {
		this.refresher = refresher;
		this.flagRefresher = flagRefresher;
	}
	
	@Override
	public Object invoke(MethodInvocation invocation) throws Throwable {
		Object[] args = invocation.getArguments();
		boolean watched = METHODS.contains(invocation.getMethod().getName()) && args.length > 0
		        && args[0] instanceof Encounter;
		Integer patientId = watched && ((Encounter) args[0]).getPatient() != null
		        ? ((Encounter) args[0]).getPatient().getPatientId()
		        : null;
		Object result = invocation.proceed();
		if (patientId != null) {
			// Once the save commits, so a failed refresh cannot roll it back, and once per patient however
			// many encounters the transaction saves.
			Transactions.afterCommit(this, patientId, new IntConsumer() {
				
				@Override
				public void accept(int committed) {
					refresh(committed);
				}
			});
		}
		return result;
	}
	
	private void refresh(int patientId) {
		long startedAt = System.currentTimeMillis();
		try {
			refresher.accept(patientId);
		}
		catch (RuntimeException e) {
			// The save stands; the next full refresh writes the row.
			log.error("Could not refresh the prophylaxis adherence of patient {}", patientId, e);
		}
		// Even when the row failed: patientflags may have evaluated every flag before the commit.
		try {
			flagRefresher.accept(patientId);
		}
		catch (RuntimeException e) {
			log.error("Could not refresh the flags of patient {}", patientId, e);
		}
		// The save waits for this.
		log.debug("Refreshing the adherence and flags of patient {} took {} ms", patientId,
		    System.currentTimeMillis() - startedAt);
	}
}
