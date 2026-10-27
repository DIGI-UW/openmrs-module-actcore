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

import java.sql.Date;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.openmrs.Location;
import org.openmrs.api.APIException;
import org.openmrs.api.AdministrationService;
import org.openmrs.api.context.Context;
import org.openmrs.module.reporting.dataset.DataSet;
import org.openmrs.module.reporting.dataset.DataSetRow;
import org.openmrs.module.reporting.evaluation.EvaluationContext;
import org.openmrs.module.reporting.evaluation.EvaluationException;
import org.openmrs.module.reporting.report.ReportData;
import org.openmrs.module.reporting.report.definition.ReportDefinition;
import org.openmrs.module.reporting.report.definition.service.ReportDefinitionService;
import org.openmrs.util.PrivilegeConstants;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ACT 2.0's run charts: each clinic's metrics, snapshotted daily into actcore_run_chart_record from
 * the report actcore.runCharts.report names, and read back as a series over a date range.
 */
public class RunCharts {
	
	public static final String TABLE = "actcore_run_chart_record";
	
	public static final String GP_REPORT = "actcore.runCharts.report";
	
	/** The report's column, and the table's, naming the clinic; empty for the row of every clinic. */
	static final String CLINIC_COLUMN = "clinic_uuid";
	
	/** ACT 2.0's runchart_record columns, which the report gives and the table keeps. */
	public static final List<String> COLUMNS = Collections.unmodifiableList(Arrays.asList("total_registrants",
	    "active_registrants", "active_registrants_seen_last_7_months", "active_registrants_prescribed_prophylaxis",
	    "active_registrants_adherent", "consultation_visit_total", "consultation_visits_with_echocardiogram_resulted",
	    "consultation_visits_with_intervention", "interventions_completed"));
	
	/** The metrics ACT 2.0 charted as a percent, of the column given, as dashboard_query.py does. */
	static final Map<String, String> PERCENT_OF;
	
	static {
		Map<String, String> percentOf = new HashMap<String, String>();
		percentOf.put("active_registrants_seen_last_7_months", "active_registrants");
		percentOf.put("active_registrants_prescribed_prophylaxis", "active_registrants");
		percentOf.put("active_registrants_adherent", "active_registrants");
		percentOf.put("consultation_visits_with_echocardiogram_resulted", "consultation_visit_total");
		percentOf.put("consultation_visits_with_intervention", "consultation_visit_total");
		PERCENT_OF = Collections.unmodifiableMap(percentOf);
	}
	
	/** ACT 2.0's eight run chart metrics; consultation_visit_total is only a denominator. */
	public static final List<String> METRICS = Collections.unmodifiableList(Arrays.asList("total_registrants",
	    "active_registrants", "active_registrants_seen_last_7_months", "active_registrants_prescribed_prophylaxis",
	    "active_registrants_adherent", "consultation_visits_with_echocardiogram_resulted",
	    "consultation_visits_with_intervention", "interventions_completed"));
	
	/** How the daily snapshots are grouped into points: a week starts on Monday. */
	public enum Period {
		
		DAY,
		WEEK,
		MONTH;
		
		/** The period a request names, week when it names none. */
		public static Period of(String name) {
			if (StringUtils.isBlank(name)) {
				return WEEK;
			}
			for (Period period : values()) {
				if (period.name().equalsIgnoreCase(name.trim())) {
					return period;
				}
			}
			throw new IllegalArgumentException("points must be day, week or month");
		}
		
		LocalDate startOf(LocalDate day) {
			switch (this) {
				case WEEK:
					return day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
				case MONTH:
					return day.withDayOfMonth(1);
				default:
					return day;
			}
		}
	}
	
	public static class Point {
		
		/** The period's first day, which may precede the range's start. */
		public final LocalDate date;
		
		/** The metric's total, or its percent rounded to one decimal; null for a percent of none. */
		public final Double value;
		
