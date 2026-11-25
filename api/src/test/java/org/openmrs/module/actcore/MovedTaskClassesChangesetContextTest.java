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

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import liquibase.Contexts;
import org.junit.Before;
import org.junit.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.tasks.AdherenceRefreshTask;
import org.openmrs.module.actcore.tasks.PatientFlagRefreshTask;
import org.openmrs.module.actcore.utils.Transactions;
import org.openmrs.scheduler.TaskDefinition;
import org.openmrs.scheduler.tasks.HelloWorldTask;
import org.openmrs.test.BaseModuleContextSensitiveTest;
import org.openmrs.util.DatabaseUpdater;

public class MovedTaskClassesChangesetContextTest extends BaseModuleContextSensitiveTest {
	
	private static final String CHANGESET = "actcore-2026-11-25-moved-task-classes";
	
	private final List<Integer> saved = new ArrayList<Integer>();
	
	@Before
	public void createModuleTables() {
		ModuleTables.create();
	}
	
	@Test
	public void movesTasksSavedUnderAnOldClassAndLeavesOtherTasksAlone() throws Exception {
		try {
			int flags = savedUnder("org.openmrs.module.actcore.task.PatientFlagRefreshTask");
			int adherence = savedUnder("org.openmrs.module.actcore.adherence.AdherenceRefreshTask");
			int rhdFlags = savedUnder("org.openmrs.module.rhdflags.task.PatientFlagRefreshTask");
			int rhdAdherence = savedUnder("org.openmrs.module.rhdflags.adherence.AdherenceRefreshTask");
			int other = savedUnder(HelloWorldTask.class.getName());
			
			// Liquibase skips a changeset it has recorded, and ModuleTables already ran this one.
			Transactions.inNewTransaction(() -> Context.getAdministrationService()
			        .executeSQL("delete from liquibasechangelog where id = '" + CHANGESET + "'", false));
			DatabaseUpdater.executeChangelog("liquibase.xml", new Contexts(), null, getClass().getClassLoader());
			
			assertEquals(PatientFlagRefreshTask.class.getName(), storedClass(flags));
			assertEquals(AdherenceRefreshTask.class.getName(), storedClass(adherence));
			assertEquals(PatientFlagRefreshTask.class.getName(), storedClass(rhdFlags));
			assertEquals(AdherenceRefreshTask.class.getName(), storedClass(rhdAdherence));
			assertEquals(HelloWorldTask.class.getName(), storedClass(other));
		}
		finally {
			Transactions.inNewTransaction(() -> {
				for (Integer id : saved) {
					Context.getAdministrationService()
					        .executeSQL("delete from scheduler_task_config where task_config_id = " + id, false);
				}
			});
		}
	}
	
	/**
	 * Saves and commits a task as a database that ran an earlier version holds it, under a class that
	 * may no longer load.
	 */
	private int savedUnder(String taskClass) {
		TaskDefinition task = new TaskDefinition();
		Transactions.inNewTransaction(() -> {
			task.setName("Moved task classes test " + saved.size());
			task.setTaskClass(HelloWorldTask.class.getName());
			task.setRepeatInterval(3600L);
			task.setStartTime(new Date(1500000000000L));
			task.setStartOnStartup(Boolean.TRUE);
			task.setStarted(Boolean.FALSE);
			Context.getSchedulerService().saveTaskDefinition(task);
			// Core will not save a class it cannot load.
			Context.flushSession();
			Context.getAdministrationService().executeSQL("update scheduler_task_config set schedulable_class = '"
			        + taskClass + "' where task_config_id = " + task.getId(),
			    false);
		});
		saved.add(task.getId());
		return task.getId();
	}
	
	private static Object storedClass(int taskConfigId) {
		return Context.getAdministrationService()
		        .executeSQL("select schedulable_class from scheduler_task_config where task_config_id = " + taskConfigId,
		            true)
		        .get(0).get(0);
	}
}
