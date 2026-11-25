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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.hibernate.SessionFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openmrs.GlobalProperty;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.AdministrationService;
import org.openmrs.api.ProgramWorkflowService;
import org.openmrs.api.UserService;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.Daemon;
import org.openmrs.module.DaemonTokens;
import org.openmrs.module.actcore.tasks.AdherenceRefreshTask;
import org.openmrs.module.actcore.tasks.PatientFlagRefreshTask;
import org.openmrs.module.patientflags.api.FlagService;
import org.openmrs.module.patientflags.api.impl.FlagServiceImpl;
import org.openmrs.test.BaseModuleContextSensitiveTest;
import org.openmrs.scheduler.SchedulerService;
import org.openmrs.scheduler.TaskDefinition;
import org.openmrs.util.PrivilegeConstants;
import org.springframework.aop.AfterReturningAdvice;
import org.springframework.aop.MethodBeforeAdvice;
import org.springframework.aop.framework.Advised;
import org.springframework.beans.factory.annotation.Autowired;

public class ActCoreActivatorContextTest extends BaseModuleContextSensitiveTest {
	
	private static final String HIV_PROGRAM = "da4a0391-ba62-4fad-ad66-1e3722d16380";
	
	/** The concept of the terminal state in the standard test data's HIV program. */
	private static final String TERMINAL_STATE_CONCEPT = "7d104a6f-8337-4afa-b936-41083a5d9d88";
	
	private static final String ENROLMENT = "5b0c1f9e-4d0a-4c8e-9a3f-0e7d2b6c4a19";
	
	private static final String FLAGS_USERNAME = "patientflags.username";
	
	@Autowired
	private SessionFactory sessionFactory;
	
	@Before
	public void passTheDaemonToken() {
		DaemonTokens.pass("actcore", new ActCoreActivator());
	}
	
	/**
	 * Undoes what the tests committed through the daemon thread, which the test's own transaction
	 * cannot roll back.
	 */
	@After
	public void undoWhatTheDaemonCommitted() {
		asDaemon(() -> {
			AdministrationService admin = Context.getAdministrationService();
			admin.executeSQL("delete from patient_state where patient_program_id in"
			        + " (select patient_program_id from patient_program where uuid = '" + ENROLMENT + "')",
			    false);
			admin.executeSQL("delete from patient_program where uuid = '" + ENROLMENT + "'", false);
			admin.executeSQL("update person set dead = false, death_date = null where person_id = 7", false);
			admin.executeSQL("delete from global_property where property in ('" + ActCoreConstants.GP_REGISTRY_PROGRAM
			        + "', '" + ActCoreConstants.GP_REGISTRY_DEATH_STATE_CONCEPT + "', '" + FLAGS_USERNAME + "')",
			    false);
		});
		sessionFactory.getCache().evictAllRegions();
		new ActCoreActivator().setDaemonToken(null);
	}
	
	@Test
	public void updatesTheDescriptionOfATaskAnEarlierVersionRegistered() {
		SchedulerService schedulerService = Context.getSchedulerService();
		TaskDefinition task = new TaskDefinition();
		task.setName(ActCoreActivator.REFRESH_TASK_NAME);
		task.setDescription("an older description");
		task.setTaskClass(PatientFlagRefreshTask.class.getName());
		task.setRepeatInterval(3600L);
		Date startTime = new Date(1500000000000L);
		task.setStartTime(startTime);
		task.setStartOnStartup(Boolean.TRUE);
		task.setStarted(Boolean.FALSE);
		schedulerService.saveTaskDefinition(task);
		
		new ActCoreActivator().started();
		
		TaskDefinition refreshed = schedulerService.getTaskByName(ActCoreActivator.REFRESH_TASK_NAME);
		assertTrue(refreshed.getDescription(),
		    refreshed.getDescription().startsWith("Re-evaluates every enabled, unretired patient flag"));
		assertEquals(Long.valueOf(3600L), refreshed.getRepeatInterval());
		assertEquals(startTime.getTime(), refreshed.getStartTime().getTime());
	}
	
