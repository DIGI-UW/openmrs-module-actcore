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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import org.openmrs.Cohort;
import org.openmrs.Patient;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.Daemon;
import org.openmrs.api.context.UserContext;
import org.openmrs.module.DaemonTokens;
import org.openmrs.module.actcore.ActCoreActivator;
import org.openmrs.module.patientflags.Flag;
import org.openmrs.module.patientflags.api.FlagService;
import org.openmrs.module.patientflags.evaluator.GroovyFlagEvaluator;
import org.openmrs.module.patientflags.evaluator.GroovyFlagEvaluatorThread;
import org.openmrs.test.BaseModuleContextSensitiveTest;
import org.openmrs.util.PrivilegeConstants;

public class BoundedFlagEvaluationContextTest extends BaseModuleContextSensitiveTest {
	
	private static final long BOUND_MILLIS = 300;
	
	// Room for starting the worker and for a slow build machine, well short of what the scripts take.
	private static final long SLACK_MILLIS = 1000;
	
	private static Flag groovyFlag(String criteria) {
		Flag flag = new Flag();
		flag.setName("groovy");
		flag.setCriteria(criteria);
		flag.setEvaluator(GroovyFlagEvaluator.class.getName());
		flag.setEnabled(Boolean.TRUE);
		return flag;
	}
	
	@Test(timeout = 20000)
	public void within_shouldGiveUpAtTheBoundAWaitThatMissedItsNotifyAndEndTheWorker() throws Exception {
		Flag flag = groovyFlag("return testCohort");
		AtomicReference<Thread> worker = new AtomicReference<Thread>();
		long startedAt = System.currentTimeMillis();
		try {
			BoundedFlagEvaluation.within(flag, () -> {
				worker.set(Thread.currentThread());
				GroovyFlagEvaluatorThread evaluator = new GroovyFlagEvaluatorThread(flag, new Cohort(), null);
				Thread script = new Thread(evaluator);
				script.start();
				try {
					// The script's thread notifies before anyone waits, as it can in GroovyFlagEvaluator.evalCohort.
					script.join();
					return evaluator.fetchResultCohort();
				}
				catch (Exception e) {
					throw new APIException("Unable to evaluate Groovy Flag groovy, " + e, e);
				}
			}, BOUND_MILLIS);
			fail("The evaluation was not given up");
		}
		catch (APIException e) {
			assertTrue(e.getMessage(), e.getMessage().startsWith("Gave up evaluating flag 'groovy' after 0 s"));
		}
		long elapsed = System.currentTimeMillis() - startedAt;
		
		assertTrue(String.valueOf(elapsed), elapsed >= BOUND_MILLIS && elapsed < BOUND_MILLIS + SLACK_MILLIS);
		worker.get().join(5000);
		assertFalse(worker.get().isAlive());
		assertFalse(Thread.interrupted());
	}
	
	@Test(timeout = 20000)
	public void within_shouldGiveUpAtTheBoundAGroovyScriptThatIsStillRunning() {
		Patient patient = Context.getPatientService().getPatient(2);
		// It would match: the script gives the patient back once it wakes.
		Flag flag = groovyFlag("Thread.sleep(3000); return testCohort");
		long startedAt = System.currentTimeMillis();
		try {
			BoundedFlagEvaluation.within(flag, () -> flag.eval(patient, new HashMap<Object, Object>()), BOUND_MILLIS);
			fail("The evaluation was not given up");
		}
		catch (APIException e) {
			assertTrue(e.getMessage(), e.getMessage().startsWith("Gave up evaluating flag 'groovy'"));
		}
		long elapsed = System.currentTimeMillis() - startedAt;
		
		assertTrue(String.valueOf(elapsed), elapsed >= BOUND_MILLIS && elapsed < BOUND_MILLIS + SLACK_MILLIS);
		assertFalse(Thread.interrupted());
	}
	
