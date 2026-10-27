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

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.openmrs.Location;
import org.openmrs.User;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.clinics.UserClinics;
import org.openmrs.module.actcore.screening.ScreeningCounts;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.openmrs.module.webservices.rest.web.response.IllegalRequestException;
import org.openmrs.module.webservices.rest.web.v1_0.controller.BaseRestController;
import org.openmrs.util.PrivilegeConstants;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * A clinic's monthly screening counts, read and saved, and their sums over clinics and months for
 * the Dashboard; a site administrator works with the clinics on their account only.
 */
@Controller
@RequestMapping("/rest/" + RestConstants.VERSION_1 + "/actcore/screeningcounts")
public class ScreeningCountController extends BaseRestController {
	
	/** Opens the Screening counts page, where they are read. */
	public static final String READ_PRIVILEGE = "App: act.screeningCounts";
	
	/** The Dashboard's, whose screening section reads them. */
	public static final String DASHBOARD_PRIVILEGE = RunChartController.READ_PRIVILEGE;
	
	public static final String ENTER_PRIVILEGE = "Task: act.enterScreeningCounts";
	
	private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("uuuu-MM");
	
	private final ScreeningCounts screeningCounts = new ScreeningCounts();
	
	@RequestMapping(method = RequestMethod.GET)
	@ResponseBody
	public SimpleObject get(@RequestParam("clinic") String clinic, @RequestParam("month") String month) {
		requireReader();
		Location location = clinicOf(clinic);
		YearMonth yearMonth = month("month", month, null);
		return describe(location, yearMonth, screeningCounts.get(location, yearMonth));
	}
	
	@RequestMapping(method = RequestMethod.POST)
	@ResponseBody
	public SimpleObject save(@RequestParam("clinic") String clinic, @RequestParam("month") String month,
	        @RequestBody Map<String, Object> body) {
		requirePrivilege(ENTER_PRIVILEGE);
		Location location = clinicOf(clinic);
		YearMonth yearMonth = month("month", month, null);
		Object notes = body == null ? null : body.get("notes");
		try {
			screeningCounts.save(location, yearMonth, counts(body), notes == null ? null : notes.toString());
		}
		catch (IllegalArgumentException e) {
			throw new IllegalRequestException(e.getMessage());
		}
		return describe(location, yearMonth, screeningCounts.get(location, yearMonth));
	}
	
	/**
	 * The sums from startMonth to endMonth, both included, by default the twelve months to this one, of
	 * the clinics given or of every clinic.
	 */
	@RequestMapping(value = "/summary", method = RequestMethod.GET)
	@ResponseBody
	public SimpleObject summary(@RequestParam(value = "clinic", required = false) String[] clinics,
	        @RequestParam(value = "startMonth", required = false) String startMonth,
	        @RequestParam(value = "endMonth", required = false) String endMonth) {
		requireReader();
		YearMonth end = month("endMonth", endMonth, YearMonth.now());
		YearMonth start = month("startMonth", startMonth, end.minusMonths(11));
		if (start.isAfter(end)) {
			throw new IllegalRequestException("startMonth is after endMonth");
		}
		ScreeningCounts.Summary summary = screeningCounts.summary(locationIds(clinics), start, end);
		SimpleObject settings = new SimpleObject();
		for (String setting : ScreeningCounts.SETTINGS) {
			settings.add(setting, summary.sums.get(setting + "_screened"));
		}
		return new SimpleObject().add("startMonth", start.format(MONTH)).add("endMonth", end.format(MONTH))
		        .add("months", summary.months).add("screened", summary.screened()).add("females", summary.total("females"))
		        .add("positive", summary.positive()).add("negative", summary.negative())
		        .add("children", summary.total("children")).add("adolescents", summary.total("adolescents"))
		        .add("adults", summary.total("adults")).add("settings", settings);
	}
	
	private static SimpleObject describe(Location clinic, YearMonth month, ScreeningCounts.Month counts) {
		return new SimpleObject().add("clinic", clinic.getUuid()).add("month", month.format(MONTH))
		        .add("saved", counts.saved).add("counts", counts.counts).add("notes", counts.notes);
	}
	
