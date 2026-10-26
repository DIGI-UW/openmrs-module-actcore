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
import static org.junit.Assert.fail;

import java.time.LocalDate;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openmrs.GlobalProperty;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * A run that fails after the snapshot began writing, outside a test transaction so that the
 * snapshot's rollback and the run's own commit are the real ones.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class RunChartRunCommitContextTest extends AdherenceContextTest {
	
	private static final String REPORT_UUID = "c41d8e57-92ab-4f06-8b3c-5a7e1d9f2064";
	
	private static final String TOO_BIG = "3000000000";
	
	@Before
	public void createTheTablesAndAReportOfOneRow() {
		StringBuilder columns = new StringBuilder();
		for (String column : RunCharts.COLUMNS) {
			columns.append(", ").append(column).append(" int not null default 0");
		}
		Context.getAdministrationService()
		        .executeSQL("create table if not exists " + RunCharts.TABLE
		                + " (record_id int auto_increment primary key, record_date date not null, location_id int" + columns
		                + ", date_created datetime not null, unique (record_date, location_id))",
		            false);
		Context.getAdministrationService().executeSQL(
		    "create table if not exists " + RunChartRuns.TABLE
		            + " (run_id int auto_increment primary key, run_date date not null, finished datetime not null,"
		            + " duration_millis bigint not null default 0, source varchar(16) not null, status varchar(16) not null,"
		            + " row_count int not null default 0, clinic_count int not null default 0, failure_reason varchar(500))",
		    false);
		saveReport("5");
		Context.getAdministrationService().saveGlobalProperty(new GlobalProperty(RunCharts.GP_REPORT, REPORT_UUID));
	}
	
	@After
	public void removeWhatCommitted() {
		Transactions.inNewTransaction(() -> {
			Context.getAdministrationService().executeSQL("delete from " + RunChartRuns.TABLE, false);
			Context.getAdministrationService().executeSQL("delete from " + RunCharts.TABLE, false);
		});
	}
	
	private void saveReport(String active) {
		StringBuilder others = new StringBuilder();
		for (String column : RunCharts.COLUMNS) {
			if (!column.equals("active_registrants")) {
				others.append(", 0 as ").append(column);
			}
		}
		SqlDataSetDefinition sql = new SqlDataSetDefinition();
		sql.addParameter(new Parameter("endDate", "As of", java.util.Date.class));
		sql.setSqlQuery("select null as clinic_uuid, " + active + " as active_registrants" + others);
		ReportDefinitionService reports = Context.getService(ReportDefinitionService.class);
		ReportDefinition report = reports.getDefinitionByUuid(REPORT_UUID);
		if (report == null) {
			report = new ReportDefinition();
			report.setUuid(REPORT_UUID);
			report.setName("Run chart commit test report");
			report.addParameter(new Parameter("endDate", "As of", java.util.Date.class));
		} else {
			report.getDataSetDefinitions().clear();
		}
		report.addDataSetDefinition("metrics", Mapped.mapStraightThrough(sql));
		reports.saveDefinition(report);
	}
	
	@Test
	public void runIfIdle_shouldKeepTheDaysRowsAndRecordTheFailedRunWhenTheInsertFailsAfterTheDelete() {
		new RunChartSnapshotTask().runIfIdle(RunChartRuns.SCHEDULED);
		saveReport(TOO_BIG);
		
		try {
			new RunChartSnapshotTask().runIfIdle(RunChartRuns.MANUAL);
			fail("expected the run to fail");
		}
		catch (RuntimeException e) {
			// expected
		}
		
		List<List<Object>> kept = Context.getAdministrationService().executeSQL(
		    "select active_registrants from " + RunCharts.TABLE + " where record_date = '" + LocalDate.now() + "'", true);
		assertEquals(1, kept.size());
		assertEquals(5, ((Number) kept.get(0).get(0)).intValue());
		RunChartRuns.Run failed = new RunChartRuns().latest(7).get(0);
		assertFalse(failed.taken);
		assertEquals(RunChartRuns.MANUAL, failed.source);
		assertEquals(0, failed.rows);
	}
}
