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
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.openmrs.GlobalProperty;
import org.openmrs.Location;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.module.reporting.dataset.definition.SqlDataSetDefinition;
import org.openmrs.module.reporting.evaluation.parameter.Mapped;
import org.openmrs.module.reporting.evaluation.parameter.Parameter;
import org.openmrs.module.reporting.report.definition.ReportDefinition;
import org.openmrs.module.reporting.report.definition.service.ReportDefinitionService;
import org.openmrs.test.BaseModuleContextSensitiveTest;

public class RunChartsContextTest extends BaseModuleContextSensitiveTest {
	
	private static final String REPORT_UUID = "0e6f3a52-5c0b-4c43-9d0f-3d5b6a1f9e21";
	
	private static final LocalDate END = LocalDate.of(2026, 10, 21);
	
	private Location clinic;
	
	private final RunCharts runCharts = new RunCharts();
	
	@Before
	public void pointAtAReport() {
		clinic = Context.getLocationService().getLocation(1);
		Context.getAdministrationService().saveGlobalProperty(new GlobalProperty(RunCharts.GP_REPORT, REPORT_UUID));
	}
	
	/**
	 * A row of the clinic and a row of every clinic for the days given, with the day of month as the
	 * active count and the clinic's consultation visits one more than every clinic's.
	 */
	private void saveReportOf(String clinicUuid, int... days) {
		StringBuilder select = new StringBuilder();
		for (int day : days) {
			for (String named : new String[] { "'" + clinicUuid + "'", "null" }) {
				if (select.length() > 0) {
					select.append(" union all ");
				}
				select.append("select date '2026-10-").append(String.format("%02d", day)).append("' as point_date, ")
				        .append(named).append(" as clinic_uuid, 10 as total_registrants, ").append(day)
				        .append(" as active_registrants, 5 as active_registrants_seen_last_7_months,")
				        .append(" 4 as active_registrants_prescribed_prophylaxis, ").append(named.equals("null") ? 0 : 4)
				        .append(" as consultation_visit_total,")
				        .append(" 1 as consultation_visits_with_echocardiogram_resulted,")
				        .append(" 0 as consultation_visits_with_intervention, 3 as interventions_completed,")
				        .append(" 0 as active_registrants_on_bpg, null as bpg_patient_ids");
			}
		}
		useReportSelecting(select.toString());
	}
	
	private void useReportSelecting(String select) {
		SqlDataSetDefinition sql = new SqlDataSetDefinition();
		for (Parameter parameter : parameters()) {
			sql.addParameter(parameter);
		}
		sql.setSqlQuery(select);
		ReportDefinition report = new ReportDefinition();
		report.setUuid(REPORT_UUID);
		report.setName("Run chart test report");
		for (Parameter parameter : parameters()) {
			report.addParameter(parameter);
		}
		report.addDataSetDefinition("metrics", Mapped.mapStraightThrough(sql));
		Context.getService(ReportDefinitionService.class).saveDefinition(report);
	}
	
	private static List<Parameter> parameters() {
		return Arrays.asList(new Parameter("startDate", "From", java.util.Date.class),
		    new Parameter("endDate", "To", java.util.Date.class), new Parameter("points", "Points", String.class));
	}
	
	private static List<LocalDate> dates(List<RunCharts.Point> points) {
		List<LocalDate> dates = new java.util.ArrayList<LocalDate>();
		for (RunCharts.Point point : points) {
			dates.add(point.date);
		}
		return dates;
	}
	
