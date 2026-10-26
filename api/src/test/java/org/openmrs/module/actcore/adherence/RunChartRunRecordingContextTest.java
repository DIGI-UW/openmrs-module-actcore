/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.adherence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.commons.lang3.StringUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openmrs.GlobalProperty;
import org.openmrs.Location;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.Transactions;
import org.openmrs.module.actcore.runchart.RunChartRuns;
import org.openmrs.module.actcore.runchart.RunChartSnapshotTask;
import org.openmrs.module.actcore.runchart.RunCharts;
import org.openmrs.module.reporting.dataset.definition.SqlDataSetDefinition;
import org.openmrs.module.reporting.evaluation.parameter.Mapped;
import org.openmrs.module.reporting.evaluation.parameter.Parameter;
import org.openmrs.module.reporting.report.definition.ReportDefinition;
import org.openmrs.module.reporting.report.definition.service.ReportDefinitionService;

/**
 * Each run of the snapshot task as the log keeps it. A record commits in a transaction of its own,
 * so the rows outlive the test's and are removed after it.
 */
public class RunChartRunRecordingContextTest extends AdherenceContextTest {
	
	private static final String REPORT_UUID = "3a9e5d71-6c24-4b8f-a0d3-1e7f9b2c4a68";
	
	private final RunChartRuns runs = new RunChartRuns();
	
	private static void createTheRunTable() {
		Context.getAdministrationService().executeSQL(
		    "create table if not exists " + RunChartRuns.TABLE
		            + " (run_id int auto_increment primary key, run_date date not null, finished datetime not null,"
		            + " duration_millis bigint not null default 0, source varchar(16) not null, status varchar(16) not null,"
		            + " row_count int not null default 0, clinic_count int not null default 0, failure_reason varchar(500))",
		    false);
	}
	
	@Before
	public void createTheTablesAndAReportOfOneClinic() {
		StringBuilder columns = new StringBuilder();
		for (String column : RunCharts.COLUMNS) {
			columns.append(", ").append(column).append(" int not null default 0");
		}
		Context.getAdministrationService()
		        .executeSQL("create table if not exists " + RunCharts.TABLE
		                + " (record_id int auto_increment primary key, record_date date not null, location_id int" + columns
		                + ", date_created datetime not null, unique (record_date, location_id))",
		            false);
		createTheRunTable();
		saveReport("5");
		Context.getAdministrationService().saveGlobalProperty(new GlobalProperty(RunCharts.GP_REPORT, REPORT_UUID));
	}
	
	@After
	public void removeTheRunsThatCommitted() {
		createTheRunTable();
		Transactions.inNewTransaction(
		    () -> Context.getAdministrationService().executeSQL("delete from " + RunChartRuns.TABLE, false));
	}
	
	/** The row of every clinic and the row of a clinic, each with the given active registrants. */
	private void saveReport(String active) {
		Location clinic = Context.getLocationService().getLocation(1);
		String row = "%s as clinic_uuid, 10 as total_registrants, " + active + " as active_registrants,"
		        + " 0 as active_registrants_seen_last_7_months, 0 as active_registrants_prescribed_prophylaxis,"
		        + " 0 as active_registrants_adherent, 0 as consultation_visit_total,"
		        + " 0 as consultation_visits_with_echocardiogram_resulted, 0 as consultation_visits_with_intervention,"
		        + " 0 as interventions_completed";
		SqlDataSetDefinition sql = new SqlDataSetDefinition();
		sql.addParameter(new Parameter("endDate", "As of", java.util.Date.class));
		sql.setSqlQuery("select " + String.format(row, "'" + clinic.getUuid() + "'") + " union all select "
		        + String.format(row, "null"));
		ReportDefinitionService reports = Context.getService(ReportDefinitionService.class);
		ReportDefinition report = reports.getDefinitionByUuid(REPORT_UUID);
		if (report == null) {
			report = new ReportDefinition();
			report.setUuid(REPORT_UUID);
			report.setName("Run chart recording test report");
			report.addParameter(new Parameter("endDate", "As of", java.util.Date.class));
		} else {
			report.getDataSetDefinitions().clear();
		}
		report.addDataSetDefinition("metrics", Mapped.mapStraightThrough(sql));
		reports.saveDefinition(report);
	}
	