		/** The numerator summed over the period's days; for a count, its value. */
		public final Long count;
		
		/** The denominator summed over the period's days; null for a count. */
		public final Long total;
		
		Point(LocalDate date, Double value) {
			this(date, value, null, null);
		}
		
		Point(LocalDate date, Double value, Long count, Long total) {
			this.date = date;
			this.value = value;
			this.count = count;
			this.total = total;
		}
	}
	
	/**
	 * Evaluates the report as of the day and replaces that day's rows with its rows, so a second run on
	 * one day takes the place of the first, in one transaction. Returns how many rows it wrote.
	 */
	public int snapshot(LocalDate day) {
		final List<String> values = new ArrayList<String>();
		for (DataSetRow row : evaluate(day)) {
			values.add(values(day, row));
		}
		if (values.isEmpty()) {
			throw new APIException("The run chart report has no rows for " + day + ", so the day's snapshot is kept");
		}
		final String date = "'" + Date.valueOf(day) + "'";
		try {
			Context.addProxyPrivilege(PrivilegeConstants.SQL_LEVEL_ACCESS);
			final AdministrationService admin = Context.getAdministrationService();
			PlatformTransactionManager transactions = Context.getRegisteredComponent("transactionManager",
			    PlatformTransactionManager.class);
			new TransactionTemplate(transactions).execute(new TransactionCallbackWithoutResult() {
				
				@Override
				protected void doInTransactionWithoutResult(TransactionStatus status) {
					admin.executeSQL("delete from " + TABLE + " where record_date = " + date, false);
					admin.executeSQL("insert into " + TABLE + " (record_date, location_id, "
					        + StringUtils.join(COLUMNS, ", ") + ", date_created) values " + StringUtils.join(values, ", "),
					    false);
				}
			});
		}
		finally {
			Context.removeProxyPrivilege(PrivilegeConstants.SQL_LEVEL_ACCESS);
		}
		return values.size();
	}
	
	private DataSet evaluate(LocalDate day) {
		String uuid = Context.getAdministrationService().getGlobalProperty(GP_REPORT);
		ReportDefinitionService reports = Context.getService(ReportDefinitionService.class);
		ReportDefinition report = StringUtils.isBlank(uuid) ? null : reports.getDefinitionByUuid(uuid.trim());
		if (report == null) {
			throw new APIException("No report has the uuid " + GP_REPORT + " names: " + uuid);
		}
		EvaluationContext context = new EvaluationContext();
		context.addParameterValue("endDate", Date.valueOf(day));
		try {
			ReportData data = reports.evaluate(report, context);
			if (data.getDataSets().isEmpty()) {
				throw new APIException("The run chart report " + uuid + " has no dataset");
			}
			return data.getDataSets().values().iterator().next();
		}
		catch (EvaluationException e) {
			throw new APIException("Could not evaluate the run chart report " + uuid, e);
		}
	}
	
	/**
	 * A row's SQL values; a clinic that is no location is an error rather than a row of every clinic.
	 */
	private static String values(LocalDate day, DataSetRow row) {
		Object clinic = row.getColumnValue(CLINIC_COLUMN);
		String locationId = "null";
		if (clinic != null && StringUtils.isNotBlank(clinic.toString())) {
			Location location = Context.getLocationService().getLocationByUuid(clinic.toString());
			if (location == null) {
				throw new APIException("The run chart report names no location " + clinic);
			}
			locationId = location.getLocationId().toString();
		}
		List<String> values = new ArrayList<String>();
		values.add("'" + Date.valueOf(day) + "'");
		values.add(locationId);
		for (String column : COLUMNS) {
			Object value = row.getColumnValue(column);
			if (!(value instanceof Number)) {
				throw new APIException("The run chart report has no number in its column " + column);
			}
			values.add(String.valueOf(((Number) value).longValue()));
		}
		values.add("current_timestamp");
		return "(" + StringUtils.join(values, ", ") + ")";
	}
	
