/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.web;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.openmrs.Location;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.Daemon;
import org.openmrs.module.DaemonToken;
import org.openmrs.module.actcore.ActCoreActivator;
import org.openmrs.module.actcore.runchart.RunCharts;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.openmrs.module.webservices.rest.web.response.IllegalRequestException;
import org.openmrs.module.webservices.rest.web.v1_0.controller.BaseRestController;
import org.openmrs.util.PrivilegeConstants;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * The run chart metrics over a date range in days, weeks or months, each point calculated when
 * asked from the records as they are now, with the median of each metric's points, of a clinic or
 * of every clinic.
 */
@Controller
@RequestMapping("/rest/" + RestConstants.VERSION_1 + "/actcore")
public class RunChartController extends BaseRestController {
	
	/** The Dashboard's own privilege in the distribution, which the run charts sit on. */
	public static final String READ_PRIVILEGE = "App: act.dashboard";
	
	/**
	 * The year to today in weeks unless the request says day or month, of a clinic's rows or, with none
	 * given, of every clinic; ACT limits the clinics a user is offered on the client, not here. Every
	 * metric is answered under metrics, and the one named, if any, under points and median too.
	 */
	@RequestMapping(value = "/runchart", method = RequestMethod.GET)
	@ResponseBody
	public SimpleObject get(@RequestParam(value = "metric", required = false) String metric,
	        @RequestParam(value = "clinic", required = false) String clinic,
	        @RequestParam(value = "startDate", required = false) String startDate,
	        @RequestParam(value = "endDate", required = false) String endDate,
	        @RequestParam(value = "points", required = false) String points) {
		requirePrivilege(READ_PRIVILEGE);
		if (StringUtils.isNotBlank(metric) && !RunCharts.METRICS.contains(metric)) {
			throw new IllegalRequestException("metric must be one of " + RunCharts.METRICS);
		}
		LocalDate end = date("endDate", endDate, LocalDate.now());
		LocalDate start = date("startDate", startDate, end.minusYears(1));
		if (start.isAfter(end)) {
			throw new IllegalRequestException("startDate is after endDate");
		}
		RunCharts.Period period;
		try {
			period = RunCharts.Period.of(points);
		}
		catch (IllegalArgumentException e) {
			throw new IllegalRequestException(e.getMessage());
		}
		if (period == RunCharts.Period.DAY && ChronoUnit.DAYS.between(start, end) > RunCharts.MOST_DAILY_DAYS) {
			throw new IllegalRequestException(
			        "points=day is for a range of up to " + RunCharts.MOST_DAILY_DAYS + " days; ask for weeks or months");
		}
		int count = RunCharts.pointDates(start, end, period).size();
		if (count > RunCharts.MOST_POINTS) {
			throw new IllegalRequestException("The range has " + count + " points; at most " + RunCharts.MOST_POINTS
			        + " can be charted, so shorten it or ask for longer periods");
		}
		Map<String, List<RunCharts.Point>> series = calculate(location(clinic), start, end, period);
		SimpleObject metrics = new SimpleObject();
		for (Map.Entry<String, List<RunCharts.Point>> entry : series.entrySet()) {
			metrics.add(entry.getKey(), new SimpleObject().add("points", describe(entry.getValue())).add("median",
			    RunCharts.median(entry.getValue())));
		}
		SimpleObject response = new SimpleObject().add("startDate", start.toString()).add("endDate", end.toString())
		        .add("period", period.name().toLowerCase());
		if (StringUtils.isNotBlank(metric)) {
			response.add("metric", metric).add("points", describe(series.get(metric))).add("median",
			    RunCharts.median(series.get(metric)));
		}
		return response.add("metrics", metrics);
	}
	
	/**
	 * Runs as the scheduler's daemon user: the report reads every patient and is held to its own report
	 * privilege, which the caller's Dashboard privilege need not come with.
	 */
	private static Map<String, List<RunCharts.Point>> calculate(final Location clinic, final LocalDate start,
	        final LocalDate end, final RunCharts.Period period) {
		DaemonToken token = ActCoreActivator.getDaemonToken();
		if (token == null) {
			throw new APIException("ACT Core has not started, so it cannot calculate the run chart");
		}
		final List<Map<String, List<RunCharts.Point>>> calculated = new ArrayList<Map<String, List<RunCharts.Point>>>();
		final RuntimeException[] failure = new RuntimeException[1];
		Daemon.runInDaemonThreadAndWait(new Runnable() {
			
			@Override
			public void run() {
				try {
					calculated.add(new RunCharts().calculate(clinic, start, end, period));
				}
				catch (RuntimeException e) {
					failure[0] = e;
				}
			}
		}, token);
		if (failure[0] != null) {
			throw new APIException("The run chart could not be calculated", failure[0]);
		}
		return calculated.get(0);
	}
	
	private static List<SimpleObject> describe(List<RunCharts.Point> points) {
		List<SimpleObject> values = new ArrayList<SimpleObject>();
		for (RunCharts.Point point : points) {
			values.add(new SimpleObject().add("date", point.date.toString()).add("value", point.value)
			        .add("count", point.count).add("total", point.total));
		}
		return values;
	}
	
	private static LocalDate date(String name, String value, LocalDate fallback) {
		if (StringUtils.isBlank(value)) {
			return fallback;
		}
		try {
			return LocalDate.parse(value.trim());
		}
		catch (DateTimeParseException e) {
			throw new IllegalRequestException(name + " must be a date as YYYY-MM-DD");
		}
	}
	
	private static Location location(String uuid) {
		if (StringUtils.isBlank(uuid)) {
			return null;
		}
		Location location;
		try {
			Context.addProxyPrivilege(PrivilegeConstants.GET_LOCATIONS);
			location = Context.getLocationService().getLocationByUuid(uuid.trim());
		}
		finally {
			Context.removeProxyPrivilege(PrivilegeConstants.GET_LOCATIONS);
		}
		if (location == null) {
			throw new IllegalRequestException("No location has the uuid " + uuid);
		}
		return location;
	}
	
	private static void requirePrivilege(String privilege) {
		if (!Context.hasPrivilege(privilege)) {
			throw new APIAuthenticationException("Privilege required: " + privilege);
		}
	}
}