	@Test
	public void runIfIdle_shouldRecordATakenRunWithItsRowsClinicsSourceAndDay() {
		assertEquals(Integer.valueOf(2), new RunChartSnapshotTask().runIfIdle(RunChartRuns.MANUAL));
		
		RunChartRuns.Run run = runs.latest(7).get(0);
		assertTrue(run.taken);
		assertEquals(2, run.rows);
		assertEquals(1, run.clinics);
		assertEquals(RunChartRuns.MANUAL, run.source);
		assertEquals(LocalDate.now(), run.runDate);
		assertNull(run.reason);
		assertTrue(run.durationMillis >= 0);
	}
	
	@Test
	public void runIfIdle_shouldRecordAFailedRunWithItsReasonAndThrowTheFailureOn() {
		Context.getAdministrationService().saveGlobalProperty(new GlobalProperty(RunCharts.GP_REPORT, "no-such-report"));
		
		try {
			new RunChartSnapshotTask().runIfIdle(RunChartRuns.SCHEDULED);
			fail("expected the run to fail");
		}
		catch (APIException e) {
			assertTrue(e.getMessage().contains(RunCharts.GP_REPORT));
		}
		
		RunChartRuns.Run run = runs.latest(7).get(0);
		assertFalse(run.taken);
		assertEquals(0, run.rows);
		assertEquals(RunChartRuns.SCHEDULED, run.source);
		assertTrue(run.reason, run.reason.contains(RunCharts.GP_REPORT));
		assertNull(runs.lastTaken());
	}
	
	@Test
	public void runIfIdle_shouldKeepAReasonNoLongerThanTheColumn() {
		Context.getAdministrationService()
		        .saveGlobalProperty(new GlobalProperty(RunCharts.GP_REPORT, StringUtils.repeat("z", 600)));
		
		try {
			new RunChartSnapshotTask().runIfIdle(RunChartRuns.SCHEDULED);
			fail("expected the run to fail");
		}
		catch (APIException e) {
			// expected
		}
		
		assertEquals(RunChartRuns.REASON_LENGTH, runs.latest(7).get(0).reason.length());
	}
	
	@Test
	public void runIfIdle_shouldKeepItsOutcomeWhenTheRunCannotBeRecorded() {
		Context.getAdministrationService().executeSQL("drop table " + RunChartRuns.TABLE, false);
		
		assertEquals(Integer.valueOf(2), new RunChartSnapshotTask().runIfIdle(RunChartRuns.MANUAL));
		
		assertEquals(LocalDate.now(), new RunCharts().lastSnapshot());
	}
	
	@Test
	public void runIfIdle_shouldKeepTheOriginalFailureWhenTheRunCannotBeRecordedEither() {
		Context.getAdministrationService().executeSQL("drop table " + RunChartRuns.TABLE, false);
		Context.getAdministrationService().saveGlobalProperty(new GlobalProperty(RunCharts.GP_REPORT, "no-such-report"));
		
		try {
			new RunChartSnapshotTask().runIfIdle(RunChartRuns.MANUAL);
			fail("expected the run to fail");
		}
		catch (APIException e) {
			assertTrue(e.getMessage().contains(RunCharts.GP_REPORT));
		}
	}
	
	@Test
	public void runIfIdle_shouldRecordNothingForARunSkippedBecauseAnotherIsInProgress() throws Exception {
		Field field = RunChartSnapshotTask.class.getDeclaredField("RUNNING");
		field.setAccessible(true);
		final ReentrantLock running = (ReentrantLock) field.get(null);
		final CountDownLatch held = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);
		Thread other = new Thread(() -> {
			running.lock();
			held.countDown();
			try {
				release.await();
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			finally {
				running.unlock();
			}
		});
		other.start();
		held.await();
		try {
			assertTrue(RunChartSnapshotTask.isRunning());
			assertNull(new RunChartSnapshotTask().runIfIdle(RunChartRuns.MANUAL));
		}
		finally {
			release.countDown();
			other.join();
		}
		
		assertTrue(runs.latest(7).isEmpty());
		assertFalse(RunChartSnapshotTask.isRunning());
	}
}
