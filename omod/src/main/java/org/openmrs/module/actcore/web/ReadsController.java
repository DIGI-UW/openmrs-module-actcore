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
import org.openmrs.module.actcore.ActCoreConstants;
import org.openmrs.module.actcore.api.AuditLogService;
import org.openmrs.module.actcore.api.TooManyResultsException;
import org.openmrs.module.actcore.api.search.ReadSearchCriteria;
import org.openmrs.module.actcore.model.ReadEvent;
import org.openmrs.module.actcore.model.ReadPage;
import org.openmrs.module.actcore.model.ReadRow;
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
	 * @param limit the most rows to give, capped at {@link ActCoreConstants#LOG_MAX_LIMIT}; null or
	 *            below 1 for {@link ActCoreConstants#LOG_DEFAULT_LIMIT}
	 * @return the rows as {@code results}, with {@code totalCount} and the days read
	 * @throws org.openmrs.api.APIAuthenticationException when the user lacks
	 *             {@link ActCoreConstants#AUDITLOGWEB_VIEW_READ_AUDIT_LOGS}
	 * @throws IllegalRequestException when a date is not a date, or the start is after the end
	 * @throws TooManyResultsException when too many patients match
	 */
	@RequestMapping(method = RequestMethod.GET)
	@ResponseBody
	public SimpleObject getReads(@RequestParam(value = "username", required = false) String username,
	        @RequestParam(value = "patient", required = false) String patient,
	        @RequestParam(value = "startDate", required = false) String startDate,
	        @RequestParam(value = "endDate", required = false) String endDate,
	        @RequestParam(value = "startIndex", required = false) Integer startIndex,
	        @RequestParam(value = "limit", required = false) Integer limit) {
		ReadSearchCriteria filter = new ReadSearchCriteria();
		filter.setUsername(StringUtils.trimToNull(username));
		filter.setPatient(StringUtils.trimToNull(patient));
		filter.setTo(date("endDate", endDate, null));
		filter.setFrom(from(startDate, filter.getTo(), Period.ofDays(DEFAULT_DAYS)));
		filter.setStartIndex(startIndex == null ? 0 : startIndex);
		filter.setLimit(limit == null ? ActCoreConstants.LOG_DEFAULT_LIMIT : limit);
		
		ReadPage page = Context.getService(AuditLogService.class).getReads(filter);
		List<SimpleObject> results = new ArrayList<SimpleObject>();
		for (ReadRow row : page.getResults()) {
			List<SimpleObject> reads = new ArrayList<SimpleObject>();
			for (ReadEvent read : row.getReads()) {
				reads.add(new SimpleObject().add("time", ConversionUtil.convertToRepresentation(read.getTime(), null))
				        .add("entity", read.getEntity()).add("description", read.getDescription()));
			}
			SimpleObject subject = row.getPatient() == null ? null
			        : new SimpleObject().add("uuid", row.getPatient().getUuid())
			                .add("display", row.getPatient().getDisplay()).add("actId", row.getPatient().getActId())
			                .add("primaryCareClinic", row.getPatient().getPrimaryCareClinic())
			                .add("cardiacClinic", row.getPatient().getCardiacClinic());
			results.add(new SimpleObject().add("id", row.getId())
			        .add("from", ConversionUtil.convertToRepresentation(row.getFrom(), null))
			        .add("to", ConversionUtil.convertToRepresentation(row.getTo(), null)).add("user", who(row.getUser()))
			        .add("patient", subject).add("count", row.getReads().size()).add("reads", reads)
			        .add("sessionId", row.getSessionId()).add("ip", row.getIp()).add("userAgent", row.getUserAgent())
			        .add("sameClinicAsUser", row.getSameClinicAsUser()));
		}
		return new SimpleObject().add("results", results).add("totalCount", page.getTotalCount())
		        .add("from", filter.getFrom().toString())
		        .add("to", filter.getTo() == null ? null : filter.getTo().toString());
	}
}
