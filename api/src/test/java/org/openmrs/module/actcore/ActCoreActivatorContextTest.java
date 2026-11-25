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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.tasks.AdherenceRefreshTask;
import org.openmrs.module.actcore.tasks.PatientFlagRefreshTask;
import org.openmrs.test.BaseModuleContextSensitiveTest;
import org.openmrs.scheduler.SchedulerService;
import org.openmrs.scheduler.TaskDefinition;
import org.openmrs.scheduler.timer.TimerSchedulerServiceImpl;

public class ActCoreActivatorContextTest extends BaseModuleContextSensitiveTest {
	
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
	public void started_shouldKeepTheRecordOfTheTasksTakenOffWhenOneCouldNotBeScheduled() throws Exception {
		SchedulerService schedulerService = Context.getSchedulerService();
		registered(ActCoreActivator.REFRESH_TASK_NAME, PatientFlagRefreshTask.class, true);
		stoppedAtRuntime();
		new ActCoreActivator().stopped();
		// Two tasks of one name make the scheduler's lookup by name throw.
		registered(ActCoreActivator.ADHERENCE_TASK_NAME, AdherenceRefreshTask.class, false);
		registered(ActCoreActivator.ADHERENCE_TASK_NAME, AdherenceRefreshTask.class, false);
		
		new ActCoreActivator().started();
		
		try {
			assertEquals(ActCoreActivator.REFRESH_TASK_NAME,
			    Context.getAdministrationService().getGlobalProperty(ActCoreConstants.GP_TASKS_OFF_WHILE_STOPPED));
		}
		finally {
			schedulerService.shutdownTask(schedulerService.getTaskByName(ActCoreActivator.REFRESH_TASK_NAME));
		}
	}
	
	/** Sets what core sets on a stop made while OpenMRS runs, before it calls the activator. */
	private static void stoppedAtRuntime() {
		Context.getAdministrationService().setGlobalProperty(ActCoreConstants.MODULE_STARTED_PROPERTY, "false");
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
		// The test log4j2.xml turns every logger off.
		logger.setLevel(threshold);
		try {
			work.run();
		}
		finally {
			logger.removeAppender(appender);
			logger.setLevel(level);
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
