/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.api.impl;

import java.sql.Date;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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
import org.openmrs.api.ValidationException;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.ActCoreConstants;
import org.openmrs.module.actcore.api.AdherenceService;
import org.openmrs.module.reporting.dataset.DataSet;
import org.openmrs.module.reporting.dataset.DataSetRow;
import org.openmrs.module.reporting.evaluation.EvaluationContext;
import org.openmrs.module.reporting.evaluation.EvaluationException;
import org.openmrs.module.reporting.report.ReportData;
import org.openmrs.module.reporting.report.definition.ReportDefinition;
import org.openmrs.module.reporting.report.definition.service.ReportDefinitionService;

/**
 * Calculates ACT 2.0's run charts when they are asked for, without storing them. Each request
 * evaluates the report that actcore.runCharts.report names once, and replays the histories of the
 * patients on BPG to each point's day to count those who were adherent.
 */
public class RunCharts {
	
	/** The report's column naming the clinic a row is of; it is empty in the row of every clinic. */
	static final String CLINIC_COLUMN = "clinic_uuid";
	
	/** The report's column naming the day a row's figures are as of. */
	static final String DATE_COLUMN = "point_date";
	
	/**
	 * The report's column that lists, separated by commas, the ids of the active patients on a BPG
	 * regimen in force as of the day, whose adherence the point replays.
	 */
	static final String BPG_COLUMN = "bpg_patient_ids";
	
	/**
	 * The report's column counting the patients that the BPG list names. GROUP_CONCAT cuts a long list
	 * short without an error, so a row whose list does not hold that many ids is refused rather than
	 * read.
	 */
	static final String BPG_COUNT_COLUMN = "active_registrants_on_bpg";
	
	/**
	 * The metric that the report does not give, which is counted here from the BPG list and the replay:
	 * the patients on BPG whose adherence on the day was at least 80%.
	 */
	static final String ADHERENT = "active_registrants_adherent";
	
	/** The columns of ACT 2.0's runchart_record that the report gives as numbers. */
	public static final List<String> COLUMNS = Collections.unmodifiableList(Arrays.asList("total_registrants",
	    "active_registrants", "active_registrants_seen_last_7_months", "active_registrants_prescribed_prophylaxis",
	    "consultation_visit_total", "consultation_visits_with_echocardiogram_resulted",
	    "consultation_visits_with_intervention", "interventions_completed"));
	
	/** The report's columns that must hold a number in each row of the clinic charted. */
	private static final List<String> NUMBER_COLUMNS;
	
	static {
		List<String> columns = new ArrayList<String>(COLUMNS);
		columns.add(BPG_COUNT_COLUMN);
		NUMBER_COLUMNS = Collections.unmodifiableList(columns);
	}
	
	/**
	 * The metrics ACT 2.0 charted as a percent, each mapped to the column it is a percent of, as ACT
	 * 2.0's dashboard_query.py does.
	 */
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
	
	/**
	 * ACT 2.0's eight run chart metrics, in the order they are charted. consultation_visit_total is not
	 * among them, as it serves only as a denominator.
	 */
	public static final List<String> METRICS = Collections.unmodifiableList(
	    Arrays.asList("total_registrants", "active_registrants", "active_registrants_seen_last_7_months",
	        "active_registrants_prescribed_prophylaxis", ADHERENT, "consultation_visits_with_echocardiogram_resulted",
	        "consultation_visits_with_intervention", "interventions_completed"));
	
	/**
	 * The most points one request may calculate, since MariaDB stops a recursive query at 1000
	 * iterations.
	 */
	public static final int MOST_POINTS = 1000;
	
	/** The longest range, in days, that may be charted in daily points. */
	public static final int MOST_DAILY_DAYS = 92;
	
	/**
	 * How a range is cut into points. Each point gives the figures as of the last day of its period.
	 */
	public enum Period {
		
		DAY,
		WEEK,
		MONTH;
		
		/**
		 * Gets the period a request names, which is a week when it names none.
		 *
		 * @param name day, week or month, ignoring case, or blank
		 * @return the period; WEEK for a blank name
		 * @throws ValidationException when the name is none of them
		 */
		public static Period of(String name) {
			if (StringUtils.isBlank(name)) {
				return WEEK;
			}
			for (Period period : values()) {
				if (period.name().equalsIgnoreCase(name.trim())) {
					return period;
				}
			}
			throw refusal("points must be day, week or month");
		}
		
		/**
		 * Gets the last day of the period before the one holding the day, stepping back as the report's SQL
		 * does. A week ends on a Sunday.
		 */
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
	
	/** One point of a metric's series. */
	public static class Point {
		
		/**
		 * The day the figure is as of: the last day of the point's period, or the end of the range for the
		 * last point.
		 */
		public final LocalDate date;
		
		/**
		 * The figure charted: the count for a count, or the percent rounded to one decimal for a percent.
		 * It is null for a percent whose denominator is zero.
		 */
		public final Double value;
		
		/** The count for a count, or the numerator for a percent. */
		public final Long count;
		