	@Test
	public void movesATaskAnEarlierVersionRegisteredUnderItsOldClass() {
		SchedulerService schedulerService = Context.getSchedulerService();
		TaskDefinition task = new TaskDefinition();
		task.setName(ActCoreActivator.ADHERENCE_TASK_NAME);
		task.setTaskClass(AdherenceRefreshTask.class.getName());
		task.setRepeatInterval(3600L);
		task.setStartTime(new Date(1500000000000L));
		task.setStartOnStartup(Boolean.TRUE);
		task.setStarted(Boolean.FALSE);
		schedulerService.saveTaskDefinition(task);
		// As a database that ran rhdflags holds it; core will not save a class it cannot load.
		Context.flushSession();
		Context.getAdministrationService().executeSQL(
		    "update scheduler_task_config set schedulable_class ="
		            + " 'org.openmrs.module.rhdflags.adherence.AdherenceRefreshTask' where task_config_id = " + task.getId(),
		    false);
		Context.clearSession();
		
		new ActCoreActivator().started();
		Context.flushSession();
		
		List<List<Object>> saved = Context.getAdministrationService().executeSQL(
		    "select schedulable_class, repeat_interval from scheduler_task_config where task_config_id = " + task.getId(),
		    true);
		assertEquals(AdherenceRefreshTask.class.getName(), saved.get(0).get(0));
		assertEquals(3600L, ((Number) saved.get(0).get(1)).longValue());
	}
	
	@Test
	public void reschedulesTasksAnEarlierVersionRegisteredUnderTheirClassesBeforeTheyMovedToTasks() throws Exception {
		SchedulerService schedulerService = Context.getSchedulerService();
		savedUnder(ActCoreActivator.REFRESH_TASK_NAME, "org.openmrs.module.actcore.task.PatientFlagRefreshTask");
		savedUnder(ActCoreActivator.ADHERENCE_TASK_NAME, "org.openmrs.module.actcore.adherence.AdherenceRefreshTask");
		
		new ActCoreActivator().started();
		
		TaskDefinition flags = schedulerService.getTaskByName(ActCoreActivator.REFRESH_TASK_NAME);
		TaskDefinition adherence = schedulerService.getTaskByName(ActCoreActivator.ADHERENCE_TASK_NAME);
		try {
			Context.flushSession();
			assertEquals(PatientFlagRefreshTask.class.getName(), storedClass(flags));
			assertEquals(AdherenceRefreshTask.class.getName(), storedClass(adherence));
			// Core marks a task started only once it has made an instance from the class the task holds.
			assertTrue(flags.getStarted() && flags.getTaskInstance() != null);
			assertTrue(adherence.getStarted() && adherence.getTaskInstance() != null);
		}
		finally {
			schedulerService.shutdownTask(flags);
			schedulerService.shutdownTask(adherence);
		}
	}
	
	@Test
	public void registersTheDailyAdherenceRefresh() {
		new ActCoreActivator().started();
		
		TaskDefinition task = Context.getSchedulerService().getTaskByName(ActCoreActivator.ADHERENCE_TASK_NAME);
		assertEquals(AdherenceRefreshTask.class.getName(), task.getTaskClass());
		assertEquals(Long.valueOf(86400L), task.getRepeatInterval());
		assertTrue(task.getStartOnStartup());
	}
	
	@Test
	public void fillsTheFlagCacheBeforeEndingTheEnrolmentsOfTheDead() throws Exception {
		// The daemon thread sees only what is committed, so it commits what the start will find.
		asDaemon(() -> {
			property(ActCoreConstants.GP_REGISTRY_PROGRAM, HIV_PROGRAM);
			property(ActCoreConstants.GP_REGISTRY_DEATH_STATE_CONCEPT, TERMINAL_STATE_CONCEPT);
			AdministrationService admin = Context.getAdministrationService();
			admin.executeSQL("update person set dead = true, death_date = '2025-06-01' where person_id = 7", false);
			admin.executeSQL("insert into patient_program (patient_id, program_id, date_enrolled, creator, date_created,"
			        + " voided, uuid) values (7, 1, '2025-01-01', 1, '2025-01-01', false, '" + ENROLMENT + "')",
			    false);
		});
		FlagServiceImpl flags = coldFlagService();
		List<Object> filledAtEachSave = new ArrayList<Object>();
		AfterReturningAdvice recorder = (returned, method, args, target) -> {
			if ("savePatientProgram".equals(method.getName())) {
				filledAtEachSave.add(isFilled(flags));
			}
		};
		Context.addAdvice(ProgramWorkflowService.class, recorder);
		try {
			new ActCoreActivator().started();
		}
		finally {
			Context.removeAdvice(ProgramWorkflowService.class, recorder);
		}
		
		assertEquals(Collections.singletonList(true), filledAtEachSave);
	}
	
