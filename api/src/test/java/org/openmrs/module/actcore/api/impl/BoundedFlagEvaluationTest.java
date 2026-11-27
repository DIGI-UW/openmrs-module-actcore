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
import static org.junit.Assert.fail;

import org.junit.Test;
import org.openmrs.api.APIException;
import org.openmrs.module.patientflags.Flag;
import org.openmrs.module.patientflags.evaluator.SQLFlagEvaluator;

public class BoundedFlagEvaluationTest {
	
	@Test(timeout = 10000)
	public void within_shouldRunAnEvaluationThatIsNotGroovyOnTheCallersThreadUnbounded() {
		Flag flag = new Flag();
		flag.setName("hold");
		flag.setEvaluator(SQLFlagEvaluator.class.getName());
		Thread caller = Thread.currentThread();
		
		Thread ranOn = BoundedFlagEvaluation.within(flag, () -> {
			try {
				Thread.sleep(400);
			}
			catch (InterruptedException e) {
				throw new IllegalStateException("interrupted", e);
			}
			return Thread.currentThread();
		}, 100);
		
		assertSame(caller, ranOn);
		assertFalse(Thread.interrupted());
	}
	
	@Test
	public void within_shouldWaitThirtySecondsByDefault() {
		assertEquals(30, BoundedFlagEvaluation.WAIT_SECONDS);
		assertEquals(30000, BoundedFlagEvaluation.waitMillis);
	}
	
	@Test
	public void retryingAMissedNotify_shouldEvaluateAgainOnlyAfterAGiveUp() {
		int[] calls = { 0 };
		
		assertEquals("ended", BoundedFlagEvaluationInTest.retryingAMissedNotify(() -> {
			if (++calls[0] < 3) {
				throw new APIException("Gave up evaluating flag 'groovy' after 4 s: its Groovy evaluation had not ended");
			}
			return "ended";
		}));
		assertEquals(3, calls[0]);
		
		calls[0] = 0;
		try {
			BoundedFlagEvaluationInTest.retryingAMissedNotify(() -> {
				calls[0]++;
				throw new APIException("criteria is broken");
			});
			fail("The failure was not passed on");
		}
		catch (APIException e) {
			assertEquals(1, calls[0]);
		}
		assertEquals(BoundedFlagEvaluation.WAIT_SECONDS * 1000, BoundedFlagEvaluation.waitMillis);
	}
}
