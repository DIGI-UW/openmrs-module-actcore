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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.openmrs.GlobalProperty;
import org.openmrs.Person;
import org.openmrs.PersonName;
import org.openmrs.Privilege;
import org.openmrs.Role;
import org.openmrs.User;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.AdministrationService;
import org.openmrs.api.UserService;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.tasks.AdherenceRefreshTask;
import org.openmrs.module.actcore.tasks.PatientFlagRefreshTask;
import org.openmrs.module.actcore.utils.ReportDescriptors;
import org.openmrs.module.reporting.ReportingConstants;
import org.openmrs.module.reporting.report.definition.service.ReportDefinitionService;
import org.openmrs.test.BaseModuleContextSensitiveTest;
import org.openmrs.scheduler.SchedulerService;
import org.openmrs.scheduler.TaskDefinition;
import org.openmrs.scheduler.timer.TimerSchedulerServiceImpl;
import org.openmrs.util.PrivilegeConstants;
import org.springframework.aop.MethodBeforeAdvice;

public class ActCoreActivatorContextTest extends BaseModuleContextSensitiveTest {
	
	@Rule
	public TemporaryFolder dataDirectory = new TemporaryFolder();
	
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
	public void stopped_shouldKeepTheTasksOutOfTheSchedulerStartupThatARuntimeStopRefreshRuns() {
		TaskDefinition flags = registered(ActCoreActivator.REFRESH_TASK_NAME, PatientFlagRefreshTask.class, true);
		TaskDefinition adherence = registered(ActCoreActivator.ADHERENCE_TASK_NAME, AdherenceRefreshTask.class, true);
		stoppedAtRuntime();
		
		new ActCoreActivator().stopped();
		// The refresh after a stop runs without the module, so its classes no longer load.
		Context.flushSession();
		Context.getAdministrationService().executeSQL(
		    "update scheduler_task_config set schedulable_class = 'org.openmrs.module.actcore.tasks.Unloaded'"
		            + " where task_config_id in (" + flags.getId() + ", " + adherence.getId() + ")",
		    false);
		Context.clearSession();
		
		List<String> errors = loggedWhile(TimerSchedulerServiceImpl.class, Level.ERROR,
		    () -> Context.getSchedulerService().onStartup());
		
		assertEquals(new ArrayList<String>(), errors);
	}
	
	@Test
	public void stopped_shouldLeaveTheTasksOnStartupWhenOpenmrsShutsDown() {
		registered(ActCoreActivator.REFRESH_TASK_NAME, PatientFlagRefreshTask.class, true);
		Context.getAdministrationService().setGlobalProperty(ActCoreConstants.MODULE_STARTED_PROPERTY, "true");
		
		new ActCoreActivator().stopped();
		
		assertTrue(Context.getSchedulerService().getTaskByName(ActCoreActivator.REFRESH_TASK_NAME).getStartOnStartup());
		assertEquals("",
		    Context.getAdministrationService().getGlobalProperty(ActCoreConstants.GP_TASKS_OFF_WHILE_STOPPED, ""));
	}
	
	@Test
	public void started_shouldPutBackOnStartupOnlyTheTasksAStopTookOff() throws Exception {
		SchedulerService schedulerService = Context.getSchedulerService();
		registered(ActCoreActivator.REFRESH_TASK_NAME, PatientFlagRefreshTask.class, true);
		registered(ActCoreActivator.ADHERENCE_TASK_NAME, AdherenceRefreshTask.class, false);
		stoppedAtRuntime();
		
		new ActCoreActivator().stopped();
		assertFalse(schedulerService.getTaskByName(ActCoreActivator.REFRESH_TASK_NAME).getStartOnStartup());
		List<String> logged = loggedWhile(ActCoreActivator.class, Level.INFO, () -> new ActCoreActivator().started());
		
		TaskDefinition flags = schedulerService.getTaskByName(ActCoreActivator.REFRESH_TASK_NAME);
		TaskDefinition adherence = schedulerService.getTaskByName(ActCoreActivator.ADHERENCE_TASK_NAME);
		try {
			assertTrue(flags.getStartOnStartup());
			assertTrue(flags.getStarted() && flags.getTaskInstance() != null);
			assertFalse(adherence.getStartOnStartup());
			assertFalse(adherence.getStarted());
			assertEquals("",
			    Context.getAdministrationService().getGlobalProperty(ActCoreConstants.GP_TASKS_OFF_WHILE_STOPPED, ""));
			List<String> putBack = new ArrayList<String>();
			for (String message : logged) {
				if (message.startsWith("Put ")) {
					putBack.add(message);
				}
			}
			assertEquals(Arrays.asList("Put '" + ActCoreActivator.REFRESH_TASK_NAME + "' back on the scheduler's startup"),
			    putBack);
		}
		finally {
			schedulerService.shutdownTask(flags);
		}
	}
	
