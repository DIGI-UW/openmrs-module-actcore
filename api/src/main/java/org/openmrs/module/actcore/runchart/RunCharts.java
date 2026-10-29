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
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.commons.lang3.StringUtils;
import org.openmrs.Location;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.adherence.AdherenceSeries;
import org.openmrs.module.reporting.dataset.DataSet;
import org.openmrs.module.reporting.dataset.DataSetRow;
import org.openmrs.module.reporting.evaluation.EvaluationContext;
import org.openmrs.module.reporting.evaluation.EvaluationException;
import org.openmrs.module.reporting.report.ReportData;
import org.openmrs.module.reporting.report.definition.ReportDefinition;
import org.openmrs.module.reporting.report.definition.service.ReportDefinitionService;

/**
 * ACT 2.0's run charts, calculated when asked and never stored: one pass of the report
 * actcore.runCharts.report names, and a replay of BPG patients' histories to each point's day.
 */
public class RunCharts {
	
	public static final String GP_REPORT = "actcore.runCharts.report";
	
	/** The report's column naming the clinic; empty for the row of every clinic. */
	static final String CLINIC_COLUMN = "clinic_uuid";
	
	/** The report's column naming the day a row is as of. */
	static final String DATE_COLUMN = "point_date";
	
	/**
	 * The report's column listing, separated by commas, the active patients on an in-force BPG regimen
	 * as of the day, whose adherence the point replays.
	 */
	static final String BPG_COLUMN = "bpg_patient_ids";
	
	/**
	 * The report's count of the patients the list names: GROUP_CONCAT cuts a long list short without an
	 * error, so a list that does not number its count is refused rather than read.
	 */
	static final String BPG_COUNT_COLUMN = "active_registrants_on_bpg";
	
	/** The metric the report does not give: counted here from the BPG list and the replay. */
	static final String ADHERENT = "active_registrants_adherent";
	
	/** ACT 2.0's runchart_record columns the report gives as numbers. */
	public static final List<String> COLUMNS = Collections.unmodifiableList(Arrays.asList("total_registrants",
	    "active_registrants", "active_registrants_seen_last_7_months", "active_registrants_prescribed_prophylaxis",
	    "consultation_visit_total", "consultation_visits_with_echocardiogram_resulted",
	    "consultation_visits_with_intervention", "interventions_completed"));
	
	/** The report's columns that must hold a number in every row. */
	private static final List<String> NUMBER_COLUMNS;
	
	static {
		List<String> columns = new ArrayList<String>(COLUMNS);
		columns.add(BPG_COUNT_COLUMN);
		NUMBER_COLUMNS = Collections.unmodifiableList(columns);
	}
	
	/** The metrics ACT 2.0 charted as a percent, of the column given, as dashboard_query.py does. */
	static final Map<String, String> PERCENT_OF;
	
	static {
		Map<String, String> percentOf = new HashMap<String, String>();
		percentOf.put("active_registrants_seen_last_7_months", "active_registrants");
		percentOf.put("active_registrants_prescribed_prophylaxis", "active_registrants");
		percentOf.put(ADHERENT, "active_registrants");
		percentOf.put("consultation_visits_with_echocardiogram_resulted", "consultation_visit_total");
		percentOf.put("consultation_visits_with_intervention", "consultation_visit_total");
		PERCENT_OF = Collections.unmodifiableMap(percentOf);
	}
	
	/** ACT 2.0's eight run chart metrics; consultation_visit_total is only a denominator. */
	public static final List<String> METRICS = Collections.unmodifiableList(
	    Arrays.asList("total_registrants", "active_registrants", "active_registrants_seen_last_7_months",
	        "active_registrants_prescribed_prophylaxis", ADHERENT, "consultation_visits_with_echocardiogram_resulted",
	        "consultation_visits_with_intervention", "interventions_completed"));
	
	/** The most points one request calculates; MariaDB stops a recursive query at 1000 iterations. */
	public static final int MOST_POINTS = 1000;
	
	/** The longest range, in days, charted in daily points. */
	public static final int MOST_DAILY_DAYS = 92;
	
	/** How a range is cut into points: each point is the figure as of the last day of its period. */
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
		
