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

import java.text.SimpleDateFormat;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.ActCoreConstants;
import org.openmrs.module.actcore.api.AuditLogService;
import org.openmrs.module.actcore.logs.ActivityLog;
import org.openmrs.module.actcore.logs.Rows;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.ConversionUtil;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.openmrs.module.webservices.rest.web.response.IllegalRequestException;
import org.openmrs.module.webservices.rest.web.response.ObjectNotFoundException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Serves the Logs page's Activity tab: what ACT's users changed, one entry per save, and the fields
 * each entry changed.
 */
@Controller
@RequestMapping("/rest/" + RestConstants.VERSION_1 + "/actcore/activity")
public class ActivityController extends ActCoreRestController {
	
	/**
	 * How many days back a request with a kind filter but no start date reads. The span is short so
	 * that such a request rarely reaches ActivityLog.MAX_CLASSIFIED.
	 */
	static final int KIND_DEFAULT_DAYS = 30;
	
	/**
	 * Gets a page of what ACT's users changed, newest first.
	 *
	 * @param username the user to list, or blank for no user filter
	 * @param startDate the first day as YYYY-MM-DD; blank for a default span before the end date, or
	 *            before today without one, which is {@value #KIND_DEFAULT_DAYS} days with a kind and
	 *            {@value ActCoreRestController#DEFAULT_MONTHS} months without
	 * @param endDate the last day as YYYY-MM-DD; blank for no end
	 * @param kind one of {@link ActivityLog#KINDS}, or blank for no kind filter
	 * @param form the uuid of the form whose saves to list, or blank for no form filter
	 * @param patient the uuid of the patient whose changes to list, or blank for no patient filter
	 * @param startIndex the first entry to give, from 0; null for 0
	 * @param limit the most entries to give, capped at {@link Rows#MAX_LIMIT}; null or below 1 for
	 *            {@link Rows#DEFAULT_LIMIT}
	 * @return the entries as {@code results}, with {@code totalCount} and the days read
	 * @throws org.openmrs.api.APIAuthenticationException when the user lacks
	 *             {@link ActCoreConstants#AUDITLOGWEB_VIEW_AUDIT_LOGS}
	 * @throws IllegalRequestException when the kind or a date is not recognised, or the start is after
	 *             the end
	 * @throws Rows.TooMany when a kind filter has too many changes to sort
	 */
	@RequestMapping(method = RequestMethod.GET)
	@ResponseBody
	public SimpleObject getActivity(@RequestParam(value = "username", required = false) String username,
	        @RequestParam(value = "startDate", required = false) String startDate,
	        @RequestParam(value = "endDate", required = false) String endDate,
	        @RequestParam(value = "kind", required = false) String kind,
	        @RequestParam(value = "form", required = false) String form,
	        @RequestParam(value = "patient", required = false) String patient,
	        @RequestParam(value = "startIndex", required = false) Integer startIndex,
	        @RequestParam(value = "limit", required = false) Integer limit) {
		ActivityLog.Filter filter = new ActivityLog.Filter();
		filter.username = StringUtils.trimToNull(username);
		filter.kind = StringUtils.trimToNull(kind);
		if (filter.kind != null && !ActivityLog.KINDS.contains(filter.kind)) {
			throw new IllegalRequestException("Not a kind of activity: " + kind + "; one of " + ActivityLog.KINDS);
		}
		filter.to = date("endDate", endDate, null);
		filter.from = from(startDate, filter.to,
		    filter.kind == null ? Period.ofMonths(DEFAULT_MONTHS) : Period.ofDays(KIND_DEFAULT_DAYS));
		filter.formUuid = StringUtils.trimToNull(form);
		filter.patientUuid = StringUtils.trimToNull(patient);
		filter.startIndex = startIndex == null ? 0 : startIndex;
		filter.limit = limit == null ? Rows.DEFAULT_LIMIT : limit;
		
		ActivityLog.Page page = Context.getService(AuditLogService.class).getActivity(filter);
		List<SimpleObject> results = new ArrayList<SimpleObject>();
		for (ActivityLog.Entry entry : page.results) {
			results.add(new SimpleObject().add("revisionId", entry.revisionId)
			        .add("time", ConversionUtil.convertToRepresentation(entry.time, null)).add("user", who(entry.user))
			        .add("kind", entry.kind).add("action", entry.action).add("summary", entry.summary)
			        .add("target", target(entry.target)).add("encounter", encounter(entry.encounter)).add("ip", null)
			        .add("userAgent", null));
		}
		return new SimpleObject().add("results", results).add("totalCount", page.totalCount)
		        .add("from", filter.from.toString()).add("to", filter.to == null ? null : filter.to.toString());
	}
	
	/**
	 * Gets an entry's fields, each with its previous and current value, and its void reason.
	 *
	 * @param revisionId the entry's revision id
	 * @return the revision id, the fields and the void reason, null when there is none
	 * @throws org.openmrs.api.APIAuthenticationException when the user lacks
	 *             {@link ActCoreConstants#AUDITLOGWEB_VIEW_AUDIT_LOGS}
	 * @throws ObjectNotFoundException when the log has no entry for the revision
	 */
	@RequestMapping(value = "/{revisionId}", method = RequestMethod.GET)
	@ResponseBody
	public SimpleObject getDetails(@PathVariable("revisionId") Integer revisionId) {
		ActivityLog.Details details = Context.getService(AuditLogService.class).getActivityDetails(revisionId);
		if (details == null) {
			throw new ObjectNotFoundException();
		}
		List<SimpleObject> fields = new ArrayList<SimpleObject>();
		for (ActivityLog.Field field : details.fields) {
			fields.add(
			    new SimpleObject().add("name", field.name).add("previous", field.previous).add("current", field.current));
		}
		return new SimpleObject().add("revisionId", details.revisionId).add("fields", fields).add("voidReason",
		    details.voidReason);
	}
	
	private static SimpleObject target(ActivityLog.Target target) {
		return target == null ? null
		        : new SimpleObject().add("kind", target.kind).add("uuid", target.uuid).add("display", target.display)
		                .add("actId", target.actId);
	}
	
	private static SimpleObject encounter(ActivityLog.EncounterRef encounter) {
		if (encounter == null) {
			return null;
		}
		SimpleObject form = encounter.formUuid == null ? null
		        : new SimpleObject().add("uuid", encounter.formUuid).add("name", encounter.formName);
		return new SimpleObject().add("uuid", encounter.uuid).add("form", form).add("visitDate",
		    encounter.visitDate == null ? null : new SimpleDateFormat("yyyy-MM-dd").format(encounter.visitDate));
	}
}
