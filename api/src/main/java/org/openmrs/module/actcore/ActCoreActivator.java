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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;
import org.openmrs.api.APIException;
import org.openmrs.api.AdministrationService;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.Daemon;
import org.openmrs.module.BaseModuleActivator;
import org.openmrs.module.DaemonToken;
import org.openmrs.module.DaemonTokenAware;
import org.openmrs.module.actcore.aop.CloseEnrolmentsOnDeath;
import org.openmrs.module.actcore.tasks.AdherenceRefreshTask;
import org.openmrs.module.actcore.tasks.PatientFlagRefreshTask;
import org.openmrs.module.actcore.utils.ReportDescriptorLoader;
import org.openmrs.scheduler.SchedulerService;
import org.openmrs.scheduler.TaskDefinition;
import org.openmrs.util.PrivilegeConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The module's activator. Each time the module starts, it loads the report descriptors, ends the
 * open enrolments of patients already recorded dead, and makes sure the scheduler holds the patient
 * flag refresh task and the adherence refresh task, registering each one the first time it is
 * missing. When an administrator stops the module, or it fails to start, it takes those tasks off
 * the scheduler's startup until it starts again.
 * <p>
 * The tasks are registered here because Initializer has no domain for scheduler_task_config and the
 * patientflags module registers nothing of its own, so without this each task would have to be
 * created by hand in the admin UI on every environment.
 */
public class ActCoreActivator extends BaseModuleActivator implements DaemonTokenAware {
	
	/**
	 * The name the patient flag refresh task is registered under in the scheduler, and by which the
	 * activator finds it again on later starts.
	 */
	public static final String REFRESH_TASK_NAME = "RHD Patient Flag Refresh";
	
	/**
	 * The name the adherence refresh task is registered under in the scheduler, and by which the
	 * activator finds it again on later starts.
	 */
	public static final String ADHERENCE_TASK_NAME = "RHD Prophylaxis Adherence Refresh";
	
	private static final List<String> TASK_NAMES = Arrays.asList(REFRESH_TASK_NAME, ADHERENCE_TASK_NAME);
	
	private static final String[] STOP_PRIVILEGES = { PrivilegeConstants.MANAGE_SCHEDULER,
	        PrivilegeConstants.GET_GLOBAL_PROPERTIES, PrivilegeConstants.MANAGE_GLOBAL_PROPERTIES };
	
	/**
	 * The interval, in seconds, that each task is first registered with. The scheduler owns the
	 * interval once the task exists, so changing this value does not change a registered task; change
	 * it in Manage Scheduler instead.
	 */
	private static final long REFRESH_INTERVAL_SECONDS = 86400L;
	
	/**
	 * Keeps the first run minutes after installation rather than a day after it. The scheduler derives
	 * the first execution from startTime + repeatInterval once startTime is past, so a task registered
	 * at the current instant leaves a fresh install with no flag lists until tomorrow.
	 */
	private static final long INITIAL_DELAY_MILLIS = 300000L;
	
	private static final Logger log = LoggerFactory.getLogger(ActCoreActivator.class);
	
	private static volatile DaemonToken daemonToken;
	
	/** @see org.openmrs.module.DaemonTokenAware#setDaemonToken(DaemonToken) */
	@Override
	public void setDaemonToken(DaemonToken token) {
		daemonToken = token;
	}
	
	/**
	 * Gets the daemon token the module framework gave this module, which
	 * {@link #runAsDaemon(String, String, Supplier)} runs its work with.
	 *
	 * @return the module's daemon token, or null before the module framework has set it
	 */
	public static DaemonToken getDaemonToken() {
		return daemonToken;
	}
	