	@Test
	public void fillsTheFlagCacheOnARefreshByAUserWithoutManagePrivileges() throws Exception {
		FlagServiceImpl flags = coldFlagService();
		
		asAUserWithoutManagePrivileges(() -> new ActCoreActivator().contextRefreshed());
		
		assertTrue(isFilled(flags));
	}
	
	@Test
	public void fillsTheFlagCacheOnAStartByAUserWithoutManagePrivileges() throws Exception {
		FlagServiceImpl flags = coldFlagService();
		
		asAUserWithoutManagePrivileges(() -> new ActCoreActivator().started());
		
		assertTrue(isFilled(flags));
	}
	
	/**
	 * Runs the work as a user without Manage Privileges, with patientflags' user a superuser, so that
	 * filling the flag cache reads every privilege. Core 2.4.0, which the tests run on, lets anyone
	 * read every privilege, so an advice refuses it as core 2.8.10's Authorized annotation does.
	 */
	private void asAUserWithoutManagePrivileges(Runnable work) {
		asDaemon(() -> property(FLAGS_USERNAME, "admin"));
		MethodBeforeAdvice asCore2810 = (method, args, target) -> {
			if ("getAllPrivileges".equals(method.getName()) && !Context.hasPrivilege(PrivilegeConstants.MANAGE_PRIVILEGES)) {
				throw new APIAuthenticationException("Privilege required: " + PrivilegeConstants.MANAGE_PRIVILEGES);
			}
		};
		Context.addAdvice(UserService.class, asCore2810);
		try {
			Context.becomeUser("butch");
			assertFalse(Context.hasPrivilege(PrivilegeConstants.MANAGE_PRIVILEGES));
			work.run();
		}
		finally {
			Context.removeAdvice(UserService.class, asCore2810);
			Context.logout();
			authenticate();
		}
	}
	
	/** Marks patientflags' flag cache unfilled, as a context refresh leaves it. */
	private static FlagServiceImpl coldFlagService() throws Exception {
		Object flags = Context.getService(FlagService.class);
		while (flags instanceof Advised) {
			flags = ((Advised) flags).getTargetSource().getTarget();
		}
		initialized().set(flags, false);
		return (FlagServiceImpl) flags;
	}
	
	private static boolean isFilled(FlagServiceImpl flags) throws Exception {
		return (Boolean) initialized().get(flags);
	}
	
	private static Field initialized() throws Exception {
		Field initialized = FlagServiceImpl.class.getDeclaredField("isInitialized");
		initialized.setAccessible(true);
		return initialized;
	}
	
	/** Runs the work on the daemon thread, whose service calls commit, and rethrows what it throws. */
	private static void asDaemon(Runnable work) {
		RuntimeException[] thrown = new RuntimeException[1];
		Daemon.runInDaemonThreadAndWait(() -> {
			try {
				work.run();
			}
			catch (RuntimeException e) {
				thrown[0] = e;
			}
		}, ActCoreActivator.getDaemonToken());
		if (thrown[0] != null) {
			throw thrown[0];
		}
	}
	
	private static void property(String name, String value) {
		Context.getAdministrationService().saveGlobalProperty(new GlobalProperty(name, value));
	}
	
	/**
	 * Saves a task as a database that ran an earlier version holds it, under a class that no longer
	 * loads.
	 */
	private static void savedUnder(String name, String taskClass) {
		TaskDefinition task = new TaskDefinition();
		task.setName(name);
		task.setTaskClass(AdherenceRefreshTask.class.getName());
		task.setRepeatInterval(3600L);
		task.setStartTime(new Date(1500000000000L));
		task.setStartOnStartup(Boolean.TRUE);
		task.setStarted(Boolean.FALSE);
		Context.getSchedulerService().saveTaskDefinition(task);
		// Core will not save a class it cannot load.
		Context.flushSession();
		Context.getAdministrationService().executeSQL("update scheduler_task_config set schedulable_class = '" + taskClass
		        + "' where task_config_id = " + task.getId(),
		    false);
		Context.clearSession();
	}
	
	private static Object storedClass(TaskDefinition task) {
		return Context.getAdministrationService()
		        .executeSQL("select schedulable_class from scheduler_task_config where task_config_id = " + task.getId(),
		            true)
		        .get(0).get(0);
	}
}
