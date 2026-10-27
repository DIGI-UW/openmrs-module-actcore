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

import java.time.LocalDate;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.openmrs.GlobalProperty;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.runchart.RunChartSnapshotTask;
import org.openmrs.module.actcore.runchart.RunCharts;
import org.openmrs.module.reporting.dataset.definition.SqlDataSetDefinition;
import org.openmrs.module.reporting.evaluation.parameter.Mapped;
import org.openmrs.module.reporting.evaluation.parameter.Parameter;
import org.openmrs.module.reporting.report.definition.ReportDefinition;
import org.openmrs.module.reporting.report.definition.service.ReportDefinitionService;

public class RunChartSnapshotAdherenceContextTest extends AdherenceContextTest {
	
	private static final String REPORT_UUID = "7b0f6a9e-3c51-4e0b-9d1a-2f6c8e4a1b33";
	
	@Before
	public void createTheTableAndAReportThatCountsTheAdherenceRows() {
		StringBuilder columns = new StringBuilder();
		for (String column : RunCharts.COLUMNS) {
			columns.append(", ").append(column).append(" int not null default 0");
		}
		Context.getAdministrationService()
		        .executeSQL("create table if not exists " + RunCharts.TABLE
		                + " (record_id int auto_increment primary key, record_date date not null, location_id int" + columns
		                + ", date_created datetime not null, unique (record_date, location_id))",
		            false);
		SqlDataSetDefinition sql = new SqlDataSetDefinition();
		sql.addParameter(new Parameter("endDate", "As of", java.util.Date.class));
		// The adherent figure is how many adherence rows exist when the report reads them.
		StringBuilder others = new StringBuilder();
		for (String column : RunCharts.COLUMNS) {
			if (!column.equals("active_registrants_adherent")) {
				others.append(", 0 as ").append(column);
			}
		}
		sql.setSqlQuery("select null as clinic_uuid, (select count(*) from " + AdherenceRefresh.TABLE
		        + ") as active_registrants_adherent" + others);
		ReportDefinition report = new ReportDefinition();
		report.setUuid(REPORT_UUID);
		report.setName("Run chart task test report");
		report.addParameter(new Parameter("endDate", "As of", java.util.Date.class));
		report.addDataSetDefinition("metrics", Mapped.mapStraightThrough(sql));
		Context.getService(ReportDefinitionService.class).saveDefinition(report);
		Context.getAdministrationService().saveGlobalProperty(new GlobalProperty(RunCharts.GP_REPORT, REPORT_UUID));
	}
	
	private List<List<Object>> snapshotRows() {
		return Context.getAdministrationService().executeSQL(
		    "select active_registrants_adherent from " + RunCharts.TABLE + " where location_id is null", true);
	}
	
	@Test
	public void runIfIdle_shouldRecomputeAdherenceBeforeTheReportReadsItSoTheSnapshotIsNotADayStale() {
		prescribe(encounter(7, 60), oralPenicillin, 60, null);
		estimate(encounter(7, 40), 55.0);
		assertEquals(0,
		    Context.getAdministrationService().executeSQL("select * from " + AdherenceRefresh.TABLE, true).size());
		
		assertEquals(Integer.valueOf(1), new RunChartSnapshotTask().runIfIdle());
		
		assertEquals(1, snapshotRows().size());
		assertEquals(1, ((Number) snapshotRows().get(0).get(0)).intValue());
		assertEquals(LocalDate.now(), new RunCharts().lastSnapshot());
	}
}
