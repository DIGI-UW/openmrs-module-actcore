/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.api.impl;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import org.aopalliance.intercept.MethodInterceptor;
import org.junit.After;
import org.junit.Test;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.UserContext;
import org.openmrs.module.actcore.api.AdherenceService;

public class FullAdherenceRefreshContextTest extends AdherenceContextTest {
	
	private static final long WAIT_SECONDS = 30;
	
	private final List<String> events = new CopyOnWriteArrayList<String>();
	
	private final CountDownLatch held = new CountDownLatch(1);
	
	private final CountDownLatch calling = new CountDownLatch(1);
	
	private MethodInterceptor holding;
	
	@After
	public void removeTheHoldingAdvice() {
		if (holding != null) {
			Context.removeAdvice(AdherenceService.class, holding);
		}
	}
	
	@Test
	public void runIfIdle_shouldStoreTheAdherenceOfAPatientPrescribedARegimen() {
		prescribe(encounter(7, 40), q28, 40, null);
		Context.flushSession();
		
		FullAdherenceRefresh.runIfIdle();
		
		assertEquals(1, adherenceRows(7));
	}
	
	@Test
	public void refreshBeforeFlags_shouldStoreTheAdherenceOfAPatientPrescribedARegimen() {
		prescribe(encounter(7, 40), q28, 40, null);
		Context.flushSession();
		
		FullAdherenceRefresh.refreshBeforeFlags();
		
		assertEquals(1, adherenceRows(7));
	}
	
	@Test
	public void runIfIdle_shouldSkipWhileAnotherRunIsInProgress() throws InterruptedException {
		prescribe(encounter(7, 40), q28, 40, null);
		Context.flushSession();
		Thread other = holdTheLockUntilThisThreadWaits();
		
		calling.countDown();
		FullAdherenceRefresh.runIfIdle();
		other.join(SECONDS.toMillis(WAIT_SECONDS));
		
		assertEquals(Collections.singletonList("released"), events);
		assertEquals(0, adherenceRows(7));
	}
	
	@Test
	public void refreshBeforeFlags_shouldWaitForARunInProgress() throws InterruptedException {
		prescribe(encounter(7, 40), q28, 40, null);
		Context.flushSession();
		Thread other = holdTheLockUntilThisThreadWaits();
		
		calling.countDown();
		FullAdherenceRefresh.refreshBeforeFlags();
		other.join(SECONDS.toMillis(WAIT_SECONDS));
		
		assertEquals(Arrays.asList("released", "refreshed"), events);
		assertEquals(1, adherenceRows(7));
	}
	
	/**
	 * Starts a run on another thread whose refresh, in place of writing rows, holds the lock until this
	 * thread has counted down {@link #calling} and then waits, each wait bounded by
	 * {@link #WAIT_SECONDS}. That run records "released" as it lets go, and a refresh on this thread
	 * records "refreshed" as it starts.
	 */
	private Thread holdTheLockUntilThisThreadWaits() throws InterruptedException {
		final Thread test = Thread.currentThread();
		final UserContext user = Context.getUserContext();
		holding = invocation -> {
			if (!"refreshAll".equals(invocation.getMethod().getName())) {
				return invocation.proceed();
			}
			if (Thread.currentThread() == test) {
				events.add("refreshed");
				return invocation.proceed();
			}
			held.countDown();
			calling.await(WAIT_SECONDS, SECONDS);
			long deadline = System.nanoTime() + SECONDS.toNanos(WAIT_SECONDS);
			while (test.getState() != Thread.State.WAITING && test.getState() != Thread.State.TIMED_WAITING
			        && System.nanoTime() < deadline) {
				Thread.yield();
			}
			events.add("released");
			return 0;
		};
		Context.addAdvice(AdherenceService.class, holding);
		Thread other = new Thread(() -> {
			Context.setUserContext(user);
			try {
				FullAdherenceRefresh.runIfIdle();
			}
			finally {
				Context.clearUserContext();
			}
		});
		other.start();
		assertTrue(held.await(WAIT_SECONDS, SECONDS));
		return other;
	}
	
	private static int adherenceRows(int patientId) {
		return ((Number) Context.getAdministrationService()
		        .executeSQL("select count(*) from actcore_prophylaxis_adherence where patient_id = " + patientId, true)
		        .get(0).get(0)).intValue();
	}
}