	@Test
	public void contextRefreshed_shouldPutBackTheTasksAStopTookOffWhenTheStartSkipsStarted() throws Exception {
		SchedulerService schedulerService = Context.getSchedulerService();
		registered(ActCoreActivator.REFRESH_TASK_NAME, PatientFlagRefreshTask.class, true);
		registered(ActCoreActivator.ADHERENCE_TASK_NAME, AdherenceRefreshTask.class, true);
		stoppedAtRuntime();
		new ActCoreActivator().stopped();
		Context.getAdministrationService().setGlobalProperty(ActCoreConstants.MODULE_STARTED_PROPERTY, "true");
		
		// A start through REST refreshes the context without naming the module, so core skips started().
		ActCoreActivator activator = new ActCoreActivator();
		activator.willRefreshContext();
		activator.contextRefreshed();
		
		TaskDefinition flags = schedulerService.getTaskByName(ActCoreActivator.REFRESH_TASK_NAME);
		TaskDefinition adherence = schedulerService.getTaskByName(ActCoreActivator.ADHERENCE_TASK_NAME);
		try {
			assertTrue(flags.getStartOnStartup());
			assertTrue(flags.getStarted() && flags.getTaskInstance() != null);
			assertTrue(adherence.getStartOnStartup());
			assertTrue(adherence.getStarted() && adherence.getTaskInstance() != null);
			assertEquals("",
			    Context.getAdministrationService().getGlobalProperty(ActCoreConstants.GP_TASKS_OFF_WHILE_STOPPED, ""));
		}
		finally {
			schedulerService.shutdownTask(flags);
			schedulerService.shutdownTask(adherence);
		}
	}
	
	@Test
	public void contextRefreshed_shouldPutTheTasksBackOnceWhenStartedFollowsIt() throws Exception {
		SchedulerService schedulerService = Context.getSchedulerService();
		registered(ActCoreActivator.REFRESH_TASK_NAME, PatientFlagRefreshTask.class, true);
		registered(ActCoreActivator.ADHERENCE_TASK_NAME, AdherenceRefreshTask.class, true);
		stoppedAtRuntime();
		new ActCoreActivator().stopped();
		Context.getAdministrationService().setGlobalProperty(ActCoreConstants.MODULE_STARTED_PROPERTY, "true");
		
		// Boot and a start from Manage Modules run started() right after contextRefreshed().
		ActCoreActivator activator = new ActCoreActivator();
		List<String> logged = loggedWhile(ActCoreActivator.class, Level.INFO, () -> {
			activator.contextRefreshed();
			activator.started();
		});
		
		try {
			List<String> putBack = new ArrayList<String>();
			for (String message : logged) {
				if (message.startsWith("Put ")) {
					putBack.add(message);
				}
			}
			assertEquals(Arrays.asList("Put '" + ActCoreActivator.REFRESH_TASK_NAME + "' back on the scheduler's startup",
			    "Put '" + ActCoreActivator.ADHERENCE_TASK_NAME + "' back on the scheduler's startup"), putBack);
		}
		finally {
			schedulerService.shutdownTask(schedulerService.getTaskByName(ActCoreActivator.REFRESH_TASK_NAME));
			schedulerService.shutdownTask(schedulerService.getTaskByName(ActCoreActivator.ADHERENCE_TASK_NAME));
		}
	}
	
