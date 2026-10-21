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
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.openmrs.Location;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.Daemon;
import org.openmrs.module.DaemonToken;
import org.openmrs.module.actcore.ActCoreActivator;
import org.openmrs.module.actcore.runchart.RunChartSnapshotTask;
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
 * A run chart metric's daily values over a date range, of a clinic or of every clinic, and the
 * snapshot taken on demand.
 */
@Controller
@RequestMapping("/rest/" + RestConstants.VERSION_1 + "/actcore")
public class RunChartController extends BaseRestController {
	
	/** The Dashboard's own privilege in the distribution, which the run charts sit on. */
	public static final String READ_PRIVILEGE = "App: act.reports";
	
	/** Taking the snapshot now is the flag refresh's privilege, the instance administrator's. */
	public static final String RUN_PRIVILEGE = RefreshController.PRIVILEGE;
	
	/**
	 * ACT 2.0's default range is the year to today. A clinic given by uuid reads that clinic's rows,
	 * which count each of its patients once; with none, the rows of every clinic.
	 */
	@RequestMapping(value = "/runchart", method = RequestMethod.GET)
	@ResponseBody
	public SimpleObject get(@RequestParam("metric") String metric,
	        @RequestParam(value = "clinic", required = false) String clinic,
	        @RequestParam(value = "startDate", required = false) String startDate,
	        @RequestParam(value = "endDate", required = false) String endDate) {
		requirePrivilege(READ_PRIVILEGE);
		if (!RunCharts.METRICS.contains(metric)) {
			throw new IllegalRequestException("metric must be one of " + RunCharts.METRICS);
		}
		LocalDate end = date("endDate", endDate, LocalDate.now());
		LocalDate start = date("startDate", startDate, end.minusYears(1));
		if (start.isAfter(end)) {
			throw new IllegalRequestException("startDate is after endDate");
		}
		RunCharts runCharts = new RunCharts();
		List<SimpleObject> points = new ArrayList<SimpleObject>();
		for (RunCharts.Point point : runCharts.series(metric, location(clinic), start, end)) {
			points.add(new SimpleObject().add("date", point.date.toString()).add("value", point.value));
		}
		return new SimpleObject().add("metric", metric).add("startDate", start.toString()).add("endDate", end.toString())
		        .add("points", points).add("lastSnapshot", lastSnapshot(runCharts));
	}
	
	/**
	 * Runs as the scheduler's daemon user, as the daily run does: the report reads every patient, which
	 * the caller's own privileges need not allow.
	 */
	@RequestMapping(value = "/runchart", method = RequestMethod.POST)
	@ResponseBody
	public SimpleObject snapshot() {
		requirePrivilege(RUN_PRIVILEGE);
		DaemonToken token = ActCoreActivator.getDaemonToken();
		if (token == null) {
			throw new APIException("ACT Core has not started, so it cannot take the run chart snapshot");
		}
		final Integer[] rows = new Integer[1];
		final RuntimeException[] failure = new RuntimeException[1];
		Daemon.runInDaemonThreadAndWait(new Runnable() {
			
			@Override
			public void run() {
				try {
					rows[0] = new RunChartSnapshotTask().runIfIdle();
				}
				catch (RuntimeException e) {
					failure[0] = e;
				}
			}
		}, token);
		if (failure[0] != null) {
			throw new APIException("The run chart snapshot failed", failure[0]);
		}
		return new SimpleObject().add("snapshotTaken", rows[0] != null).add("rows", rows[0]).add("lastSnapshot",
		    lastSnapshot(new RunCharts()));
	}
	
	private static String lastSnapshot(RunCharts runCharts) {
		LocalDate last = runCharts.lastSnapshot();
		return last == null ? null : last.toString();
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
