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
		                + ", date_created datetime not null, unique (record_date, location_id))",
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
	
	private void useReportSelecting(String select) {
		String uuid = "9c2e7d14-5a60-4f1b-8e3d-0b7a6c5d4e32";
		SqlDataSetDefinition sql = new SqlDataSetDefinition();
		sql.addParameter(new Parameter("endDate", "As of", java.util.Date.class));
		sql.setSqlQuery(select);
		ReportDefinition report = new ReportDefinition();
		report.setUuid(uuid);
		report.setName("Run chart test report");
		report.addParameter(new Parameter("endDate", "As of", java.util.Date.class));
		report.addDataSetDefinition("metrics", Mapped.mapStraightThrough(sql));
		Context.getService(ReportDefinitionService.class).saveDefinition(report);
		Context.getAdministrationService().saveGlobalProperty(new GlobalProperty(RunCharts.GP_REPORT, uuid));
	}
	
	@Test
	public void snapshot_shouldRefuseAReportWithNoRowsAndKeepTheDaysRows() {
		saveReport(clinic.getUuid());
		runCharts.snapshot(TODAY);
		useReportSelecting("select null as clinic_uuid, 1 as total_registrants from person where 1 = 0");
		
		try {
			runCharts.snapshot(TODAY);
			fail("expected the snapshot to be refused");
		}
		catch (APIException e) {
			assertTrue(e.getMessage().contains("no rows"));
		}
		assertEquals(2, rows().size());
	}
	
	@Test
	public void snapshot_shouldRefuseAReportLackingAMetricColumnAndKeepTheDaysRows() {
		saveReport(clinic.getUuid());
		runCharts.snapshot(TODAY);
		useReportSelecting("select null as clinic_uuid, 1 as total_registrants");
		
		try {
			runCharts.snapshot(TODAY);
			fail("expected the snapshot to be refused");
		}
		catch (APIException e) {
			assertTrue(e.getMessage().contains("active_registrants"));
		}
		assertEquals(2, rows().size());
	}
	
	@Test
	public void table_shouldRefuseASecondRowForAClinicAndDay() {
		saveReport(clinic.getUuid());
		runCharts.snapshot(TODAY);
		
		try {
			Context.getAdministrationService()
			        .executeSQL("insert into " + RunCharts.TABLE + " (record_date, location_id, date_created) values ('"
			                + TODAY + "', " + clinic.getLocationId() + ", current_timestamp)",
			            false);
			fail("expected the duplicate to be refused");
		}
		catch (Exception e) {
			assertEquals(2, rows().size());
		}
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
		
		List<RunCharts.Point> points = runCharts.series("active_registrants", clinic, TODAY.minusDays(7), TODAY,
		    RunCharts.Period.DAY);
		
		assertEquals(2, points.size());
		assertEquals(TODAY.minusDays(2), points.get(0).date);
		assertEquals(Double.valueOf(19), points.get(0).value);
		assertEquals(Double.valueOf(21), points.get(1).value);
		assertEquals(Double.valueOf(10),
		    runCharts.series("total_registrants", null, TODAY, TODAY, RunCharts.Period.DAY).get(0).value);
	}
	
	@Test
	public void series_shouldGiveAPercentOfItsDenominatorAndNoneOfZero() {
		saveReport(clinic.getUuid());
		runCharts.snapshot(TODAY);
		
		// 5 of 21 active seen in 7 months; 1 of 4 visits with an echo.
		assertEquals(Double.valueOf(23.8), runCharts
		        .series("active_registrants_seen_last_7_months", clinic, TODAY, TODAY, RunCharts.Period.DAY).get(0).value);
		assertEquals(Double.valueOf(25),
		    runCharts.series("consultation_visits_with_echocardiogram_resulted", clinic, TODAY, TODAY, RunCharts.Period.DAY)
		            .get(0).value);
		// The row of every clinic has no consultation visits.
		assertNull(
		    runCharts.series("consultation_visits_with_echocardiogram_resulted", null, TODAY, TODAY, RunCharts.Period.DAY)
		            .get(0).value);
	}
	
	@Test
	public void series_shouldLeaveOutDaysOutsideTheRange() {
		saveReport(clinic.getUuid());
		runCharts.snapshot(TODAY.minusDays(10));
		runCharts.snapshot(TODAY);
		
		assertEquals(1,
		    runCharts.series("interventions_completed", clinic, TODAY.minusDays(5), TODAY, RunCharts.Period.DAY).size());
	}
	
	@Test(expected = IllegalArgumentException.class)
	public void series_shouldRefuseAColumnThatIsNoMetric() {
		runCharts.series("consultation_visit_total", null, TODAY, TODAY, RunCharts.Period.DAY);
	}
	
	@Test
	public void lastSnapshot_shouldBeNullBeforeTheFirst() {
		assertNull(runCharts.lastSnapshot());
	}
	
	// 19, 21 and 23 October 2026 share a week and 26 starts the next; active is the day of month.
	private void snapshotThreeDaysOfOneWeekAndTheNextMonday() {
		saveReport(clinic.getUuid());
		for (int day : new int[] { 19, 21, 23, 26 }) {
			runCharts.snapshot(LocalDate.of(2026, 10, day));
		}
	}
	
	@Test
	public void series_shouldGiveACountAsItsLatestSnapshotInEachWeek() {
		snapshotThreeDaysOfOneWeekAndTheNextMonday();
		
		List<RunCharts.Point> points = runCharts.series("active_registrants", clinic, LocalDate.of(2026, 10, 1),
		    LocalDate.of(2026, 10, 31), RunCharts.Period.WEEK);
		
		assertEquals(2, points.size());
		assertEquals(LocalDate.of(2026, 10, 19), points.get(0).date);
		assertEquals(Double.valueOf(23), points.get(0).value);
		assertEquals(Long.valueOf(23), points.get(0).count);
		assertNull(points.get(0).total);
		assertEquals(LocalDate.of(2026, 10, 26), points.get(1).date);
		assertEquals(Double.valueOf(26), points.get(1).value);
	}
	
	@Test
	public void series_shouldGiveAPercentAsTheRatioOfTheSumsOverEachWeekNotTheMeanOfItsDays() {
		snapshotThreeDaysOfOneWeekAndTheNextMonday();
		
		List<RunCharts.Point> points = runCharts.series("active_registrants_seen_last_7_months", clinic,
		    LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), RunCharts.Period.WEEK);
		
		// 5 seen on each of 3 days over 19 + 21 + 23 active; the mean of the daily percents would be 24.2.
		assertEquals(Double.valueOf(23.8), points.get(0).value);
		assertEquals(Long.valueOf(15), points.get(0).count);
		assertEquals(Long.valueOf(63), points.get(0).total);
		assertEquals(Double.valueOf(19.2), points.get(1).value);
	}
	
	@Test
	public void series_shouldGroupByCalendarMonth() {
		snapshotThreeDaysOfOneWeekAndTheNextMonday();
		runCharts.snapshot(LocalDate.of(2026, 11, 2));
		
		List<RunCharts.Point> counts = runCharts.series("active_registrants", clinic, LocalDate.of(2026, 10, 1),
		    LocalDate.of(2026, 11, 30), RunCharts.Period.MONTH);
		List<RunCharts.Point> percents = runCharts.series("active_registrants_seen_last_7_months", clinic,
		    LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 30), RunCharts.Period.MONTH);
		
		assertEquals(2, counts.size());
		assertEquals(LocalDate.of(2026, 10, 1), counts.get(0).date);
		assertEquals(Double.valueOf(26), counts.get(0).value);
		assertEquals(LocalDate.of(2026, 11, 1), counts.get(1).date);
		assertEquals(Double.valueOf(2), counts.get(1).value);
		// 5 on each of 4 days over 19 + 21 + 23 + 26 = 89.
		assertEquals(Double.valueOf(22.5), percents.get(0).value);
	}
	
	@Test
	public void series_shouldGiveNoValueForAWeekWhoseDenominatorIsZero() {
		saveReport(clinic.getUuid());
		runCharts.snapshot(LocalDate.of(2026, 10, 19));
		
		// The row of every clinic has no consultation visits, so no percent of them.
		List<RunCharts.Point> points = runCharts.series("consultation_visits_with_intervention", null,
		    LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 25), RunCharts.Period.WEEK);
		
		assertEquals(1, points.size());
		assertNull(points.get(0).value);
		assertEquals(Long.valueOf(0), points.get(0).total);
	}
	
	@Test
	public void median_shouldBeTheMiddleValueOrTheMeanOfTheMiddleTwoAndSkipDaysWithNoValue() {
		assertEquals(Double.valueOf(21), RunCharts.median(points(19, 21, 23)));
		assertEquals(Double.valueOf(22), RunCharts.median(points(19, 21, 23, 26)));
		assertEquals(Double.valueOf(21), RunCharts.median(points(19, null, 21, null, 23)));
		assertNull(RunCharts.median(points((Integer) null)));
		assertNull(RunCharts.median(points()));
	}
	
	private static List<RunCharts.Point> points(Integer... values) {
		List<RunCharts.Point> points = new java.util.ArrayList<RunCharts.Point>();
		LocalDate day = LocalDate.of(2026, 10, 1);
		for (Integer value : values) {
			points.add(new RunCharts.Point(day, value == null ? null : value.doubleValue()));
			day = day.plusDays(1);
		}
		return points;
	}
	
	@Test
	public void period_shouldBeNamedByTheRequestAndRefuseAnythingElse() {
		assertEquals(RunCharts.Period.WEEK, RunCharts.Period.of(null));
		assertEquals(RunCharts.Period.DAY, RunCharts.Period.of("day"));
		assertEquals(RunCharts.Period.MONTH, RunCharts.Period.of("month"));
		try {
			RunCharts.Period.of("year");
			fail("expected the period to be refused");
		}
		catch (IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("day, week or month"));
		}
	}
}
