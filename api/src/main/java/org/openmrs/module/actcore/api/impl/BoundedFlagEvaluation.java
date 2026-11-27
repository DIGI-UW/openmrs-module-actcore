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

import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.Daemon;
import org.openmrs.api.context.UserContext;
import org.openmrs.module.actcore.ActCoreActivator;
import org.openmrs.module.patientflags.Flag;
import org.openmrs.module.patientflags.evaluator.GroovyFlagEvaluator;

/**
 * Gives up a Groovy flag's evaluation that ACT Core starts and that does not end in time. In
 * patientflags 3.0.10, GroovyFlagEvaluator runs the script on a thread of its own and waits for it
 * in GroovyFlagEvaluatorThread.fetchResultCohort, a {@code wait()} with no condition, so the caller
 * waits forever when the script's thread notifies before the caller waits, and for as long as the
 * script runs otherwise. So the evaluation runs on a worker thread, which the caller waits for no
 * longer than the bound.
 * <p>
 * The worker runs as the caller. A daemon caller's worker is started by
 * {@link Daemon#runInDaemonThread} with ACT Core's daemon token; any other caller's worker shares
 * the caller's UserContext, proxy privileges included, on a JVM daemon thread. Either opens a
 * Hibernate session of its own and closes it when the evaluation ends, so it reads what is
 * committed. On giving up, the caller interrupts the worker, which ends a wait that missed its
 * notify. A worker whose script is still running ends when the script does, so a script that never
 * ends keeps its worker, and patientflags' thread, for good. Each Groovy flag that does not end
 * costs its caller the bound, once per flag. Evaluations by other evaluators run on the caller's
 * thread, unbounded.
 */
final class BoundedFlagEvaluation {
	
	/** How long ACT Core waits for one Groovy flag's evaluation. */
	static final long WAIT_SECONDS = 30;
	
	// The bound the call sites use; a test shortens it.
	static volatile long waitMillis = TimeUnit.SECONDS.toMillis(WAIT_SECONDS);
	
	private BoundedFlagEvaluation() {
	}
	
	/**
	 * Runs one evaluation of the flag, giving it up after {@link #WAIT_SECONDS} when the flag is a
	 * Groovy flag.
	 *
	 * @param flag the flag being evaluated
	 * @param evaluation the evaluation
	 * @return what the evaluation gives
	 * @throws APIException when a Groovy flag's evaluation does not end in time, or the wait for it is
	 *             interrupted
	 */
	static <T> T within(Flag flag, Supplier<T> evaluation) {
		return within(flag, evaluation, waitMillis);
	}
	
	static <T> T within(Flag flag, Supplier<T> evaluation, long millis) {
		if (!GroovyFlagEvaluator.class.getName().equals(flag.getEvaluator())) {
			return evaluation.get();
		}
		FutureTask<T> task = new FutureTask<T>(evaluation::get);
		start(task);
		try {
			return task.get(millis, TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException e) {
			task.cancel(true);
			throw new APIException("Gave up evaluating flag '" + flag.getName() + "' after "
			        + TimeUnit.MILLISECONDS.toSeconds(millis) + " s: its Groovy evaluation had not ended", e);
		}
		catch (InterruptedException e) {
			task.cancel(true);
			Thread.currentThread().interrupt();
			throw new APIException("Interrupted while evaluating flag '" + flag.getName() + "'", e);
		}
		catch (ExecutionException e) {
			// A Supplier throws nothing checked.
			if (e.getCause() instanceof Error) {
				throw (Error) e.getCause();
			}
			throw (RuntimeException) e.getCause();
		}
	}
	
	private static void start(Runnable task) {
		if (Daemon.isDaemonThread()) {
			Daemon.runInDaemonThread(task, ActCoreActivator.getDaemonToken());
			return;
		}
		UserContext caller = Context.getUserContext();
		Thread worker = new Thread(() -> {
			Context.setUserContext(caller);
			Context.openSessionWithCurrentUser();
			try {
				task.run();
			}
			finally {
				Context.closeSessionWithCurrentUser();
				Context.clearUserContext();
			}
		}, "ACT Core flag evaluation");
		worker.setDaemon(true);
		worker.start();
	}
}
