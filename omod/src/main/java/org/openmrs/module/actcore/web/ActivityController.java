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
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.logs.ActivityLog;
import org.openmrs.module.actcore.logs.Names;
import org.openmrs.module.actcore.logs.Rows;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.ConversionUtil;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.openmrs.module.webservices.rest.web.response.IllegalRequestException;
import org.openmrs.module.webservices.rest.web.response.ObjectNotFoundException;
import org.openmrs.module.webservices.rest.web.v1_0.controller.BaseRestController;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * The Logs page's Activity tab: what ACT's users changed, one entry per save, and each entry's
 * fields.
 */
@Controller
@RequestMapping("/rest/" + RestConstants.VERSION_1 + "/actcore/activity")
public class ActivityController extends BaseRestController {
	
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
		requireViewPrivilege();
		ActivityLog.Filter filter = new ActivityLog.Filter();
		filter.username = StringUtils.trimToNull(username);
		filter.to = DownloadController.date(endDate);
		filter.from = DownloadController.from(startDate, filter.to);
		filter.kind = StringUtils.trimToNull(kind);
		if (filter.kind != null && !ActivityLog.KINDS.contains(filter.kind)) {
			throw new IllegalRequestException("Not a kind of activity: " + kind + "; one of " + ActivityLog.KINDS);
		}
		filter.formUuid = StringUtils.trimToNull(form);
		filter.patientUuid = StringUtils.trimToNull(patient);
		filter.startIndex = startIndex == null ? 0 : startIndex;
		filter.limit = limit == null ? Rows.DEFAULT_LIMIT : limit;
		
		ActivityLog.Page page;
		try {
			page = new ActivityLog().list(filter);
		}
		catch (Rows.TooMany e) {
			throw new IllegalRequestException(e.getMessage());
		}
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
	
	@RequestMapping(value = "/{revisionId}", method = RequestMethod.GET)
	@ResponseBody
	public SimpleObject getDetails(@PathVariable("revisionId") Integer revisionId) {
		requireViewPrivilege();
		ActivityLog.Details details = new ActivityLog().details(revisionId);
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
	
	static void requireViewPrivilege() {
		if (!Context.hasPrivilege(ActivityLog.VIEW_PRIVILEGE)) {
			throw new APIAuthenticationException("Privilege required: " + ActivityLog.VIEW_PRIVILEGE);
		}
	}
	
	static SimpleObject who(Names.Who who) {
		return who == null ? null
		        : new SimpleObject().add("uuid", who.uuid).add("username", who.username).add("display", who.display)
		                .add("role", who.role);
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
