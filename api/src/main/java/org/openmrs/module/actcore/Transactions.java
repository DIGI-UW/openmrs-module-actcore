/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.IntConsumer;

import org.openmrs.api.context.Context;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class Transactions {
	
	private Transactions() {
	}
	
	/**
	 * Runs the work in a transaction of its own. After a commit the old transaction's resources are
	 * still bound, so writing then needs one.
	 */
	public static void inNewTransaction(final Runnable work) {
		TransactionTemplate template = new TransactionTemplate(
		        Context.getRegisteredComponent("transactionManager", PlatformTransactionManager.class));
		template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		template.execute(new TransactionCallbackWithoutResult() {
			
			@Override
			protected void doInTransactionWithoutResult(TransactionStatus status) {
				work.run();
			}
		});
	}
	
	/**
	 * Runs the work for the patient once the current transaction commits, so a failure in it cannot
	 * roll the save back, and once per patient however many saves of theirs the transaction holds; at
	 * once when no transaction is running. Each owner, such as an advice's class, keeps its own set, so
	 * two instances of one advice do not refresh a patient twice.
	 */
	public static void afterCommit(Object owner, int patientId, IntConsumer work) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			work.accept(patientId);
			return;
		}
		pending(owner, work).patientIds.add(patientId);
	}
	
	// Not a bound resource, which a REQUIRES_NEW suspend leaves bound to the outer transaction.
	private static Pending pending(Object owner, IntConsumer work) {
		for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
			if (synchronization instanceof Pending && ((Pending) synchronization).owner == owner) {
				return (Pending) synchronization;
			}
		}
		Pending pending = new Pending(owner, work);
		TransactionSynchronizationManager.registerSynchronization(pending);
		return pending;
	}
	
	private static final class Pending extends TransactionSynchronizationAdapter {
		
		private final Object owner;
		
		private final IntConsumer work;
		
		private final Set<Integer> patientIds = new LinkedHashSet<Integer>();
		
		private Pending(Object owner, IntConsumer work) {
			this.owner = owner;
			this.work = work;
		}
		
		@Override
		public void afterCommit() {
			for (Integer patientId : patientIds) {
				work.accept(patientId);
			}
		}
	}
}