	@Test
	public void pointDates_shouldEndOnTheEndDateAndStepBackADayAWeekToSundayOrAMonthToItsLastDay() {
		assertEquals(Arrays.asList(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 20), END),
		    RunCharts.pointDates(LocalDate.of(2026, 10, 19), END, RunCharts.Period.DAY));
		// 21 October 2026 is a Wednesday; the weeks before it end on Sundays 18, 11 and 4 October.
		assertEquals(Arrays.asList(LocalDate.of(2026, 10, 4), LocalDate.of(2026, 10, 11), LocalDate.of(2026, 10, 18), END),
		    RunCharts.pointDates(LocalDate.of(2026, 10, 1), END, RunCharts.Period.WEEK));
		assertEquals(Arrays.asList(LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 30), END),
		    RunCharts.pointDates(LocalDate.of(2026, 8, 31), END, RunCharts.Period.MONTH));
	}
	
	@Test
	public void pointDates_shouldNotRepeatAnEndThatIsASundayAndShouldStepAMonthEndToTheShorterMonthsEnd() {
		LocalDate sunday = LocalDate.of(2026, 10, 18);
		assertEquals(Arrays.asList(LocalDate.of(2026, 10, 4), LocalDate.of(2026, 10, 11), sunday),
		    RunCharts.pointDates(LocalDate.of(2026, 10, 1), sunday, RunCharts.Period.WEEK));
		assertEquals(
		    Arrays.asList(LocalDate.of(2025, 12, 31), LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28),
		        LocalDate.of(2026, 3, 31)),
		    RunCharts.pointDates(LocalDate.of(2025, 12, 31), LocalDate.of(2026, 3, 31), RunCharts.Period.MONTH));
	}
	
	@Test
	public void pointDates_shouldLeaveOutAPeriodEndingBeforeTheStartAndGiveOnePointForOneDay() {
		assertEquals(Arrays.asList(END), RunCharts.pointDates(END, END, RunCharts.Period.WEEK));
		assertEquals(Arrays.asList(LocalDate.of(2026, 10, 18), END),
		    RunCharts.pointDates(LocalDate.of(2026, 10, 12), END, RunCharts.Period.WEEK));
	}
	
	@Test
	public void calculate_shouldGiveEachMetricAPointPerDayFromTheReportsRowOfTheClinicOrOfEveryClinic() {
		saveReportOf(clinic.getUuid(), 19, 20, 21);
		
		Map<String, List<RunCharts.Point>> series = runCharts.calculate(clinic, LocalDate.of(2026, 10, 19), END,
		    RunCharts.Period.DAY);
		
		assertEquals(RunCharts.METRICS, new java.util.ArrayList<String>(series.keySet()));
		List<RunCharts.Point> active = series.get("active_registrants");
		assertEquals(Arrays.asList(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 20), END), dates(active));
		assertEquals(Double.valueOf(19), active.get(0).value);
		assertEquals(Long.valueOf(19), active.get(0).count);
		assertNull(active.get(0).total);
		assertEquals(Double.valueOf(21), active.get(2).value);
		// 5 of 21 active seen in 7 months; 1 of the clinic's 4 visits with an echo, and of none for every clinic.
		assertEquals(Double.valueOf(23.8), series.get("active_registrants_seen_last_7_months").get(2).value);
		assertEquals(Long.valueOf(5), series.get("active_registrants_seen_last_7_months").get(2).count);
		assertEquals(Long.valueOf(21), series.get("active_registrants_seen_last_7_months").get(2).total);
		assertEquals(Double.valueOf(25), series.get("consultation_visits_with_echocardiogram_resulted").get(2).value);
		List<RunCharts.Point> everyClinic = runCharts.calculate(null, LocalDate.of(2026, 10, 19), END, RunCharts.Period.DAY)
		        .get("consultation_visits_with_echocardiogram_resulted");
		assertNull(everyClinic.get(2).value);
		assertEquals(Long.valueOf(0), everyClinic.get(2).total);
	}
	
	@Test
	public void calculate_shouldCountNobodyOnADayTheReportGivesNoRowFor() {
		saveReportOf(clinic.getUuid(), 19, 21);
		
		Map<String, List<RunCharts.Point>> series = runCharts.calculate(clinic, LocalDate.of(2026, 10, 19), END,
		    RunCharts.Period.DAY);
		
		RunCharts.Point count = series.get("active_registrants").get(1);
		assertEquals(LocalDate.of(2026, 10, 20), count.date);
		assertEquals(Double.valueOf(0), count.value);
		RunCharts.Point percent = series.get("active_registrants_prescribed_prophylaxis").get(1);
		assertNull(percent.value);
		assertEquals(Long.valueOf(0), percent.count);
		assertEquals(Long.valueOf(0), percent.total);
	}
	
	@Test
	public void calculate_shouldGiveNoAdherentWithNoBpgPatientsListedAndWriteNothing() {
		saveReportOf(clinic.getUuid(), 21);
		
		RunCharts.Point adherent = runCharts.calculate(clinic, END, END, RunCharts.Period.DAY)
		        .get("active_registrants_adherent").get(0);
		
		assertEquals(Double.valueOf(0), adherent.value);
		assertEquals(Long.valueOf(0), adherent.count);
		assertEquals(Long.valueOf(21), adherent.total);
	}
	
	@Test
	public void calculate_shouldRefuseAReportLackingAMetricColumn() {
		useReportSelecting("select date '2026-10-21' as point_date, null as clinic_uuid, 1 as total_registrants");
		
		try {
			runCharts.calculate(null, END, END, RunCharts.Period.DAY);
			fail("expected the report to be refused");
		}
		catch (APIException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("active_registrants"));
		}
	}
	
	@Test
	public void calculate_shouldRefuseARowWithNoDate() {
		useReportSelecting("select null as point_date, null as clinic_uuid, 1 as total_registrants, 1 as active_registrants,"
		        + " 0 as active_registrants_seen_last_7_months, 0 as active_registrants_prescribed_prophylaxis,"
		        + " 0 as consultation_visit_total, 0 as consultation_visits_with_echocardiogram_resulted,"
		        + " 0 as consultation_visits_with_intervention, 0 as interventions_completed,"
		        + " 0 as active_registrants_on_bpg, null as bpg_patient_ids");
		
		try {
			runCharts.calculate(null, END, END, RunCharts.Period.DAY);
			fail("expected the row to be refused");
		}
		catch (APIException e) {
			assertTrue(e.getMessage(), e.getMessage().contains(RunCharts.DATE_COLUMN));
		}
	}
	
	@Test
	public void calculate_shouldRefuseABpgListShorterThanItsCount() {
		useReportSelecting("select date '2026-10-21' as point_date, null as clinic_uuid, 1 as total_registrants,"
		        + " 1 as active_registrants, 0 as active_registrants_seen_last_7_months,"
		        + " 0 as active_registrants_prescribed_prophylaxis, 0 as consultation_visit_total,"
		        + " 0 as consultation_visits_with_echocardiogram_resulted, 0 as consultation_visits_with_intervention,"
		        + " 0 as interventions_completed, 3 as active_registrants_on_bpg, '7,8' as bpg_patient_ids");
		
		try {
			runCharts.calculate(null, END, END, RunCharts.Period.DAY);
			fail("expected the list to be refused");
		}
		catch (APIException e) {
			assertTrue(e.getMessage(), e.getMessage().contains(RunCharts.BPG_COUNT_COLUMN));
		}
	}
	
	@Test
	public void calculate_shouldSayWhenNoReportHasTheConfiguredUuid() {
		try {
			runCharts.calculate(null, END, END, RunCharts.Period.DAY);
			fail("expected the report to be refused");
		}
		catch (APIException e) {
			assertTrue(e.getMessage().contains(RunCharts.GP_REPORT));
		}
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