	@Test
	public void contextRefreshed_shouldDoTheStartUpWorkWhenTheStartSkipsStarted() throws Exception {
		SchedulerService schedulerService = Context.getSchedulerService();
		writeReportDescriptor();
		registered(ActCoreActivator.REFRESH_TASK_NAME, PatientFlagRefreshTask.class, false);
		stoppedAtRuntime();
		new ActCoreActivator().stopped();
		Context.getAdministrationService().setGlobalProperty(ActCoreConstants.MODULE_STARTED_PROPERTY, "true");
		
		// A start through REST refreshes the context without naming the module, so core skips started().
		ActCoreActivator activator = new ActCoreActivator();
		List<String> logged = loggedWhile(ActCoreActivator.class, Level.INFO, () -> {
			activator.willRefreshContext();
			activator.contextRefreshed();
		});
		
		TaskDefinition adherence = schedulerService.getTaskByName(ActCoreActivator.ADHERENCE_TASK_NAME);
		try {
			assertNotNull(Context.getService(ReportDefinitionService.class).getDefinitionByUuid(ReportDescriptors.UUID));
			assertTrue(adherence.getStartOnStartup());
			// A context test has no daemon token, so the attempt shows as its logged failure.
			assertTrue(logged.toString(),
			    logged.contains("Could not end the open enrolments of patients already recorded dead"));
			assertEquals(logged.toString(), "ACT Core module started", logged.get(logged.size() - 1));
		}
		finally {
			schedulerService.shutdownTask(adherence);
		}
	}
	
	@Test
	public void started_shouldNotRedoTheStartUpWorkContextRefreshedDidForTheSameStart() throws Exception {
		SchedulerService schedulerService = Context.getSchedulerService();
		
		// Boot and a start from Manage Modules run started() right after contextRefreshed().
		ActCoreActivator activator = new ActCoreActivator();
		List<String> logged = loggedWhile(ActCoreActivator.class, Level.INFO, () -> {
			activator.contextRefreshed();
			activator.started();
		});
		
		try {
			assertEquals(logged.toString(), 1, Collections.frequency(logged, "ACT Core module started"));
		}
		finally {
			schedulerService.shutdownTask(schedulerService.getTaskByName(ActCoreActivator.REFRESH_TASK_NAME));
			schedulerService.shutdownTask(schedulerService.getTaskByName(ActCoreActivator.ADHERENCE_TASK_NAME));
		}
	}
	
	@Test
	public void contextRefreshed_shouldLeaveTheTasksAsTheyAreWhenNoStopTookThemOff() throws Exception {
		SchedulerService schedulerService = Context.getSchedulerService();
		ActCoreActivator activator = new ActCoreActivator();
		activator.started();
		// An administrator then turns one task off and deletes the other.
		TaskDefinition flags = schedulerService.getTaskByName(ActCoreActivator.REFRESH_TASK_NAME);
		schedulerService.shutdownTask(flags);
		flags.setStartOnStartup(Boolean.FALSE);
		schedulerService.saveTaskDefinition(flags);
		TaskDefinition adherence = schedulerService.getTaskByName(ActCoreActivator.ADHERENCE_TASK_NAME);
		schedulerService.shutdownTask(adherence);
		schedulerService.deleteTask(adherence.getId());
		
		// Another module's start or stop refreshes the context while ACT Core keeps running.
		List<String> logged = loggedWhile(ActCoreActivator.class, Level.INFO, () -> {
			activator.willRefreshContext();
			activator.contextRefreshed();
		});
		
		flags = schedulerService.getTaskByName(ActCoreActivator.REFRESH_TASK_NAME);
		assertFalse(flags.getStartOnStartup());
		assertFalse(flags.getStarted());
		assertNull(schedulerService.getTaskByName(ActCoreActivator.ADHERENCE_TASK_NAME));
		assertEquals(new ArrayList<String>(), logged);
	}
	
