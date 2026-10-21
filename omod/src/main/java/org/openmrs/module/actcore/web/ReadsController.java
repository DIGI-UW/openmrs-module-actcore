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

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.logs.ReadLog;
import org.openmrs.module.actcore.logs.Rows;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.ConversionUtil;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.openmrs.module.webservices.rest.web.v1_0.controller.BaseRestController;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/** The Logs page's Patients opened tab: who read which patient's record, folded by the hour. */
@Controller
@RequestMapping("/rest/" + RestConstants.VERSION_1 + "/actcore/reads")
public class ReadsController extends BaseRestController {
	
	@RequestMapping(method = RequestMethod.GET)
	@ResponseBody
	public SimpleObject getReads(@RequestParam(value = "username", required = false) String username,
	        @RequestParam(value = "patient", required = false) String patient,
	        @RequestParam(value = "startDate", required = false) String startDate,
	        @RequestParam(value = "endDate", required = false) String endDate,
	        @RequestParam(value = "startIndex", required = false) Integer startIndex,
	        @RequestParam(value = "limit", required = false) Integer limit) {
		if (!Context.hasPrivilege(ReadLog.VIEW_PRIVILEGE)) {
			throw new APIAuthenticationException("Privilege required: " + ReadLog.VIEW_PRIVILEGE);
		}
		ReadLog.Filter filter = new ReadLog.Filter();
		filter.username = StringUtils.trimToNull(username);
		filter.patient = StringUtils.trimToNull(patient);
		filter.to = DownloadController.date(endDate);
		filter.from = DownloadController.from(startDate, filter.to);
		filter.startIndex = startIndex == null ? 0 : startIndex;
		filter.limit = limit == null ? Rows.DEFAULT_LIMIT : limit;
		
		ReadLog.Page page = new ReadLog().list(filter);
		List<SimpleObject> results = new ArrayList<SimpleObject>();
		for (ReadLog.Row row : page.results) {
			List<SimpleObject> reads = new ArrayList<SimpleObject>();
			for (ReadLog.Read read : row.reads) {
				reads.add(new SimpleObject().add("time", ConversionUtil.convertToRepresentation(read.time, null))
				        .add("entity", read.entity).add("description", read.description));
			}
			SimpleObject subject = row.patient == null ? null
			        : new SimpleObject().add("uuid", row.patient.uuid).add("display", row.patient.display)
			                .add("actId", row.patient.actId).add("primaryCareClinic", row.patient.primaryCareClinic)
			                .add("cardiacClinic", row.patient.cardiacClinic);
			results.add(
			    new SimpleObject().add("id", row.id).add("from", ConversionUtil.convertToRepresentation(row.from, null))
			            .add("to", ConversionUtil.convertToRepresentation(row.to, null))
			            .add("user", ActivityController.who(row.user)).add("patient", subject).add("count", row.reads.size())
			            .add("reads", reads).add("sessionId", row.sessionId).add("ip", row.ip)
			            .add("userAgent", row.userAgent).add("sameClinicAsUser", row.sameClinicAsUser));
		}
		return new SimpleObject().add("results", results).add("totalCount", page.totalCount)
		        .add("from", filter.from.toString()).add("to", filter.to == null ? null : filter.to.toString());
	}
}