	/**
	 * The metric in points of the period, of a clinic or of every clinic when it is null: a percent is
	 * the ratio of its sums over the period's days (null for none), a count its latest in the period.
	 */
	public List<Point> series(String metric, Location clinic, LocalDate start, LocalDate end, Period period) {
		if (!METRICS.contains(metric)) {
			throw new IllegalArgumentException("No run chart metric is named " + metric);
		}
		String denominator = PERCENT_OF.get(metric);
		String where = " where record_date >= '" + Date.valueOf(start) + "' and record_date <= '" + Date.valueOf(end)
		        + "' and location_id " + (clinic == null ? "is null" : "= " + clinic.getLocationId());
		List<List<Object>> rows;
		try {
			Context.addProxyPrivilege(PrivilegeConstants.SQL_LEVEL_ACCESS);
			rows = Context.getAdministrationService().executeSQL(
			    "select record_date, sum(" + metric + ")" + (denominator == null ? "" : ", sum(" + denominator + ")")
			            + " from " + TABLE + where + " group by record_date order by record_date",
			    true);
		}
		finally {
			Context.removeProxyPrivilege(PrivilegeConstants.SQL_LEVEL_ACCESS);
		}
		Map<LocalDate, double[]> periods = new LinkedHashMap<LocalDate, double[]>();
		for (List<Object> row : rows) {
			double[] sums = periods.computeIfAbsent(period.startOf(toLocalDate(row.get(0))), day -> new double[2]);
			double numerator = ((Number) row.get(1)).doubleValue();
			if (denominator == null) {
				sums[0] = numerator;
			} else {
				sums[0] += numerator;
				sums[1] += ((Number) row.get(2)).doubleValue();
			}
		}
		List<Point> points = new ArrayList<Point>();
		for (Map.Entry<LocalDate, double[]> entry : periods.entrySet()) {
			double[] sums = entry.getValue();
			Double value = denominator == null ? Double.valueOf(sums[0])
			        : sums[1] == 0 ? null : Double.valueOf(Math.round(1000.0 * sums[0] / sums[1]) / 10.0);
			points.add(new Point(entry.getKey(), value, Long.valueOf(Math.round(sums[0])),
			        denominator == null ? null : Long.valueOf(Math.round(sums[1]))));
		}
		return points;
	}
	
	/**
	 * The middle of the points' values, or the mean of the middle two, ignoring points with no value;
	 * null with none.
	 */
	public static Double median(List<Point> points) {
		List<Double> values = new ArrayList<Double>();
		for (Point point : points) {
			if (point.value != null) {
				values.add(point.value);
			}
		}
		if (values.isEmpty()) {
			return null;
		}
		Collections.sort(values);
		int middle = values.size() / 2;
		return values.size() % 2 == 1 ? values.get(middle) : (values.get(middle - 1) + values.get(middle)) / 2;
	}
	
	/** The latest day with a snapshot, or null before the first. */
	public LocalDate lastSnapshot() {
		List<List<Object>> rows;
		try {
			Context.addProxyPrivilege(PrivilegeConstants.SQL_LEVEL_ACCESS);
			rows = Context.getAdministrationService().executeSQL("select max(record_date) from " + TABLE, true);
		}
		finally {
			Context.removeProxyPrivilege(PrivilegeConstants.SQL_LEVEL_ACCESS);
		}
		return rows.isEmpty() || rows.get(0).get(0) == null ? null : toLocalDate(rows.get(0).get(0));
	}
	
	private static LocalDate toLocalDate(Object value) {
		if (value instanceof java.sql.Date) {
			return ((java.sql.Date) value).toLocalDate();
		}
		if (value instanceof java.util.Date) {
			return new java.sql.Date(((java.util.Date) value).getTime()).toLocalDate();
		}
		return LocalDate.parse(value.toString().substring(0, 10));
	}
}
