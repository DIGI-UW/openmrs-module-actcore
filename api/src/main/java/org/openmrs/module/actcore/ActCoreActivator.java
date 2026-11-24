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

import java.util.Date;

import org.openmrs.api.context.Context;
import org.openmrs.api.context.Daemon;
import org.openmrs.module.BaseModuleActivator;
import org.openmrs.module.DaemonToken;
import org.openmrs.module.DaemonTokenAware;
import org.openmrs.module.actcore.adherence.AdherenceRefreshTask;
import org.openmrs.module.actcore.aop.CloseEnrolmentsOnDeath;
import org.openmrs.module.actcore.reports.ReportDescriptorLoader;
import org.openmrs.module.actcore.task.PatientFlagRefreshTask;
import org.openmrs.scheduler.SchedulerService;
import org.openmrs.scheduler.TaskDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The module's activator. Each time the module starts, it loads the report descriptors, ends the
 * open enrolments of patients already recorded dead, and makes sure the scheduler holds the patient
 * flag refresh task and the adherence refresh task, registering each one the first time it is
 * missing.
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
	 * Gets the daemon token the module framework gave this module. The refresh endpoint uses it to run
	 * the patient flag refresh as the scheduler's daemon user, and the run chart endpoint uses it to
	 * evaluate its report as that user.
	 *
	 * @return the module's daemon token, or null before the module framework has set it
	 */
	public static DaemonToken getDaemonToken() {
		return daemonToken;
	}
	
	private static Date soon() {
		return new Date(System.currentTimeMillis() + INITIAL_DELAY_MILLIS);
	}
	
	/** @see org.openmrs.module.BaseModuleActivator#started() */
	@Override
	public void started() {
		ReportDescriptorLoader.load();
		closeEnrolmentsOfTheDead();
		schedule(REFRESH_TASK_NAME, PatientFlagRefreshTask.class.getName(),
		    "Re-evaluates every enabled, unretired patient flag, then mirrors each flag into a patient list"
		            + " of the same name.",
		    soon());
		schedule(ADHERENCE_TASK_NAME, AdherenceRefreshTask.class.getName(),
		    "Recomputes each patient's prophylaxis adherence and next due date from their prescriptions,"
		            + " injections and oral adherence estimates.",
		    soon());
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
	
	private void schedule(String name, String taskClass, String description, Date firstRun) {
		try {
			SchedulerService schedulerService = Context.getSchedulerService();
			TaskDefinition existing = schedulerService.getTaskByName(name);
			if (existing != null) {
				// The description and the class: the interval and start time are the administrator's to change.
				boolean moved = !taskClass.equals(existing.getTaskClass());
				if (moved || !description.equals(existing.getDescription())) {
					existing.setDescription(description);
					existing.setTaskClass(taskClass);
					schedulerService.saveTaskDefinition(existing);
				}
				// A task saved under a class that has since moved (rhdflags' before the rename) could not load.
				if (moved) {
					log.warn("Moved '{}' to {}", name, taskClass);
					// Its started flag is stale, as it never ran; at boot core schedules it after this anyway.
					if (Boolean.TRUE.equals(existing.getStarted()) || Boolean.TRUE.equals(existing.getStartOnStartup())) {
						schedulerService.rescheduleTask(existing);
					}
				}
				return;
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
		}
		catch (Exception e) {
			// A module that cannot schedule itself must still start, or it takes the whole
			// distribution down with it on a machine where the scheduler is unavailable.
			log.error("Could not schedule '{}'", name, e);
		}
	}
	
	/** @see org.openmrs.module.BaseModuleActivator#stopped() */
	@Override
	public void stopped() {
		log.info("ACT Core module stopped");
	}
}
