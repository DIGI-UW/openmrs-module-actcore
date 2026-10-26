/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.runchart;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.time.LocalDate;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openmrs.GlobalProperty;
import org.openmrs.Location;
import org.openmrs.api.context.Context;
import org.openmrs.module.reporting.dataset.definition.SqlDataSetDefinition;
import org.openmrs.module.reporting.evaluation.parameter.Mapped;
import org.openmrs.module.reporting.evaluation.parameter.Parameter;
import org.openmrs.module.reporting.report.definition.ReportDefinition;
import org.openmrs.module.reporting.report.definition.service.ReportDefinitionService;
import org.openmrs.test.BaseModuleContextSensitiveTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The snapshot as the scheduler meets it, outside a transaction: a test's own transaction would be
 * the one the snapshot joins, so it could not show the snapshot's rollback.
 */
public class RunChartsCommitContextTest extends BaseModuleContextSensitiveTest {
	
	private static final String REPORT_UUID = "5d3c1a77-2a0e-4b79-8f1f-0c2b9a6e4d10";
	
	private static final LocalDate DAY = LocalDate.of(2026, 10, 21);
	
	private final RunCharts runCharts = new RunCharts();
	
	@Before
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public void createTheTableAndPointAtAReport() {
		StringBuilder columns = new StringBuilder();
		for (String column : RunCharts.COLUMNS) {
			columns.append(", ").append(column).append(" int not null default 0");
		}
		Context.getAdministrationService()
		        .executeSQL("create table if not exists " + RunCharts.TABLE
		                + " (record_id int auto_increment primary key, record_date date not null, location_id int" + columns
		                + ", date_created datetime not null)",
		            false);
		Context.getAdministrationService().executeSQL("delete from " + RunCharts.TABLE, false);
		Context.getAdministrationService().saveGlobalProperty(new GlobalProperty(RunCharts.GP_REPORT, REPORT_UUID));
	}
	
	@After
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public void clean() {
		Context.getAdministrationService().executeSQL("delete from " + RunCharts.TABLE, false);
		ReportDefinitionService reports = Context.getService(ReportDefinitionService.class);
		ReportDefinition report = reports.getDefinitionByUuid(REPORT_UUID);
		if (report != null) {
			reports.purgeDefinition(report);
		}
	}
	
	/**
	 * One row for every clinic, with the given total_registrants, which an int column cannot hold when
	 * large.
	 */
	private void saveReport(String totalRegistrants) {
		SqlDataSetDefinition sql = new SqlDataSetDefinition();
		sql.addParameter(new Parameter("endDate", "As of", java.util.Date.class));
		sql.setSqlQuery(
		    "select null as clinic_uuid, " + totalRegistrants + " as total_registrants, 1 as active_registrants");
		ReportDefinition report = Context.getService(ReportDefinitionService.class).getDefinitionByUuid(REPORT_UUID);
		if (report == null) {
			report = new ReportDefinition();
			report.setUuid(REPORT_UUID);
			report.setName("Run chart commit test report");
			report.addParameter(new Parameter("endDate", "As of", java.util.Date.class));
		} else {
			report.getDataSetDefinitions().clear();
		}
		report.addDataSetDefinition("metrics", Mapped.mapStraightThrough(sql));
		Context.getService(ReportDefinitionService.class).saveDefinition(report);
	}
	
	private List<List<Object>> rows() {
		return Context.getAdministrationService()
		        .executeSQL("select total_registrants from " + RunCharts.TABLE + " where record_date = '" + DAY + "'", true);
	}
	
	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public void snapshot_shouldKeepTheDaysEarlierRowsWhenTheInsertFailsAfterTheDeleteRan() {
		saveReport("7");
		runCharts.snapshot(DAY);
		assertEquals(7, ((Number) rows().get(0).get(0)).intValue());
		
		// The delete of the day's rows runs first; this value then fails the insert.
		saveReport("99999999999");
		try {
			runCharts.snapshot(DAY);
			fail("expected the snapshot to fail");
		}
		catch (RuntimeException e) {
			// expected
		}
		Context.clearSession();
		
		assertEquals(1, rows().size());
		assertEquals(7, ((Number) rows().get(0).get(0)).intValue());
	}
}