	/**
	 * Runs the work as the scheduler's daemon user, on a daemon thread, and waits for it. The refresh
	 * endpoint runs the patient flag refresh this way, and the run chart service evaluates its report
	 * this way, as both read and write more than the caller's own privileges allow.
	 *
	 * @param task what the work does, completing "ACT Core has not started, so it cannot", as "run the
	 *            refresh"
	 * @param failure the message of the exception thrown when the work throws, as "The patient flag
	 *            refresh failed"
	 * @param work the work
	 * @return what the work returns
	 * @throws APIException when the module has not started, so has no daemon token, or the work throws
	 *             a runtime exception, which becomes its cause
	 */
	public static <T> T runAsDaemon(String task, String failure, Supplier<T> work) {
		DaemonToken token = daemonToken;
		if (token == null) {
			throw new APIException("ACT Core has not started, so it cannot " + task);
		}
		List<T> result = new ArrayList<T>(1);
		RuntimeException[] thrown = new RuntimeException[1];
		Daemon.runInDaemonThreadAndWait(() -> {
			try {
				result.add(work.get());
			}
			catch (RuntimeException e) {
				thrown[0] = e;
			}
		}, token);
		if (thrown[0] != null) {
			throw new APIException(failure, thrown[0]);
		}
		return result.get(0);
	}
	
	private static Date soon() {
		return new Date(System.currentTimeMillis() + INITIAL_DELAY_MILLIS);
	}
	
	/** @see org.openmrs.module.BaseModuleActivator#started() */
	@Override
	public void started() {
		ReportDescriptorLoader.load();
		closeEnrolmentsOfTheDead();
		Set<String> takenOff = tasksTakenOff();
		boolean scheduled = schedule(REFRESH_TASK_NAME, PatientFlagRefreshTask.class.getName(),
		    "Re-evaluates every enabled, unretired patient flag, then mirrors each flag into a patient list"
		            + " of the same name.",
		    soon(), takenOff.contains(REFRESH_TASK_NAME));
		scheduled &= schedule(ADHERENCE_TASK_NAME, AdherenceRefreshTask.class.getName(),
		    "Recomputes each patient's prophylaxis adherence and next due date from their prescriptions,"
		            + " injections and oral adherence estimates.",
		    soon(), takenOff.contains(ADHERENCE_TASK_NAME));
		// Kept on a failure, so that a later start still puts the tasks back.
		if (scheduled && !takenOff.isEmpty()) {
			forgetTasksTakenOff();
		}
		log.info("ACT Core module started");
	}
	
	/**
	 * Ends the open enrolments of patients already recorded dead, running as the daemon user. A failure
	 * is logged rather than thrown, so that the module still starts.
	 */
	private static void closeEnrolmentsOfTheDead() {
		try {
			// The work runs on the daemon thread, so its failures are caught there.
			Daemon.runInDaemonThreadAndWait(() -> {
				try {
					int ended = CloseEnrolmentsOnDeath.closeEnrolmentsOfTheDead();
					if (ended > 0) {
						log.info("Ended {} open enrolments of patients already recorded dead", ended);
					}
				}
				catch (RuntimeException e) {
					log.error("Could not end the open enrolments of patients already recorded dead", e);
				}
			}, daemonToken);
		}
		catch (Exception e) {
			log.error("Could not end the open enrolments of patients already recorded dead", e);
		}
	}
	
	/**
	 * Registers the task the first time it is missing, and otherwise brings its description and class
	 * up to date and puts it back on startup when a stop took it off.
	 *
	 * @return false when it could not, which it logs
	 */
	private boolean schedule(String name, String taskClass, String description, Date firstRun, boolean takenOff) {
		try {
			SchedulerService schedulerService = Context.getSchedulerService();
			TaskDefinition existing = schedulerService.getTaskByName(name);
			if (existing != null) {
				// The description and the class: the interval and start time are the administrator's to change.
				boolean moved = !taskClass.equals(existing.getTaskClass());
				if (takenOff) {
					existing.setStartOnStartup(Boolean.TRUE);
				}
				if (moved || takenOff || !description.equals(existing.getDescription())) {
					existing.setDescription(description);
					existing.setTaskClass(taskClass);
					schedulerService.saveTaskDefinition(existing);
				}
				// A task saved under a class that has since moved (rhdflags' before the rename) could not load.
				if (moved) {
					log.warn("Moved '{}' to {}", name, taskClass);
				}
				// Core's scheduler started on the old class or without the task, so this call schedules it.
				if ((moved || takenOff) && (Boolean.TRUE.equals(existing.getStarted())
				        || Boolean.TRUE.equals(existing.getStartOnStartup()))) {
					schedulerService.rescheduleTask(existing);
				}
				return true;
			}
			
			TaskDefinition task = new TaskDefinition();
			task.setName(name);
			task.setDescription(description);
			task.setTaskClass(taskClass);
			task.setStartTime(firstRun);
			task.setRepeatInterval(REFRESH_INTERVAL_SECONDS);
			task.setStartOnStartup(Boolean.TRUE);
			task.setStarted(Boolean.TRUE);
			
			schedulerService.saveTaskDefinition(task);
			schedulerService.scheduleTask(task);
			log.info("Registered '{}' every {}s", name, task.getRepeatInterval());
			return true;
		}
		catch (Exception e) {
			// A module that cannot schedule itself must still start, or it takes the whole
			// distribution down with it on a machine where the scheduler is unavailable.
			log.error("Could not schedule '{}'", name, e);
			return false;
		}
	}
	
