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

import java.time.Period;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.ContextAuthenticationException;
import org.openmrs.module.actcore.ActCoreConstants;
import org.openmrs.module.actcore.api.AuditLogService;
import org.openmrs.module.actcore.logs.ReadLog;
import org.openmrs.module.actcore.logs.Rows;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.ConversionUtil;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.openmrs.module.webservices.rest.web.response.IllegalRequestException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Serves the Logs page's Patients opened tab: who read which patient's record, with a user's reads
 * of one patient within an hour folded into one row.
 */
@Controller
@RequestMapping("/rest/" + RestConstants.VERSION_1 + "/actcore/reads")
public class ReadsController extends ActCoreRestController {
	
	/**
	 * How many days back the log reads when the request gives no start date. It is shorter than the
	 * other logs' default because every chart opened writes reads.
	 */
	static final int DEFAULT_DAYS = 30;
	
	/**
	 * Gets a page of who opened which patient's record, newest first.
	 *
	 * @param username the user to list, or blank for no user filter
	 * @param patient a patient uuid, or else an identifier to match exactly or part of a name to search
	 *            for; blank for no patient filter
	 * @param startDate the first day as YYYY-MM-DD; blank for {@value #DEFAULT_DAYS} days before the
	 *            end date or today
	 * @param endDate the last day as YYYY-MM-DD; blank for no end
	 * @param startIndex the first row to give, from 0; null for 0
	 * @param limit the most rows to give, capped at {@link Rows#MAX_LIMIT}; null or below 1 for
	 *            {@link Rows#DEFAULT_LIMIT}
	 * @return the rows as {@code results}, with {@code totalCount} and the days read
	 * @throws ContextAuthenticationException when the user lacks
	 *             {@link ActCoreConstants#AUDITLOGWEB_VIEW_READ_AUDIT_LOGS}
	 * @throws IllegalRequestException when a date is not a date, the start is after the end, or too
	 *             many patients match
	 */
	@RequestMapping(method = RequestMethod.GET)
	@ResponseBody
	public SimpleObject getReads(@RequestParam(value = "username", required = false) String username,
	        @RequestParam(value = "patient", required = false) String patient,
	        @RequestParam(value = "startDate", required = false) String startDate,
	        @RequestParam(value = "endDate", required = false) String endDate,
	        @RequestParam(value = "startIndex", required = false) Integer startIndex,
	        @RequestParam(value = "limit", required = false) Integer limit) {
		Context.requirePrivilege(ActCoreConstants.AUDITLOGWEB_VIEW_READ_AUDIT_LOGS);
		ReadLog.Filter filter = new ReadLog.Filter();
		filter.username = StringUtils.trimToNull(username);
		filter.patient = StringUtils.trimToNull(patient);
		filter.to = DownloadController.date(endDate);
		filter.from = DownloadController.from(startDate, filter.to, Period.ofDays(DEFAULT_DAYS));
		filter.startIndex = startIndex == null ? 0 : startIndex;
		filter.limit = limit == null ? Rows.DEFAULT_LIMIT : limit;
		
		ReadLog.Page page;
		try {
			page = Context.getService(AuditLogService.class).getReads(filter);
		}
		catch (Rows.TooMany e) {
			throw new IllegalRequestException(e.getMessage());
		}
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
