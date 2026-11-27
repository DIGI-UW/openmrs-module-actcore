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
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.openmrs.Location;
import org.openmrs.User;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.ValidationException;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.ActCorePrivileges;
import org.openmrs.module.actcore.api.ScreeningCountService;
import org.openmrs.module.actcore.model.ScreeningCount;
import org.openmrs.module.actcore.model.ScreeningCountMonth;
import org.openmrs.module.actcore.model.ScreeningCountSummary;
import org.openmrs.module.actcore.utils.UserClinics;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.openmrs.module.webservices.rest.web.response.IllegalRequestException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Serves the monthly screening counts: a clinic's counts for a month, read and saved on the
 * Screening counts page, and their sums over clinics and months for the Dashboard. A site
 * administrator works only with the clinics on their account, which {@link ScreeningCountService}
 * enforces.
 */
@Controller
@RequestMapping("/rest/" + RestConstants.VERSION_1 + "/actcore/screeningcounts")
public class ScreeningCountController extends ActCoreRestController {
	
	private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("uuuu-MM");
	
	/**
	 * Gets the clinic's counts for the month.
	 *
	 * @param clinic the clinic's location uuid
	 * @param month the month as YYYY-MM
	 * @return the clinic, the month, whether it was saved, each column's count, all zero when it was
	 *         not, and the notes
	 * @throws APIAuthenticationException when the user lacks
	 *             {@link ActCorePrivileges#SCREENING_COUNTS}, {@link ActCorePrivileges#DASHBOARD} and
	 *             {@link ActCorePrivileges#ENTER_SCREENING_COUNTS}, or is a site administrator without
	 *             the clinic on their account
	 * @throws IllegalRequestException when the clinic or month is blank or not recognised; a request
	 *             without one of them is answered with 400 before this method runs
	 */
	@RequestMapping(method = RequestMethod.GET)
	@ResponseBody
	public SimpleObject get(@RequestParam("clinic") String clinic, @RequestParam("month") String month) {
		Location location = clinicOf(clinic);
		YearMonth yearMonth = month("month", month, null);
		return describe(location, yearMonth, service().getMonth(location, yearMonth));
	}
	
	/**
	 * Saves the clinic's counts for the month, replacing the counts saved before.
	 *
	 * @param clinic the clinic's location uuid
	 * @param month the month as YYYY-MM
	 * @param body the request body, holding each column's count as {@code counts}, a column not given
	 *            being zero, and optional {@code notes}
	 * @return the month as saved, as {@link #get(String, String)} gives it
	 * @throws APIAuthenticationException when the user lacks
	 *             {@link ActCorePrivileges#ENTER_SCREENING_COUNTS}, or is a site administrator without
	 *             the clinic on their account
	 * @throws IllegalRequestException when the clinic or month is blank or not recognised, the counts
	 *             are missing, or a count is not a whole number; a request without the clinic or month
	 *             is answered with 400 before this method runs
	 * @throws ValidationException when the service refuses the counts, notes or month, saying why
	 */
	@RequestMapping(method = RequestMethod.POST)
	@ResponseBody
	public SimpleObject save(@RequestParam("clinic") String clinic, @RequestParam("month") String month,
	        @RequestBody Map<String, Object> body) {
		Location location = clinicOf(clinic);
		YearMonth yearMonth = month("month", month, null);
		Object notes = body == null ? null : body.get("notes");
		service().saveMonth(location, yearMonth, counts(body), notes == null ? null : notes.toString());
		return describe(location, yearMonth, service().getMonth(location, yearMonth));
	}
	
	/**
	 * Gets the sums of the counts from startMonth to endMonth, both included, of the clinics given or
	 * of every clinic. Without months, the range is the twelve months up to and including this one.
	 *
	 * @param clinics the clinics' location uuids, or none for no clinic filter; a site administrator's
	 *            are limited to the clinics on their account, which none stands for, and one of those
	 *            that names no location any more is skipped
	 * @param startMonth the first month as YYYY-MM; blank for eleven months before the end
	 * @param endMonth the last month as YYYY-MM; blank for this month
	 * @return the months, how many clinic months were saved, the totals screened, female, positive,
	 *         negative and by age group, and the screened by setting
	 * @throws APIAuthenticationException when the user lacks
	 *             {@link ActCorePrivileges#SCREENING_COUNTS}, {@link ActCorePrivileges#DASHBOARD} and
	 *             {@link ActCorePrivileges#ENTER_SCREENING_COUNTS}
	 * @throws IllegalRequestException when a month or clinic is not recognised or the start is after
	 *             the end
	 */
	@RequestMapping(value = "/summary", method = RequestMethod.GET)
	@ResponseBody
	public SimpleObject summary(@RequestParam(value = "clinic", required = false) String[] clinics,
	        @RequestParam(value = "startMonth", required = false) String startMonth,
	        @RequestParam(value = "endMonth", required = false) String endMonth) {
		YearMonth end = month("endMonth", endMonth, YearMonth.now());
		YearMonth start = month("startMonth", startMonth, end.minusMonths(11));
		if (start.isAfter(end)) {
			throw new IllegalRequestException("startMonth is after endMonth");
		}
		ScreeningCountSummary summary = service().getSummary(locationIds(clinics), start, end);
		SimpleObject settings = new SimpleObject();
		for (String setting : ScreeningCount.SETTINGS) {
			settings.add(setting, summary.getSums().get(setting + "_screened"));
		}
		return new SimpleObject().add("startMonth", start.format(MONTH)).add("endMonth", end.format(MONTH))
		        .add("months", summary.getMonths()).add("screened", summary.screened())
		        .add("females", summary.total("females")).add("positive", summary.positive())
		        .add("negative", summary.negative()).add("children", summary.total("children"))
		        .add("adolescents", summary.total("adolescents")).add("adults", summary.total("adults"))
		        .add("settings", settings);
	}
	
	private static SimpleObject describe(Location clinic, YearMonth month, ScreeningCountMonth counts) {
		return new SimpleObject().add("clinic", clinic.getUuid()).add("month", month.format(MONTH))
		        .add("saved", counts.isSaved()).add("counts", counts.getCounts()).add("notes", counts.getNotes());
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
	 * Gets the location ids of the clinics given, or null for none, which stands for every clinic the
	 * user may sum. A uuid that names no location is a bad request, unless it is a clinic on a site
	 * administrator's account, which is skipped.
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
		if (uuids.isEmpty()) {
			return null;
		}
		User user = Context.getAuthenticatedUser();
		List<Integer> ids = new ArrayList<Integer>();
		for (String uuid : uuids) {
			Location location = location(uuid);
			if (location != null) {
				ids.add(location.getLocationId());
			} else if (!UserClinics.isLimited(user) || !UserClinics.of(user).contains(uuid)) {
				throw new IllegalRequestException("No location has the uuid " + uuid);
			}
		}
		return ids;
	}
	
	private static Location clinicOf(String uuid) {
		Location location = location(uuid);
		if (location == null) {
			throw new IllegalRequestException("No location has the uuid " + uuid);
		}
		return location;
	}
	
	private static ScreeningCountService service() {
		return Context.getService(ScreeningCountService.class);
	}
}
