/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.utils;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

import org.openmrs.api.context.Context;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.ResourceTransactionManager;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Helpers that run work in a Spring transaction, for code that no service of the module wraps in
 * one, such as advice classes and scheduled tasks.
 */
public final class Transactions {
	
	private Transactions() {
	}
	
	/**
	 * Runs the work in a new transaction, suspending any transaction already in progress. Code that
	 * runs after a commit needs this to write, because the committed transaction's resources are still
	 * bound to the thread at that point. When no transaction synchronization is active, the session
	 * bound to the thread, such as a REST request's, is set aside while the work runs and bound again
	 * afterwards, so a rollback of the work cannot clear it and detach what the caller loaded. Inside a
	 * scope that activates synchronization without starting a transaction, such as one with SUPPORTS
	 * propagation, the session is not set aside.
	 *
	 * @param work the work to run; a runtime exception it throws rolls the new transaction back and
	 *            propagates
	 */
	public static void inNewTransaction(final Runnable work) {
		PlatformTransactionManager manager = transactionManager();
		TransactionTemplate template = new TransactionTemplate(manager);
		template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		// With no synchronization to suspend, Spring would join the bound session and clear it on a rollback.
		Object sessionFactory = ((ResourceTransactionManager) manager).getResourceFactory();
		Object callersSession = TransactionSynchronizationManager.isSynchronizationActive() ? null
		        : TransactionSynchronizationManager.unbindResourceIfPossible(sessionFactory);
		try {
			template.execute(new TransactionCallbackWithoutResult() {
				
				@Override
				protected void doInTransactionWithoutResult(TransactionStatus status) {
					work.run();
				}
			});
		}
		finally {
			if (callersSession != null) {
				TransactionSynchronizationManager.bindResource(sessionFactory, callersSession);
			}
		}
	}
	
	/**
	 * A piece of work for {@link Transactions#inTransaction(Work)} that may throw any exception,
	 * checked ones included, as an advice's call to proceed does.
	 */
	public interface Work<T> {
		
		/**
		 * Runs the work.
		 *
		 * @return the work's result
		 * @throws Throwable whatever the work throws
		 */
		T run() throws Throwable;
	}
	
	/**
	 * Runs the work in the current transaction, or in a new transaction when none is in progress. The
	 * service calls the work makes therefore join that one transaction, so none of them commits, which
	 * would flush the session, before the work returns, and an exception thrown by the work rolls all
	 * of it back.
	 *
	 * @param work the work to run
	 * @return what the work returns
	 * @throws Throwable what the work throws, checked exceptions included, unwrapped
	 */
	public static <T> T inTransaction(final Work<T> work) throws Throwable {
		try {
			return new TransactionTemplate(transactionManager()).execute(status -> {
				try {
					return work.run();
				}
				catch (RuntimeException | Error e) {
					throw e;
				}
				catch (Throwable e) {
					throw new Checked(e);
				}
			});
		}
		catch (Checked e) {
			throw e.getCause();
		}
	}
	
	/**
	 * Runs the work as {@link #inTransaction(Work)} does, for work that throws no checked exception.
	 *
	 * @param work the work to run
	 * @return what the work returns
	 */
	public static <T> T supplyInTransaction(Supplier<T> work) {
		return new TransactionTemplate(transactionManager()).execute(status -> work.get());
	}
	
	private static PlatformTransactionManager transactionManager() {
		return Context.getRegisteredComponent("transactionManager", PlatformTransactionManager.class);
	}
	
	/**
	 * Wraps a checked exception so that it can leave the transaction callback, which cannot declare
	 * one, and be unwrapped again outside it.
	 */
	private static final class Checked extends RuntimeException {
		
		private Checked(Throwable cause) {
			super(cause);
		}
	}
	
	/**
	 * Runs the work for the patient once the current transaction commits, or at once when no
	 * transaction synchronization is active, so that a failure in the work cannot roll back the save
	 * that triggered it. Calls made with the same owner, such as an advice class, within one
	 * transaction run the work once for each patient, however many times a patient is passed.
	 *
	 * @param owner what the work is registered under in the current transaction; a later call with the
	 *            same owner adds its patient to that registration and its own work is not used
	 * @param patientId the patient to run the work for
	 * @param work the work, given the patient id
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
	
	/**
	 * Collects the patients that one owner registered during a transaction, and once the transaction
	 * commits, runs the owner's work for each of them in the order they were first added.
	 */
	private static final class Pending extends TransactionSynchronizationAdapter {
		
		private final Object owner;
		
		private final IntConsumer work;
		
		private final Set<Integer> patientIds = new LinkedHashSet<Integer>();
		
		private Pending(Object owner, IntConsumer work) {
			this.owner = owner;
			this.work = work;
		}
		
		/** @see org.springframework.transaction.support.TransactionSynchronization#afterCommit() */
		@Override
		public void afterCommit() {
			for (Integer patientId : patientIds) {
				work.accept(patientId);
			}
		}
	}
}