	/**
	 * Takes the module's tasks off the scheduler's startup when an administrator stops the module, or
	 * it fails to start. Core refreshes its context after such a stop, and its scheduler then schedules
	 * every task set to start on startup, which for these tasks logs a ClassNotFoundException since
	 * their classes went with the module. {@link #started()} puts back the tasks this took off. A
	 * shutdown of OpenMRS leaves the tasks as they are.
	 *
	 * @see org.openmrs.module.BaseModuleActivator#stopped()
	 */
	@Override
	public void stopped() {
		for (String privilege : STOP_PRIVILEGES) {
			Context.addProxyPrivilege(privilege);
		}
		try {
			takeTasksOffStartup();
		}
		catch (Exception e) {
			log.error("Could not take ACT Core's tasks off the scheduler's startup", e);
		}
		finally {
			for (String privilege : STOP_PRIVILEGES) {
				Context.removeProxyPrivilege(privilege);
			}
		}
		log.info("ACT Core module stopped");
	}
	
	private static void takeTasksOffStartup() {
		AdministrationService administrationService = Context.getAdministrationService();
		// Core sets this to false before calling stopped() on a stop or a failed start, and not on a shutdown.
		if (!"false".equals(administrationService.getGlobalProperty(ActCoreConstants.MODULE_STARTED_PROPERTY))) {
			return;
		}
		SchedulerService schedulerService = Context.getSchedulerService();
		Set<String> takenOff = tasksTakenOff();
		List<TaskDefinition> taking = new ArrayList<TaskDefinition>();
		for (String name : TASK_NAMES) {
			TaskDefinition task = schedulerService.getTaskByName(name);
			if (task != null && Boolean.TRUE.equals(task.getStartOnStartup())) {
				taking.add(task);
				takenOff.add(name);
			}
		}
		if (taking.isEmpty()) {
			return;
		}
		// Recorded before the tasks change, so that no failure leaves a task off that no start puts back.
		administrationService.setGlobalProperty(ActCoreConstants.GP_TASKS_OFF_WHILE_STOPPED,
		    StringUtils.join(takenOff, ","));
		for (TaskDefinition task : taking) {
			task.setStartOnStartup(Boolean.FALSE);
			schedulerService.saveTaskDefinition(task);
		}
		log.info("Took {} off the scheduler's startup until ACT Core starts again", takenOff);
	}
	
	private static Set<String> tasksTakenOff() {
		Set<String> names = new LinkedHashSet<String>();
		try {
			String value = Context.getAdministrationService().getGlobalProperty(ActCoreConstants.GP_TASKS_OFF_WHILE_STOPPED);
			for (String name : StringUtils.split(StringUtils.defaultString(value), ',')) {
				names.add(name.trim());
			}
		}
		catch (Exception e) {
			log.error("Could not read which tasks a stop took off the scheduler's startup", e);
		}
		return names;
	}
	
	private static void forgetTasksTakenOff() {
		Context.addProxyPrivilege(PrivilegeConstants.MANAGE_GLOBAL_PROPERTIES);
		try {
			Context.getAdministrationService().setGlobalProperty(ActCoreConstants.GP_TASKS_OFF_WHILE_STOPPED, "");
		}
		catch (Exception e) {
			log.error("Could not clear the record of the tasks a stop took off the scheduler's startup", e);
		}
		finally {
			Context.removeProxyPrivilege(PrivilegeConstants.MANAGE_GLOBAL_PROPERTIES);
		}
	}
}
