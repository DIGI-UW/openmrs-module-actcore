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
import org.openmrs.module.BaseModuleActivator;
import org.openmrs.module.actcore.adherence.AdherenceRefreshTask;
import org.openmrs.module.actcore.reports.ReportDescriptorLoader;
import org.openmrs.module.actcore.task.PatientFlagRefreshTask;
import org.openmrs.scheduler.SchedulerService;
import org.openmrs.scheduler.TaskDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads the report descriptors, then registers the flag and adherence refreshes with the scheduler
 * on first start. Initializer has no domain for scheduler_task_config, and the patientflags module
 * registers nothing of its own, so without this the task would have to be created by hand in the
 * admin UI on every environment.
 */
public class ActCoreActivator extends BaseModuleActivator {
	
	public static final String REFRESH_TASK_NAME = "RHD Patient Flag Refresh";
	
	public static final String ADHERENCE_TASK_NAME = "RHD Prophylaxis Adherence Refresh";
	
	/**
	 * The scheduler owns the interval once the task exists, so this is only the value the task is first
	 * registered with. Change it afterwards in Manage Scheduler.
	 */
	private static final long REFRESH_INTERVAL_SECONDS = 86400L;
	
	/**
	 * Keeps the first run minutes after installation rather than a day after it. The scheduler derives
	 * the first execution from startTime + repeatInterval once startTime is past, so a task registered
	 * at the current instant leaves a fresh install with no flag lists until tomorrow.
	 */
	private static final long INITIAL_DELAY_MILLIS = 300000L;
	
	private static final Logger log = LoggerFactory.getLogger(ActCoreActivator.class);
	
	@Override
	public void started() {
		ReportDescriptorLoader.load();
		schedule(REFRESH_TASK_NAME, PatientFlagRefreshTask.class.getName(),
		    "Re-evaluates every enabled, unretired patient flag, then mirrors each flag into a patient list"
		            + " of the same name.");
		schedule(ADHERENCE_TASK_NAME, AdherenceRefreshTask.class.getName(),
		    "Recomputes each patient's prophylaxis adherence and next due date from their prescriptions,"
		            + " injections and oral adherence estimates.");
		log.info("ACT Core module started");
	}
	
	private void schedule(String name, String taskClass, String description) {
		try {
			SchedulerService schedulerService = Context.getSchedulerService();
			TaskDefinition existing = schedulerService.getTaskByName(name);
			if (existing != null) {
				// Only the description: the interval and start time are the administrator's to change.
				if (!description.equals(existing.getDescription())) {
					existing.setDescription(description);
					schedulerService.saveTaskDefinition(existing);
				}
				return;
			}
			
			TaskDefinition task = new TaskDefinition();
			task.setName(name);
			task.setDescription(description);
			task.setTaskClass(taskClass);
			task.setStartTime(new Date(System.currentTimeMillis() + INITIAL_DELAY_MILLIS));
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
	
	@Override
	public void stopped() {
		log.info("ACT Core module stopped");
	}
}
