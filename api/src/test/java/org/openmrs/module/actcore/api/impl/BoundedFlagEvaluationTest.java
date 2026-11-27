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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.openmrs.api.APIException;
import org.openmrs.module.patientflags.Flag;
import org.openmrs.module.patientflags.evaluator.GroovyFlagEvaluator;
import org.openmrs.module.patientflags.evaluator.SQLFlagEvaluator;

public class BoundedFlagEvaluationTest {
	
	private static Flag flag(Class<?> evaluator) {
		Flag flag = new Flag();
		flag.setName("hold");
		flag.setEvaluator(evaluator.getName());
		return flag;
	}
	
	/**
	 * Waits as patientflags' fetchResultCohort does, and fails as its evalCohort does when interrupted.
	 */
	private static Boolean waitForANotifyThatNeverComes() {
		Object monitor = new Object();
		synchronized (monitor) {
			try {
				monitor.wait();
			}
			catch (InterruptedException e) {
				throw new APIException("Unable to evaluate Groovy Flag hold, null", e);
			}
		}
		return Boolean.TRUE;
	}
	
	@Test(timeout = 10000)
	public void within_shouldGiveUpAGroovyEvaluationThatDoesNotEndAndClearTheInterrupt() {
		long startedAt = System.currentTimeMillis();
		try {
			BoundedFlagEvaluation.within(flag(GroovyFlagEvaluator.class),
			    BoundedFlagEvaluationTest::waitForANotifyThatNeverComes, 200);
			fail("The evaluation was not given up");
		}
		catch (APIException e) {
			assertTrue(e.getMessage(), e.getMessage().startsWith("Gave up evaluating flag 'hold' after 0 s"));
			assertTrue(e.getCause() instanceof APIException);
		}
		assertTrue(System.currentTimeMillis() - startedAt < 5000);
		assertFalse(Thread.interrupted());
	}
	
	@Test(timeout = 10000)
	public void within_shouldLeaveAnEvaluationThatIsNotGroovyUnbounded() throws InterruptedException {
		Boolean matched = BoundedFlagEvaluation.within(flag(SQLFlagEvaluator.class), () -> {
			try {
				Thread.sleep(400);
			}
			catch (InterruptedException e) {
				throw new IllegalStateException("interrupted", e);
			}
			return Boolean.TRUE;
		}, 100);
		
		assertEquals(Boolean.TRUE, matched);
		assertFalse(Thread.interrupted());
	}
	
	@Test(timeout = 10000)
	public void within_shouldClearTheInterruptWhenAGroovyEvaluationEndsAfterTheBoundWithoutWaiting() {
		Boolean matched = BoundedFlagEvaluation.within(flag(GroovyFlagEvaluator.class), () -> {
			long until = System.currentTimeMillis() + 400;
			while (System.currentTimeMillis() < until) {
				// Busy, as a script that never waits is, so the interrupt is not consumed.
			}
			return Boolean.TRUE;
		}, 100);
		
		assertEquals(Boolean.TRUE, matched);
		assertFalse(Thread.interrupted());
	}
	
	@Test
	public void within_shouldReturnWhatAGroovyEvaluationThatEndsGives() {
		Boolean matched = BoundedFlagEvaluation.within(flag(GroovyFlagEvaluator.class), () -> Boolean.FALSE, 5000);
		
		assertEquals(Boolean.FALSE, matched);
		assertFalse(Thread.interrupted());
	}
	
	@Test
	public void within_shouldPassOnAFailureOfAGroovyEvaluationThatEndsInTime() {
		APIException failure = new APIException("criteria is broken");
		try {
			BoundedFlagEvaluation.within(flag(GroovyFlagEvaluator.class), () -> {
				throw failure;
			}, 5000);
			fail("The failure was not passed on");
		}
		catch (APIException e) {
			assertSame(failure, e);
		}
	}
	
	@Test
	public void within_shouldWaitThirtySecondsByDefault() {
		assertEquals(30, BoundedFlagEvaluation.WAIT_SECONDS);
	}
}