		/** The denominator for a percent, or null for a count. */
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
	 * Gets the days that the range's points are as of, earliest first. The last point is the end of the
	 * range, and each earlier point is the last day of an earlier period, back to the first such day
	 * that is not before the start.
	 *
	 * @param start the range's first day
	 * @param end the range's last day
	 * @param period the period each point closes
	 * @return the points' days; empty when the end is before the start
	 * @throws ValidationException when the points are daily over a range longer than
	 *             {@link #MOST_DAILY_DAYS} days, or the range has more than {@link #MOST_POINTS} points
	 */
	public static List<LocalDate> pointDates(LocalDate start, LocalDate end, Period period) {
		if (period == Period.DAY && ChronoUnit.DAYS.between(start, end) > MOST_DAILY_DAYS) {
			throw refusal("points=day is for a range of up to " + MOST_DAILY_DAYS + " days; ask for weeks or months");
		}
		LinkedList<LocalDate> dates = new LinkedList<LocalDate>();
		for (LocalDate day = end; !day.isBefore(start); day = period.previous(day)) {
			dates.addFirst(day);
		}
		if (dates.size() > MOST_POINTS) {
			throw refusal("The range has " + dates.size() + " points; at most " + MOST_POINTS
			        + " can be charted, so shorten it or ask for longer periods");
		}
		return dates;
	}
	
	/**
	 * Calculates every metric's points over the range, one point per period, for a clinic or for every
	 * clinic, from one evaluation of the report. At a point where the report gives the clinic no row,
	 * every count is zero and every percent is null.
	 *
	 * @param clinic the clinic, or null for every clinic
	 * @param start the range's first day
	 * @param end the range's last day
	 * @param period the period each point closes
	 * @return each of {@link #METRICS}' points, earliest first, by metric in that order
	 * @throws ValidationException when the points are daily over a range longer than
	 *             {@link #MOST_DAILY_DAYS} days, or the range has more than {@link #MOST_POINTS} points
	 * @throws APIException when {@value ActCoreConstants#GP_RUN_CHARTS_REPORT} names no report, or the
	 *             report cannot be evaluated or gives rows ACT Core cannot read
	 */
	public Map<String, List<Point>> calculate(Location clinic, LocalDate start, LocalDate end, Period period) {
		List<LocalDate> days = pointDates(start, end, period);
		Map<LocalDate, DataSetRow> rows = rowsOf(clinic, start, end, period, new HashSet<LocalDate>(days));
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
	
	/**
	 * Evaluates the report and returns its rows of the clinic, or of every clinic, by day. It refuses
	 * the report's rows when one has no date, a date that is not a point, or no number in a number
	 * column, when two rows of the clinic share a day, and when a point has no row of every clinic.
	 */
	private Map<LocalDate, DataSetRow> rowsOf(Location clinic, LocalDate start, LocalDate end, Period period,
	        Set<LocalDate> days) {
		String wanted = clinic == null ? "" : clinic.getUuid();
		Map<LocalDate, DataSetRow> rows = new HashMap<LocalDate, DataSetRow>();
		Set<LocalDate> everyClinic = new HashSet<LocalDate>();
		for (DataSetRow row : evaluate(start, end, period)) {
			Object dated = row.getColumnValue(DATE_COLUMN);
			if (dated == null) {
				throw new APIException("The run chart report has a row with no " + DATE_COLUMN);
			}
			LocalDate day = toLocalDate(dated);
			if (!days.contains(day)) {
				throw new APIException("The run chart report has a row whose " + DATE_COLUMN + " " + day
				        + " is not a point of " + start + " to " + end + " in " + period.name().toLowerCase()
				        + "s, so the report's point dates and ACT Core's disagree");
			}
			Object named = row.getColumnValue(CLINIC_COLUMN);
			String rowClinic = named == null ? "" : named.toString();
			if (rowClinic.isEmpty()) {
				everyClinic.add(day);
			}
			if (!wanted.equals(rowClinic)) {
				continue;
			}
			for (String column : NUMBER_COLUMNS) {
				if (!(row.getColumnValue(column) instanceof Number)) {
					throw new APIException("The run chart report has no number in its column " + column);
				}
			}
			if (rows.put(day, row) != null) {
				throw new APIException("The run chart report has more than one row for "
				        + (wanted.isEmpty() ? "every clinic" : "clinic " + wanted) + " on " + DATE_COLUMN + " " + day);
			}
		}
		for (LocalDate day : new TreeSet<LocalDate>(days)) {
			if (!everyClinic.contains(day)) {
				throw new APIException("The run chart report has no row of every clinic for the point " + day
				        + ", so it left the point out");
			}
		}
		return rows;
	}
	
	/**
	 * Counts, for each day, how many of the BPG patients the day's row lists were adherent on that day,
	 * replaying each patient's history once for all the days.
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
		        : Context.getService(AdherenceService.class).getAdherenceOn(everyone, new TreeSet<LocalDate>(days));
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
		String uuid = Context.getAdministrationService().getGlobalProperty(ActCoreConstants.GP_RUN_CHARTS_REPORT);
		ReportDefinitionService reports = Context.getService(ReportDefinitionService.class);
		ReportDefinition report = StringUtils.isBlank(uuid) ? null : reports.getDefinitionByUuid(uuid.trim());
		if (report == null) {
			throw new APIException("No report has the uuid " + ActCoreConstants.GP_RUN_CHARTS_REPORT + " names: " + uuid);
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
	 * Gets the median of the points' values: the middle value, or the mean of the middle two when there
	 * is an even number of them. Points without a value are ignored.
	 *
	 * @param points the points
	 * @return the median, or null when no point has a value
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
	
	private static ValidationException refusal(String message) {
		return Rows.refusal("runChart", message);
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
