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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.time.LocalDate;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.openmrs.GlobalProperty;
import org.openmrs.Location;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.module.reporting.dataset.definition.SqlDataSetDefinition;
import org.openmrs.module.reporting.evaluation.parameter.Parameter;
import org.openmrs.module.reporting.evaluation.parameter.Mapped;
import org.openmrs.module.reporting.report.definition.ReportDefinition;
import org.openmrs.module.reporting.report.definition.service.ReportDefinitionService;
import org.openmrs.test.BaseModuleContextSensitiveTest;

public class RunChartsContextTest extends BaseModuleContextSensitiveTest {
	
	private static final String REPORT_UUID = "0e6f3a52-5c0b-4c43-9d0f-3d5b6a1f9e21";
	
	private static final LocalDate TODAY = LocalDate.of(2026, 10, 21);
	
	private Location clinic;
	
	private final RunCharts runCharts = new RunCharts();
	
	@Before
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
		clinic = Context.getLocationService().getLocation(1);
		Context.getAdministrationService().saveGlobalProperty(new GlobalProperty(RunCharts.GP_REPORT, REPORT_UUID));
	}
	
	/**
	 * A report giving the clinic's row and the row of every clinic, with endDate's day of month as the
	 * active count.
	 */
	private void saveReport(String clinicUuid) {
		saveReport(REPORT_UUID, clinicUuid);
	}
	
	private void saveReport(String reportUuid, String clinicUuid) {
		String row = "%s as clinic_uuid, 10 as total_registrants, day_of_month(:endDate) as active_registrants,"
		        + " %d as active_registrants_seen_last_7_months, 4 as active_registrants_prescribed_prophylaxis,"
		        + " 2 as active_registrants_adherent, %d as consultation_visit_total,"
		        + " 1 as consultation_visits_with_echocardiogram_resulted, 0 as consultation_visits_with_intervention,"
		        + " 3 as interventions_completed";
		SqlDataSetDefinition sql = new SqlDataSetDefinition();
		sql.addParameter(new Parameter("endDate", "As of", java.util.Date.class));
		sql.setSqlQuery("select " + String.format(row, "'" + clinicUuid + "'", 5, 4) + " union all select "
		        + String.format(row, "null", 6, 0));
		ReportDefinition report = new ReportDefinition();
		report.setUuid(reportUuid);
		report.setName("Run chart test report");
		report.addParameter(new Parameter("endDate", "As of", java.util.Date.class));
		report.addDataSetDefinition("metrics", Mapped.mapStraightThrough(sql));
		Context.getService(ReportDefinitionService.class).saveDefinition(report);
	}
	
	private List<List<Object>> rows() {
		return Context.getAdministrationService().executeSQL(
		    "select record_date, location_id, active_registrants from " + RunCharts.TABLE + " order by location_id", true);
	}
	
	@Test
	public void snapshot_shouldKeepEachOfTheReportsRowsForTheDay() {
		saveReport(clinic.getUuid());
		
		assertEquals(2, runCharts.snapshot(TODAY));
		
		List<List<Object>> rows = rows();
		assertEquals(2, rows.size());
		assertNull(rows.get(0).get(1));
		assertEquals(clinic.getLocationId(), rows.get(1).get(1));
		assertEquals(21, ((Number) rows.get(1).get(2)).intValue());
		assertEquals(TODAY, runCharts.lastSnapshot());
	}
	
	@Test
	public void snapshot_shouldReplaceAnEarlierSnapshotOfTheSameDayAndKeepOtherDays() {
		saveReport(clinic.getUuid());
		runCharts.snapshot(TODAY.minusDays(1));
		runCharts.snapshot(TODAY);
		
		runCharts.snapshot(TODAY);
		
		assertEquals(4, rows().size());
	}
	
	@Test
	public void snapshot_shouldRefuseARowNamingNoLocationAndKeepTheDaysRows() {
		saveReport(clinic.getUuid());
		runCharts.snapshot(TODAY);
		String other = "6a1d2f0e-8b3c-4d5e-9f60-718293a4b5c6";
		saveReport(other, "a0000000-0000-0000-0000-000000000000");
		Context.getAdministrationService().saveGlobalProperty(new GlobalProperty(RunCharts.GP_REPORT, other));
		
		try {
			runCharts.snapshot(TODAY);
			fail("expected the snapshot to be refused");
		}
		catch (APIException e) {
			assertTrue(e.getMessage().contains("names no location"));
		}
		assertEquals(2, rows().size());
	}
	
	@Test
	public void snapshot_shouldSayWhenNoReportHasTheConfiguredUuid() {
		try {
			runCharts.snapshot(TODAY);
			fail("expected the snapshot to be refused");
		}
		catch (APIException e) {
			assertTrue(e.getMessage().contains(RunCharts.GP_REPORT));
		}
	}
	
	@Test
	public void series_shouldGiveATotalPerDayOfTheClinicOrOfEveryClinic() {
		saveReport(clinic.getUuid());
		runCharts.snapshot(TODAY.minusDays(2));
		runCharts.snapshot(TODAY);
		
		List<RunCharts.Point> points = runCharts.series("active_registrants", clinic, TODAY.minusDays(7), TODAY);
		
		assertEquals(2, points.size());
		assertEquals(TODAY.minusDays(2), points.get(0).date);
		assertEquals(Double.valueOf(19), points.get(0).value);
		assertEquals(Double.valueOf(21), points.get(1).value);
		assertEquals(Double.valueOf(10), runCharts.series("total_registrants", null, TODAY, TODAY).get(0).value);
	}
	
	@Test
	public void series_shouldGiveAPercentOfItsDenominatorAndNoneOfZero() {
		saveReport(clinic.getUuid());
		runCharts.snapshot(TODAY);
		
		// 5 of 21 active seen in 7 months; 1 of 4 visits with an echo.
		assertEquals(Double.valueOf(23.8),
		    runCharts.series("active_registrants_seen_last_7_months", clinic, TODAY, TODAY).get(0).value);
		assertEquals(Double.valueOf(25),
		    runCharts.series("consultation_visits_with_echocardiogram_resulted", clinic, TODAY, TODAY).get(0).value);
		// The row of every clinic has no consultation visits.
		assertNull(runCharts.series("consultation_visits_with_echocardiogram_resulted", null, TODAY, TODAY).get(0).value);
	}
	
	@Test
	public void series_shouldLeaveOutDaysOutsideTheRange() {
		saveReport(clinic.getUuid());
		runCharts.snapshot(TODAY.minusDays(10));
		runCharts.snapshot(TODAY);
		
		assertEquals(1, runCharts.series("interventions_completed", clinic, TODAY.minusDays(5), TODAY).size());
	}
	
	@Test(expected = IllegalArgumentException.class)
	public void series_shouldRefuseAColumnThatIsNoMetric() {
		runCharts.series("consultation_visit_total", null, TODAY, TODAY);
	}
	
	@Test
	public void lastSnapshot_shouldBeNullBeforeTheFirst() {
		assertNull(runCharts.lastSnapshot());
	}
}
