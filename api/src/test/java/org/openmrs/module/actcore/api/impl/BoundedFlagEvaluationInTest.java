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

import java.util.function.Supplier;

import org.openmrs.api.APIException;

/**
 * Runs a test's Groovy flag evaluation that should end, again when patientflags 3.0.10's evaluator
 * missed its notify. Its GroovyFlagEvaluatorThread can notify before the caller waits, most often
 * on a busy build machine, and BoundedFlagEvaluation then gives the evaluation up at the bound.
 */
public final class BoundedFlagEvaluationInTest {
	
	static final int ATTEMPTS = 5;
	
	static final long ATTEMPT_MILLIS = 4000;
	
	private BoundedFlagEvaluationInTest() {
	}
	
	public static <T> T retryingAMissedNotify(Supplier<T> evaluation) {
		long bound = BoundedFlagEvaluation.waitMillis;
		BoundedFlagEvaluation.waitMillis = ATTEMPT_MILLIS;
		try {
			for (int attempt = 1;; attempt++) {
				try {
					return evaluation.get();
				}
				catch (APIException e) {
					if (attempt == ATTEMPTS || !String.valueOf(e.getMessage()).startsWith("Gave up evaluating flag")) {
						throw e;
					}
				}
			}
		}
		finally {
			BoundedFlagEvaluation.waitMillis = bound;
		}
	}
}