	@Test
	public void started_shouldPutBackATaskTakenOffByAStopBeforeAnEarlierStartFailed() throws Exception {
		SchedulerService schedulerService = Context.getSchedulerService();
		registered(ActCoreActivator.REFRESH_TASK_NAME, PatientFlagRefreshTask.class, true);
		TaskDefinition adherence = registered(ActCoreActivator.ADHERENCE_TASK_NAME, AdherenceRefreshTask.class, false);
		stoppedAtRuntime();
		
		new ActCoreActivator().stopped();
		// While stopped an administrator puts the other task on startup, and a start fails before started().
		adherence.setStartOnStartup(Boolean.TRUE);
		schedulerService.saveTaskDefinition(adherence);
		new ActCoreActivator().stopped();
		new ActCoreActivator().started();
		
		TaskDefinition flags = schedulerService.getTaskByName(ActCoreActivator.REFRESH_TASK_NAME);
		adherence = schedulerService.getTaskByName(ActCoreActivator.ADHERENCE_TASK_NAME);
		try {
			assertTrue(flags.getStartOnStartup());
			assertTrue(flags.getStarted());
			assertTrue(adherence.getStartOnStartup());
			assertTrue(adherence.getStarted());
		}
		finally {
			schedulerService.shutdownTask(flags);
			schedulerService.shutdownTask(adherence);
		}
	}
	
	@Test
	public void started_shouldKeepInTheRecordOnlyTheTaskItCouldNotPutBack() throws Exception {
		SchedulerService schedulerService = Context.getSchedulerService();
		registered(ActCoreActivator.REFRESH_TASK_NAME, PatientFlagRefreshTask.class, true);
		registered(ActCoreActivator.ADHERENCE_TASK_NAME, AdherenceRefreshTask.class, true);
		stoppedAtRuntime();
		new ActCoreActivator().stopped();
		// Two tasks of one name make the scheduler's lookup by name throw.
		registered(ActCoreActivator.ADHERENCE_TASK_NAME, AdherenceRefreshTask.class, false);
		
		ActCoreActivator activator = new ActCoreActivator();
		activator.started();
		assertEquals(ActCoreActivator.ADHERENCE_TASK_NAME,
		    Context.getAdministrationService().getGlobalProperty(ActCoreConstants.GP_TASKS_OFF_WHILE_STOPPED));
		// An administrator then turns off the task that was put back.
		TaskDefinition flags = schedulerService.getTaskByName(ActCoreActivator.REFRESH_TASK_NAME);
		schedulerService.shutdownTask(flags);
		flags.setStartOnStartup(Boolean.FALSE);
		schedulerService.saveTaskDefinition(flags);
		
		// Another module's start or stop refreshes the context, retrying what the record holds.
		activator.willRefreshContext();
		activator.contextRefreshed();
		
		flags = schedulerService.getTaskByName(ActCoreActivator.REFRESH_TASK_NAME);
		try {
			assertFalse(flags.getStartOnStartup());
			assertFalse(flags.getStarted());
		}
		finally {
			schedulerService.shutdownTask(flags);
		}
	}
	
	@Test
	public void contextRefreshed_shouldPutBackATaskAnEarlierStartCouldNot() throws Exception {
		SchedulerService schedulerService = Context.getSchedulerService();
		registered(ActCoreActivator.REFRESH_TASK_NAME, PatientFlagRefreshTask.class, true);
		registered(ActCoreActivator.ADHERENCE_TASK_NAME, AdherenceRefreshTask.class, true);
		stoppedAtRuntime();
		new ActCoreActivator().stopped();
		// Two tasks of one name make the scheduler's lookup by name throw.
		TaskDefinition duplicate = registered(ActCoreActivator.ADHERENCE_TASK_NAME, AdherenceRefreshTask.class, false);
		ActCoreActivator activator = new ActCoreActivator();
		activator.started();
		assertEquals(ActCoreActivator.ADHERENCE_TASK_NAME,
		    Context.getAdministrationService().getGlobalProperty(ActCoreConstants.GP_TASKS_OFF_WHILE_STOPPED));
		schedulerService.deleteTask(duplicate.getId());
		
		// Another module's start or stop refreshes the context, retrying what the record holds.
		activator.willRefreshContext();
		activator.contextRefreshed();
		
		TaskDefinition flags = schedulerService.getTaskByName(ActCoreActivator.REFRESH_TASK_NAME);
		TaskDefinition adherence = schedulerService.getTaskByName(ActCoreActivator.ADHERENCE_TASK_NAME);
		try {
			assertTrue(adherence.getStartOnStartup());
			assertEquals("",
			    Context.getAdministrationService().getGlobalProperty(ActCoreConstants.GP_TASKS_OFF_WHILE_STOPPED, ""));
		}
		finally {
			schedulerService.shutdownTask(flags);
			schedulerService.shutdownTask(adherence);
		}
	}
	