	@Test(timeout = 20000)
	public void within_shouldGiveWhatAGroovyScriptThatEndsInTimeGives() {
		Patient patient = Context.getPatientService().getPatient(2);
		Flag matches = groovyFlag("return testCohort");
		Flag matchesNobody = groovyFlag("return new Cohort()");
		
		assertEquals(Boolean.TRUE,
		    BoundedFlagEvaluation.within(matches, () -> matches.eval(patient, new HashMap<Object, Object>()), 10000));
		assertEquals(Boolean.FALSE, BoundedFlagEvaluation.within(matchesNobody,
		    () -> matchesNobody.eval(patient, new HashMap<Object, Object>()), 10000));
	}
	
	@Test(timeout = 20000)
	public void within_shouldPassOnAFailureOfAGroovyEvaluationThatEndsInTime() {
		APIException failure = new APIException("criteria is broken");
		try {
			BoundedFlagEvaluation.within(groovyFlag("return testCohort"), () -> {
				throw failure;
			}, 10000);
			fail("The failure was not passed on");
		}
		catch (APIException e) {
			assertSame(failure, e);
		}
	}
	
	// No timeout: JUnit would run the test on a daemon thread, and the worker would be one too.
	@Test
	public void within_shouldRunTheWorkerOnADaemonThreadAsTheCallerWithItsProxyPrivileges() {
		Thread callerThread = Thread.currentThread();
		assertFalse(callerThread.isDaemon());
		Context.logout();
		UserContext caller = Context.getUserContext();
		Context.addProxyPrivilege(PrivilegeConstants.GET_PATIENTS);
		try {
			List<Object> seen = BoundedFlagEvaluation.within(groovyFlag("return testCohort"),
			    () -> Arrays.<Object> asList(Thread.currentThread() != callerThread, Thread.currentThread().isDaemon(),
			        Context.getUserContext() == caller, Context.hasPrivilege(PrivilegeConstants.GET_PATIENTS),
			        Context.hasPrivilege(PrivilegeConstants.EDIT_PATIENTS),
			        Context.getPatientService().getPatient(2).getPatientId()),
			    10000);
			
			assertEquals(Arrays.<Object> asList(true, true, true, true, false, 2), seen);
		}
		finally {
			Context.removeProxyPrivilege(PrivilegeConstants.GET_PATIENTS);
		}
	}
	
	@Test(timeout = 20000)
	public void within_shouldRunTheWorkerOfADaemonCallerAsADaemon() {
		Flag flag = groovyFlag("return testCohort");
		List<Object> seen = new ArrayList<Object>();
		DaemonTokens.pass("actcore", new ActCoreActivator());
		try {
			Daemon.runInDaemonThreadAndWait(() -> {
				Thread callerThread = Thread.currentThread();
				seen.addAll(BoundedFlagEvaluation
				        .within(
				            flag, () -> Arrays.<Object> asList(Thread.currentThread() != callerThread,
				                Daemon.isDaemonThread(), Context.getService(FlagService.class)
				                        .getFlaggedPatients(flag, new HashMap<Object, Object>()).getMemberships().isEmpty()),
				            10000));
			}, ActCoreActivator.getDaemonToken());
		}
		finally {
			new ActCoreActivator().setDaemonToken(null);
		}
		
		assertEquals(Arrays.<Object> asList(true, true, false), seen);
	}
	
	@Test(timeout = 20000)
	public void within_shouldStopWaitingWhenTheCallerIsInterruptedAndKeepTheInterrupt() {
		Thread.currentThread().interrupt();
		try {
			BoundedFlagEvaluation.within(groovyFlag("return testCohort"), () -> {
				try {
					Thread.sleep(3000);
				}
				catch (InterruptedException e) {
					throw new IllegalStateException("interrupted", e);
				}
				return Boolean.TRUE;
			}, 10000);
			fail("The wait did not stop");
		}
		catch (APIException e) {
			assertTrue(e.getMessage(), e.getMessage().startsWith("Interrupted while evaluating flag 'groovy'"));
		}
		assertTrue(Thread.interrupted());
	}
}