		/** The last day of the period before the one holding the day, as the report's SQL steps. */
		LocalDate previous(LocalDate day) {
			switch (this) {
				case WEEK:
					return day.with(TemporalAdjusters.previous(DayOfWeek.SUNDAY));
				case MONTH:
					return day.minusMonths(1).with(TemporalAdjusters.lastDayOfMonth());
				default:
					return day.minusDays(1);
			}
		}
	}
	
	public static class Point {
		
		/** The day the figure is as of: the period's last day, or the range's end for the last period. */
		public final LocalDate date;
		
		/** The metric's total, or its percent rounded to one decimal; null for a percent of none. */
		public final Double value;
		
		/** A count's value, or a percent's numerator. */
		public final Long count;
		
		/** A percent's denominator; null for a count. */
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
	 * The days the range's points are as of, earliest first: the end, then the last day of each earlier
	 * period while it is not before the start.
	 */
	public static List<LocalDate> pointDates(LocalDate start, LocalDate end, Period period) {
		LinkedList<LocalDate> dates = new LinkedList<LocalDate>();
		for (LocalDate day = end; !day.isBefore(start); day = period.previous(day)) {
			dates.addFirst(day);
		}
		return dates;
	}
	
	/**
	 * Every metric in points of the period over the range, of a clinic or of every clinic when it is
	 * null, from one evaluation of the report. A day the report gives no row for counts nobody.
	 */
	public Map<String, List<Point>> calculate(Location clinic, LocalDate start, LocalDate end, Period period) {
		List<LocalDate> days = pointDates(start, end, period);
		Map<LocalDate, DataSetRow> rows = rowsOf(clinic, start, end, period);
		Map<LocalDate, Long> adherent = adherentOf(rows, days);
		Map<String, List<Point>> series = new LinkedHashMap<String, List<Point>>();
		for (String metric : METRICS) {
			String denominator = PERCENT_OF.get(metric);
			List<Point> points = new ArrayList<Point>();
			for (LocalDate day : days) {
				DataSetRow row = rows.get(day);
				long numerator = ADHERENT.equals(metric) ? adherent.get(day) : number(row, metric);
				if (denominator == null) {
					points.add(new Point(day, Double.valueOf(numerator), Long.valueOf(numerator), null));
				} else {
					long total = number(row, denominator);
					Double value = total == 0 ? null : Double.valueOf(Math.round(1000.0 * numerator / total) / 10.0);
					points.add(new Point(day, value, Long.valueOf(numerator), Long.valueOf(total)));
				}
			}
			series.put(metric, points);
		}
		return series;
	}
	
	/** The report's rows of the clinic, or of every clinic, by the day each is as of. */
	private Map<LocalDate, DataSetRow> rowsOf(Location clinic, LocalDate start, LocalDate end, Period period) {
		String wanted = clinic == null ? "" : clinic.getUuid();
		Map<LocalDate, DataSetRow> rows = new HashMap<LocalDate, DataSetRow>();
		for (DataSetRow row : evaluate(start, end, period)) {
			Object named = row.getColumnValue(CLINIC_COLUMN);
			if (!wanted.equals(named == null ? "" : named.toString())) {
				continue;
			}
			Object day = row.getColumnValue(DATE_COLUMN);
			if (day == null) {
				throw new APIException("The run chart report has a row with no " + DATE_COLUMN);
			}
			for (String column : NUMBER_COLUMNS) {
				if (!(row.getColumnValue(column) instanceof Number)) {
					throw new APIException("The run chart report has no number in its column " + column);
				}
			}
			rows.put(toLocalDate(day), row);
		}
		return rows;
	}
	
	/**
	 * How many of each day's listed BPG patients were adherent on it, from one replay of each history.
	 */
	private static Map<LocalDate, Long> adherentOf(Map<LocalDate, DataSetRow> rows, List<LocalDate> days) {
		Map<LocalDate, Set<Integer>> listed = new HashMap<LocalDate, Set<Integer>>();
		Set<Integer> everyone = new LinkedHashSet<Integer>();
		for (LocalDate day : days) {
			Set<Integer> ids = patientIds(rows.get(day));
			listed.put(day, ids);
			everyone.addAll(ids);
		}
		Map<Integer, Map<LocalDate, Double>> adherence = everyone.isEmpty()
		        ? Collections.<Integer, Map<LocalDate, Double>> emptyMap()
		        : AdherenceSeries.adherenceOn(everyone, new TreeSet<LocalDate>(days));
		Map<LocalDate, Long> adherent = new HashMap<LocalDate, Long>();
		for (LocalDate day : days) {
			long count = 0;
			for (Integer id : listed.get(day)) {
				Double value = adherence.containsKey(id) ? adherence.get(id).get(day) : null;
				if (value != null && value >= 0.8) {
					count++;
				}
			}
			adherent.put(day, Long.valueOf(count));
		}
		return adherent;
	}
	
	private static Set<Integer> patientIds(DataSetRow row) {
		Set<Integer> ids = new LinkedHashSet<Integer>();
		if (row == null) {
			return ids;
		}
		Object value = row.getColumnValue(BPG_COLUMN);
		for (String id : StringUtils.split(value == null ? "" : value.toString(), ',')) {
			if (StringUtils.isNotBlank(id)) {
				ids.add(Integer.valueOf(id.trim()));
			}
		}
		if (ids.size() != number(row, BPG_COUNT_COLUMN)) {
			throw new APIException("The run chart report lists " + ids.size() + " BPG patients in a row whose "
			        + BPG_COUNT_COLUMN + " is " + number(row, BPG_COUNT_COLUMN) + "; GROUP_CONCAT cuts a long list short");
		}
		return ids;
	}
	
	private static long number(DataSetRow row, String column) {
		return row == null ? 0 : ((Number) row.getColumnValue(column)).longValue();
	}
	
	private DataSet evaluate(LocalDate start, LocalDate end, Period period) {
		String uuid = Context.getAdministrationService().getGlobalProperty(GP_REPORT);
		ReportDefinitionService reports = Context.getService(ReportDefinitionService.class);
		ReportDefinition report = StringUtils.isBlank(uuid) ? null : reports.getDefinitionByUuid(uuid.trim());
		if (report == null) {
			throw new APIException("No report has the uuid " + GP_REPORT + " names: " + uuid);
		}
		EvaluationContext context = new EvaluationContext();
		context.addParameterValue("startDate", Date.valueOf(start));
		context.addParameterValue("endDate", Date.valueOf(end));
		context.addParameterValue("points", period.name().toLowerCase());
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
	
	private static LocalDate toLocalDate(Object value) {
		if (value instanceof java.sql.Date) {
			return ((java.sql.Date) value).toLocalDate();
		}
		if (value instanceof java.util.Date) {
			return new java.sql.Date(((java.util.Date) value).getTime()).toLocalDate();
		}
		if (value instanceof LocalDate) {
			return (LocalDate) value;
		}
		return LocalDate.parse(value.toString().substring(0, 10));
	}
}