	@Test
	public void started_shouldKeepInTheRecordOnlyTheFirstTaskWhenItCouldNotBePutBack() throws Exception {
		SchedulerService schedulerService = Context.getSchedulerService();
		registered(ActCoreActivator.REFRESH_TASK_NAME, PatientFlagRefreshTask.class, true);
		registered(ActCoreActivator.ADHERENCE_TASK_NAME, AdherenceRefreshTask.class, true);
		stoppedAtRuntime();
		new ActCoreActivator().stopped();
		// Two tasks of one name make the scheduler's lookup by name throw.
		registered(ActCoreActivator.REFRESH_TASK_NAME, PatientFlagRefreshTask.class, false);
		
		new ActCoreActivator().started();
		
		TaskDefinition adherence = schedulerService.getTaskByName(ActCoreActivator.ADHERENCE_TASK_NAME);
		try {
			assertTrue(adherence.getStartOnStartup());
			assertEquals(ActCoreActivator.REFRESH_TASK_NAME,
			    Context.getAdministrationService().getGlobalProperty(ActCoreConstants.GP_TASKS_OFF_WHILE_STOPPED));
		}
		finally {
			schedulerService.shutdownTask(adherence);
		}
	}
	
	@Test
	public void started_shouldPutBackTheTasksWhenAUserWhoCanOnlyManageModulesStopsAndStartsTheModule() throws Exception {
		SchedulerService schedulerService = Context.getSchedulerService();
		registered(ActCoreActivator.REFRESH_TASK_NAME, PatientFlagRefreshTask.class, true);
		stoppedAtRuntime();
		User moduleManager = userWithOnly(PrivilegeConstants.MANAGE_MODULES);
		MethodBeforeAdvice globalPropertyChecks = new GlobalPropertyChecksOfLaterCore();
		Context.addAdvice(AdministrationService.class, globalPropertyChecks);
		try {
			Context.becomeUser(moduleManager.getSystemId());
			new ActCoreActivator().stopped();
			new ActCoreActivator().started();
			assertFalse(Context.hasPrivilege(PrivilegeConstants.MANAGE_SCHEDULER));
			assertFalse(Context.hasPrivilege(PrivilegeConstants.GET_GLOBAL_PROPERTIES));
			assertFalse(Context.hasPrivilege(PrivilegeConstants.MANAGE_GLOBAL_PROPERTIES));
		}
		finally {
			Context.removeAdvice(AdministrationService.class, globalPropertyChecks);
			Context.logout();
			authenticate();
		}
		
		TaskDefinition flags = schedulerService.getTaskByName(ActCoreActivator.REFRESH_TASK_NAME);
		try {
			assertTrue(flags.getStartOnStartup());
			assertTrue(flags.getStarted() && flags.getTaskInstance() != null);
			assertEquals("",
			    Context.getAdministrationService().getGlobalProperty(ActCoreConstants.GP_TASKS_OFF_WHILE_STOPPED, ""));
		}
		finally {
			schedulerService.shutdownTask(flags);
		}
	}
	
	@Test
	public void contextRefreshed_shouldLoadTheReportDescriptorsWhenAUserWhoCanOnlyManageModulesStartsTheModule()
	        throws Exception {
		writeReportDescriptor();
		User moduleManager = userWithOnly(PrivilegeConstants.MANAGE_MODULES);
		MethodBeforeAdvice globalPropertyChecks = new GlobalPropertyChecksOfLaterCore();
		Context.addAdvice(AdministrationService.class, globalPropertyChecks);
		try {
			Context.becomeUser(moduleManager.getSystemId());
			ActCoreActivator activator = new ActCoreActivator();
			activator.willRefreshContext();
			activator.contextRefreshed();
			assertFalse(Context.hasPrivilege(PrivilegeConstants.GET_GLOBAL_PROPERTIES));
		}
		finally {
			Context.removeAdvice(AdministrationService.class, globalPropertyChecks);
			Context.logout();
			authenticate();
		}
		
		SchedulerService schedulerService = Context.getSchedulerService();
		try {
			assertNotNull(Context.getService(ReportDefinitionService.class).getDefinitionByUuid(ReportDescriptors.UUID));
		}
		finally {
			schedulerService.shutdownTask(schedulerService.getTaskByName(ActCoreActivator.REFRESH_TASK_NAME));
			schedulerService.shutdownTask(schedulerService.getTaskByName(ActCoreActivator.ADHERENCE_TASK_NAME));
		}
	}
	
