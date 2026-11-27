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

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.openmrs.api.APIException;
import org.openmrs.module.patientflags.Flag;
import org.openmrs.module.patientflags.evaluator.GroovyFlagEvaluator;

/**
 * Gives up a Groovy flag's evaluation that ACT Core starts and that does not end in time. In
 * patientflags 3.0.10, GroovyFlagEvaluator runs the script on a thread of its own and waits for it
 * in GroovyFlagEvaluatorThread.fetchResultCohort, a {@code wait()} with no condition, so the caller
 * waits forever when the script's thread notifies before the caller waits. Interrupting the caller
 * ends that wait, and patientflags reports it as an APIException, as it reports a script that
 * fails. Evaluations by other evaluators are left unbounded.
 */
final class BoundedFlagEvaluation {
	
	/** How long ACT Core waits for one Groovy flag's evaluation. */
	static final long WAIT_SECONDS = 30;
	
	private static final ScheduledThreadPoolExecutor TIMER = timer();
	
	private BoundedFlagEvaluation() {
	}
	
	/** Whether the evaluation has ended, and whether the timer gave it up first. */
	private static final class Timing {
		
		boolean ended;
		
		boolean gaveUp;
	}
	
	private static ScheduledThreadPoolExecutor timer() {
		ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, work -> {
			Thread thread = new Thread(work, "ACT Core flag evaluation timer");
			thread.setDaemon(true);
			return thread;
		});
		timer.setRemoveOnCancelPolicy(true);
		// The thread ends when no evaluation is being timed, so a stopped module leaves none behind.
		timer.setKeepAliveTime(1, TimeUnit.SECONDS);
		timer.allowCoreThreadTimeOut(true);
		return timer;
	}
	
	/**
	 * Runs one evaluation of the flag, giving it up after {@link #WAIT_SECONDS} when the flag is a
	 * Groovy flag.
	 *
	 * @param flag the flag being evaluated
	 * @param evaluation the evaluation, on this thread
	 * @return what the evaluation gives
	 * @throws APIException when a Groovy flag's evaluation does not end in time
	 */
	static <T> T within(Flag flag, Supplier<T> evaluation) {
		return within(flag, evaluation, TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
	}
	
	static <T> T within(Flag flag, Supplier<T> evaluation, long millis) {
		if (!GroovyFlagEvaluator.class.getName().equals(flag.getEvaluator())) {
			return evaluation.get();
		}
		Thread caller = Thread.currentThread();
		Timing timing = new Timing();
		// The interrupt and the end of the evaluation take this lock, so no interrupt is left on the thread.
		ScheduledFuture<?> giveUp = TIMER.schedule(() -> {
			synchronized (timing) {
				if (!timing.ended) {
					timing.gaveUp = true;
					caller.interrupt();
				}
			}
		}, millis, TimeUnit.MILLISECONDS);
		try {
			return evaluation.get();
		}
		catch (RuntimeException e) {
			synchronized (timing) {
				if (timing.gaveUp) {
					throw new APIException("Gave up evaluating flag '" + flag.getName() + "' after "
					        + TimeUnit.MILLISECONDS.toSeconds(millis)
					        + " s: its Groovy evaluation did not end, as patientflags' evaluator can miss the notify it waits for",
					        e);
				}
			}
			throw e;
		}
		finally {
			giveUp.cancel(false);
			synchronized (timing) {
				timing.ended = true;
				if (timing.gaveUp) {
					Thread.interrupted();
				}
			}
		}
	}
}
