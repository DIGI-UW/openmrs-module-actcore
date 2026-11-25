/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.aop;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.IntConsumer;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.openmrs.Encounter;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.api.AdherenceService;
import org.openmrs.module.actcore.api.impl.PatientFlagRefresh;
import org.openmrs.module.actcore.utils.Transactions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Advice on EncounterService that recomputes a patient's adherence rows, and then their flags,
 * after one of their encounters is saved, voided, unvoided or purged. The advice sits outside the
 * service's own transaction, so when no transaction synchronization is active, as on a REST
 * request, the service has already committed and the refresh runs at once, before the call returns.
 * Otherwise, as inside a wider transaction, the refresh waits as
 * {@link Transactions#afterCommit(Object, int, IntConsumer)} describes. Each step of the refresh
 * runs as a unit of its own, as {@link Transactions#inPatientTransaction(int, Runnable)} describes,
 * and a step that fails is logged and leaves the save as it is. The flags are refreshed again here
 * because the patientflags module's own pass may run before the commit, and so miss the change.
 */
public class RefreshAdherenceOnSave implements MethodInterceptor {
	
	private static final Logger log = LoggerFactory.getLogger(RefreshAdherenceOnSave.class);
	
	/**
	 * The time, in milliseconds, beyond which a patient's refresh is logged as a warning rather than at
	 * debug level, since the save waits for the refresh to finish.
	 */
	static final long SLOW_MILLIS = 1000L;
	
	private static final Set<String> METHODS = new HashSet<String>(
	        Arrays.asList("saveEncounter", "voidEncounter", "unvoidEncounter", "purgeEncounter"));
	
	private final IntConsumer refresher;
	
	private final IntConsumer flagRefresher;
	
	public RefreshAdherenceOnSave() {
		this(new IntConsumer() {
			
			@Override
			public void accept(final int patientId) {
				Transactions.inPatientTransaction(patientId, new Runnable() {
					
					@Override
					public void run() {
						Context.getService(AdherenceService.class).refreshPatient(patientId, LocalDate.now());
					}
				});
			}
		}, new IntConsumer() {
			
			// Not in one transaction: it runs each flag in its own, so one that fails loses no other.
			@Override
			public void accept(int patientId) {
				PatientFlagRefresh.refreshPatient(patientId);
			}
		});
	}
	
	RefreshAdherenceOnSave(IntConsumer refresher, IntConsumer flagRefresher) {
		this.refresher = refresher;
		this.flagRefresher = flagRefresher;
	}
	
	/** @see org.aopalliance.intercept.MethodInterceptor#invoke(MethodInvocation) */
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
			Transactions.afterCommit(RefreshAdherenceOnSave.class, patientId, new IntConsumer() {
				
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
		long took = System.currentTimeMillis() - startedAt;
		if (took > SLOW_MILLIS) {
			log.warn("Refreshing the adherence and flags of patient {} took {} ms, which the save waited for", patientId,
			    took);
		} else {
			log.debug("Refreshing the adherence and flags of patient {} took {} ms", patientId, took);
		}
	}
}