	/**
	 * Puts one report descriptor in the configuration, and has reporting load descriptors at startup.
	 */
	private void writeReportDescriptor() throws Exception {
		ReportDescriptors.write(dataDirectory.getRoot());
		Context.getAdministrationService().saveGlobalProperty(
		    new GlobalProperty(ReportingConstants.GLOBAL_PROPERTY_LOAD_REPORTS_FROM_CONFIGURATION_AT_STARTUP, "true"));
	}
	
	/** Sets what core sets on a stop made while OpenMRS runs, before it calls the activator. */
	private static void stoppedAtRuntime() {
		Context.getAdministrationService().setGlobalProperty(ActCoreConstants.MODULE_STARTED_PROPERTY, "false");
	}
	
	private static User userWithOnly(String privilegeName) {
		UserService userService = Context.getUserService();
		Privilege privilege = userService.getPrivilege(privilegeName);
		if (privilege == null) {
			privilege = userService.savePrivilege(new Privilege(privilegeName, privilegeName));
		}
		Role role = new Role("Test Module Manager", "Can only manage modules");
		role.addPrivilege(privilege);
		userService.saveRole(role);
		Person person = new Person();
		person.addName(new PersonName("Module", null, "Manager"));
		person.setGender("F");
		User user = new User(person);
		user.setUsername("modulemanager");
		user.addRole(role);
		return userService.createUser(user, "Password123");
	}
	
	/**
	 * Refuses the global property reads and writes that core 2.8.10 authorizes and 2.4.0, which the
	 * tests run on, does not.
	 */
	private static class GlobalPropertyChecksOfLaterCore implements MethodBeforeAdvice {
		
		@Override
		public void before(Method method, Object[] args, Object target) {
			if ("getGlobalProperty".equals(method.getName())) {
				require(PrivilegeConstants.GET_GLOBAL_PROPERTIES);
			} else if ("setGlobalProperty".equals(method.getName())) {
				require(PrivilegeConstants.MANAGE_GLOBAL_PROPERTIES);
			}
		}
		
		private static void require(String privilege) {
			if (!Context.hasPrivilege(privilege)) {
				throw new APIAuthenticationException("Privileges required: " + privilege);
			}
		}
	}
	
	private static TaskDefinition registered(String name, Class<?> taskClass, boolean startOnStartup) {
		TaskDefinition task = new TaskDefinition();
		task.setName(name);
		task.setTaskClass(taskClass.getName());
		task.setRepeatInterval(3600L);
		task.setStartTime(new Date(1500000000000L));
		task.setStartOnStartup(startOnStartup);
		task.setStarted(Boolean.FALSE);
		Context.getSchedulerService().saveTaskDefinition(task);
		return task;
	}
	
	/**
	 * Runs the work and returns the messages the class's logger logged meanwhile at that level or a
	 * more severe one.
	 */
	private static List<String> loggedWhile(Class<?> source, Level threshold, Runnable work) {
		List<String> logged = new ArrayList<String>();
		AbstractAppender appender = new AbstractAppender("capture", null, null, true, Property.EMPTY_ARRAY) {
			
			@Override
			public void append(LogEvent event) {
				if (event.getLevel().isMoreSpecificThan(threshold)) {
					logged.add(event.getMessage().getFormattedMessage());
				}
			}
		};
		appender.start();
		Logger logger = (Logger) LogManager.getLogger(source);
		Level level = logger.getLevel();
		logger.addAppender(appender);
		// Kept off the console: a test captures what it expects to be logged.
		boolean additive = logger.isAdditive();
		logger.setAdditive(false);
		// The test log4j2.xml turns every logger off.
		logger.setLevel(threshold);
		try {
			work.run();
		}
		finally {
			logger.removeAppender(appender);
			logger.setLevel(level);
			logger.setAdditive(additive);
		}
		return logged;
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
