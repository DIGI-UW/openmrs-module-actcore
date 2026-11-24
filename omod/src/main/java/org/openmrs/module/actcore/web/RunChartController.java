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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.openmrs.Location;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.APIException;
import org.openmrs.api.ValidationException;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.ActCorePrivileges;
import org.openmrs.module.actcore.api.RunChartService;
import org.openmrs.module.actcore.runchart.RunCharts;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.openmrs.module.webservices.rest.web.response.IllegalRequestException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * The run chart metrics of a clinic or of every clinic over a range in days, weeks or months, each
 * point calculated when asked from the records as they are now, with each metric's median.
 */
@Controller
@RequestMapping("/rest/" + RestConstants.VERSION_1 + "/actcore")
public class RunChartController extends ActCoreRestController {
	
	/**
	 * Gets the run charts of a clinic, or of every clinic, over a range that defaults to the year up to
	 * today, in weekly points unless the request asks for daily or monthly ones. Every metric is given
	 * under {@code metrics}, and the metric the request names, if any, is also given at the top level
	 * as {@code points} and {@code median}.
	 *
	 * @param metric one of {@link RunCharts#METRICS} to give at the top level, or blank for none
	 * @param clinic the clinic's location uuid, or blank for every clinic
	 * @param startDate the range's first day as YYYY-MM-DD; blank for a year before the end
	 * @param endDate the range's last day as YYYY-MM-DD; blank for today
	 * @param points day, week or month; blank for week
	 * @return the range, the period and each metric's points and median, with the named metric's too; a
	 *         median is null when no point has a value
	 * @throws APIAuthenticationException when the user lacks {@link ActCorePrivileges#DASHBOARD}
	 * @throws IllegalRequestException when the metric, a date or the clinic is not recognised, or the
	 *             start is after the end
	 * @throws ValidationException when the points are not recognised, a daily range is longer than
	 *             {@link RunCharts#MOST_DAILY_DAYS} days, or the range has more than
	 *             {@link RunCharts#MOST_POINTS} points
	 * @throws APIException when the module has not started or the report cannot be calculated
	 */
	@RequestMapping(value = "/runchart", method = RequestMethod.GET)
	@ResponseBody
	public SimpleObject get(@RequestParam(value = "metric", required = false) String metric,
	        @RequestParam(value = "clinic", required = false) String clinic,
	        @RequestParam(value = "startDate", required = false) String startDate,
	        @RequestParam(value = "endDate", required = false) String endDate,
	        @RequestParam(value = "points", required = false) String points) {
		if (StringUtils.isNotBlank(metric) && !RunCharts.METRICS.contains(metric)) {
			throw new IllegalRequestException("metric must be one of " + RunCharts.METRICS);
		}
		LocalDate end = date("endDate", endDate, LocalDate.now());
		LocalDate start = date("startDate", startDate, end.minusYears(1));
		if (start.isAfter(end)) {
			throw new IllegalRequestException("startDate is after endDate");
		}
		RunCharts.Period period = RunCharts.Period.of(points);
		Location location = location(clinic);
		if (StringUtils.isNotBlank(clinic) && location == null) {
			throw new IllegalRequestException("No location has the uuid " + clinic);
		}
		Map<String, List<RunCharts.Point>> series = Context.getService(RunChartService.class).getRunCharts(location, start,
		    end, period);
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
	
	private static List<SimpleObject> describe(List<RunCharts.Point> points) {
		List<SimpleObject> values = new ArrayList<SimpleObject>();
		for (RunCharts.Point point : points) {
			values.add(new SimpleObject().add("date", point.date.toString()).add("value", point.value)
			        .add("count", point.count).add("total", point.total));
		}
		return values;
	}
}