	private static Map<String, Integer> counts(Map<String, Object> body) {
		Object given = body == null ? null : body.get("counts");
		if (!(given instanceof Map)) {
			throw new IllegalRequestException("The body needs counts, by column");
		}
		Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
		for (Map.Entry<?, ?> entry : ((Map<?, ?>) given).entrySet()) {
			Object value = entry.getValue();
			if (value == null) {
				continue;
			}
			if (!(value instanceof Number) || ((Number) value).doubleValue() != Math.rint(((Number) value).doubleValue())) {
				throw new IllegalRequestException(entry.getKey() + " must be a whole number");
			}
			if (Math.abs(((Number) value).doubleValue()) > Integer.MAX_VALUE) {
				throw new IllegalRequestException(entry.getKey() + " is more than " + Integer.MAX_VALUE);
			}
			counts.put(String.valueOf(entry.getKey()), ((Number) value).intValue());
		}
		return counts;
	}
	
	/**
	 * The location ids to sum: those given, or null for every clinic; for a site administrator only the
	 * clinics on their account among them, which may leave none. A uuid given that names no location is
	 * a bad request, unless it is on the site administrator's account.
	 */
	private static Collection<Integer> locationIds(String[] clinics) {
		Set<String> uuids = new LinkedHashSet<String>();
		if (clinics != null) {
			for (String clinic : clinics) {
				if (StringUtils.isNotBlank(clinic)) {
					uuids.add(clinic.trim());
				}
			}
		}
		User user = Context.getAuthenticatedUser();
		boolean limited = UserClinics.isLimited(user);
		Set<String> own = limited ? UserClinics.of(user) : Collections.<String> emptySet();
		for (String uuid : uuids) {
			if (!own.contains(uuid) && location(uuid) == null) {
				throw new IllegalRequestException("No location has the uuid " + uuid);
			}
		}
		if (limited) {
			if (uuids.isEmpty()) {
				uuids.addAll(own);
			} else {
				uuids.retainAll(own);
			}
		} else if (uuids.isEmpty()) {
			return null;
		}
		List<Integer> ids = new ArrayList<Integer>();
		for (String uuid : uuids) {
			Location location = location(uuid);
			if (location != null) {
				ids.add(location.getLocationId());
			}
		}
		return ids;
	}
	
	/** The clinic, which a site administrator must have on their account. */
	private static Location clinicOf(String uuid) {
		Location location = StringUtils.isBlank(uuid) ? null : location(uuid.trim());
		if (location == null) {
			throw new IllegalRequestException("No location has the uuid " + uuid);
		}
		User user = Context.getAuthenticatedUser();
		if (UserClinics.isLimited(user) && !UserClinics.of(user).contains(location.getUuid())) {
			throw new APIAuthenticationException(location.getName() + " is not one of your clinics");
		}
		return location;
	}
	
	private static Location location(String uuid) {
		try {
			Context.addProxyPrivilege(PrivilegeConstants.GET_LOCATIONS);
			return Context.getLocationService().getLocationByUuid(uuid);
		}
		finally {
			Context.removeProxyPrivilege(PrivilegeConstants.GET_LOCATIONS);
		}
	}
	
	private static YearMonth month(String name, String value, YearMonth fallback) {
		if (StringUtils.isBlank(value)) {
			if (fallback == null) {
				throw new IllegalRequestException(name + " must be a month as YYYY-MM");
			}
			return fallback;
		}
		try {
			return YearMonth.parse(value.trim(), MONTH);
		}
		catch (DateTimeParseException e) {
			throw new IllegalRequestException(name + " must be a month as YYYY-MM");
		}
	}
	
	private static void requireReader() {
		if (!Context.hasPrivilege(READ_PRIVILEGE) && !Context.hasPrivilege(DASHBOARD_PRIVILEGE)
		        && !Context.hasPrivilege(ENTER_PRIVILEGE)) {
			throw new APIAuthenticationException(
			        "Privilege required: " + READ_PRIVILEGE + ", " + DASHBOARD_PRIVILEGE + " or " + ENTER_PRIVILEGE);
		}
	}
	
	private static void requirePrivilege(String privilege) {
		if (!Context.hasPrivilege(privilege)) {
			throw new APIAuthenticationException("Privilege required: " + privilege);
		}
	}
}
