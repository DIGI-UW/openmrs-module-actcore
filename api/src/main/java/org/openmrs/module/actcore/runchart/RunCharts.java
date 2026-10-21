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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
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
	
	public static class Point {
		
		public final LocalDate date;
		
		/** The metric's total, or its percent rounded to one decimal; null for a percent of none. */
		public final Double value;
		
		Point(LocalDate date, Double value) {
			this.date = date;
			this.value = value;
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
					if (!values.isEmpty()) {
						admin.executeSQL(
						    "insert into " + TABLE + " (record_date, location_id, " + StringUtils.join(COLUMNS, ", ")
						            + ", date_created) values " + StringUtils.join(values, ", "),
						    false);
					}
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
			values.add(String.valueOf(value instanceof Number ? ((Number) value).longValue() : 0L));
		}
		values.add("current_timestamp");
		return "(" + StringUtils.join(values, ", ") + ")";
	}
	
	/**
	 * The metric each day in the range, of the clinic, or of every clinic when it is null: a total, or
	 * a percent of its denominator over the clinic's rows, as ACT 2.0's get_runchart_stats.
	 */
	public List<Point> series(String metric, Location clinic, LocalDate start, LocalDate end) {
		if (!METRICS.contains(metric)) {
			throw new IllegalArgumentException("No run chart metric is named " + metric);
		}
		String denominator = PERCENT_OF.get(metric);
		String value = denominator == null ? "sum(" + metric + ")"
		        : "case when sum(" + denominator + ") = 0 then null else round(100.0 * sum(" + metric + ") / sum("
		                + denominator + "), 1) end";
		String where = " where record_date >= '" + Date.valueOf(start) + "' and record_date <= '" + Date.valueOf(end)
		        + "' and location_id " + (clinic == null ? "is null" : "= " + clinic.getLocationId());
		List<List<Object>> rows;
		try {
			Context.addProxyPrivilege(PrivilegeConstants.SQL_LEVEL_ACCESS);
			rows = Context.getAdministrationService().executeSQL(
			    "select record_date, " + value + " from " + TABLE + where + " group by record_date order by record_date",
			    true);
		}
		finally {
			Context.removeProxyPrivilege(PrivilegeConstants.SQL_LEVEL_ACCESS);
		}
		List<Point> points = new ArrayList<Point>();
		for (List<Object> row : rows) {
			Object v = row.get(1);
			points.add(new Point(toLocalDate(row.get(0)), v == null ? null : ((Number) v).doubleValue()));
		}
		return points;
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
