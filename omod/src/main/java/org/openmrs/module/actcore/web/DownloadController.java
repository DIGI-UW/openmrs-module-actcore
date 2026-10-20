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

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.openmrs.Concept;
import org.openmrs.Form;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.download.DownloadLog;
import org.openmrs.module.actcore.download.FormDownload;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.ConversionUtil;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.openmrs.module.webservices.rest.web.response.IllegalRequestException;
import org.openmrs.module.webservices.rest.web.response.ObjectNotFoundException;
import org.openmrs.module.webservices.rest.web.v1_0.controller.BaseRestController;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * ACT 2.0's Download, each recorded in the download log, under privileges the distribution creates.
 * Identifiers only with IDENTIFIERS_PRIVILEGE; a research download is never identified.
 */
@Controller
@RequestMapping("/rest/" + RestConstants.VERSION_1 + "/actcore/download")
public class DownloadController extends BaseRestController {
	
	/** Download any patient's forms, without identifiers. */
	public static final String DOWNLOAD_PRIVILEGE = "Task: act.downloadData";
	
	/** With DOWNLOAD_PRIVILEGE, also give what identifies the patient, as FormDownload lists it. */
	public static final String IDENTIFIERS_PRIVILEGE = "Task: act.downloadIdentifiers";
	
	/** Without DOWNLOAD_PRIVILEGE, download only the forms of patients who opted in to research. */
	public static final String RESEARCH_PRIVILEGE = "Task: act.downloadResearchData";
	
	static final int MAX_LABEL = 255;
	
	static final int DEFAULT_LIMIT = 100;
	
	static final int MAX_LIMIT = 500;
	
	private final DownloadLog downloadLog = new DownloadLog();
	
	@RequestMapping(method = RequestMethod.POST)
	@ResponseBody
	public SimpleObject download(@RequestBody Map<String, Object> body) throws JsonProcessingException {
		boolean all = Context.hasPrivilege(DOWNLOAD_PRIVILEGE);
		if (!all && !Context.hasPrivilege(RESEARCH_PRIVILEGE)) {
			throw new APIAuthenticationException("Privilege required: " + DOWNLOAD_PRIVILEGE);
		}
		boolean identified = all && Context.hasPrivilege(IDENTIFIERS_PRIVILEGE);
		boolean researchOnly = !all;
		
		Object formUuid = body == null ? null : body.get("form");
		if (formUuid == null) {
			throw new IllegalRequestException("The form is required");
		}
		Form form = Context.getFormService().getFormByUuid(formUuid.toString());
		if (form == null) {
			throw new ObjectNotFoundException();
		}
		List<Concept> concepts = new ArrayList<Concept>();
		List<String> labels = new ArrayList<String>();
		Object fields = body.get("fields");
		if (!(fields instanceof List) || ((List<?>) fields).isEmpty()) {
			throw new IllegalRequestException("Choose at least one field");
		}
		for (Object field : (List<?>) fields) {
			Object conceptUuid = field instanceof Map ? ((Map<?, ?>) field).get("concept") : null;
			Concept concept = conceptUuid == null ? null
			        : Context.getConceptService().getConceptByUuid(conceptUuid.toString());
			if (concept == null) {
				throw new IllegalRequestException("Not a concept: " + conceptUuid);
			}
			if (concepts.contains(concept)) {
				continue;
			}
			Object label = ((Map<?, ?>) field).get("label");
			concepts.add(concept);
			labels.add(StringUtils.isBlank(label == null ? null : label.toString()) ? concept.getName().getName()
			        : StringUtils.left(label.toString().trim(), MAX_LABEL));
		}
		LocalDate from = date(body.get("startDate"));
		LocalDate to = date(body.get("endDate"));
		if (from != null && to != null && from.isAfter(to)) {
			throw new IllegalRequestException("The start date is after the end date");
		}
		
		FormDownload.Table table = new FormDownload().build(form, concepts, labels, from, to, identified, researchOnly);
		
		int given = labels.size() - table.withheld.size();
		Map<String, Object> detail = new LinkedHashMap<String, Object>();
		detail.put("fields", table.headers.subList(table.headers.size() - given, table.headers.size()));
		detail.put("withheld", table.withheld);
		detail.put("startDate", from == null ? null : from.toString());
		detail.put("endDate", to == null ? null : to.toString());
		detail.put("rows", table.rows.size());
		detail.put("identifiers", identified);
		detail.put("researchOnly", researchOnly);
		// Recorded before the rows are returned, so no download goes unrecorded.
		downloadLog.record(form.getFormId(), new ObjectMapper().writeValueAsString(detail));
		
		return new SimpleObject().add("headers", table.headers).add("rows", table.rows).add("identifiers", identified)
		        .add("researchOnly", researchOnly).add("withheld", table.withheld);
	}
	
	/** The latest downloads, newest first, for a user who may read the audit log's reads. */
	@RequestMapping(method = RequestMethod.GET)
	@ResponseBody
	public SimpleObject getDownloads(@RequestParam(value = "limit", required = false) Integer limit) throws IOException {
		if (!Context.hasPrivilege(DownloadLog.VIEW_PRIVILEGE)) {
			throw new APIAuthenticationException("Privilege required: " + DownloadLog.VIEW_PRIVILEGE);
		}
		int size = limit == null || limit < 1 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
		ObjectMapper json = new ObjectMapper();
		List<SimpleObject> results = new ArrayList<SimpleObject>();
		for (DownloadLog.Entry entry : downloadLog.latest(size)) {
			results.add(new SimpleObject().add("date", ConversionUtil.convertToRepresentation(entry.date, null))
			        .add("user", new SimpleObject().add("uuid", entry.userUuid).add("username", entry.username))
			        .add("form",
			            entry.formName == null ? null
			                    : new SimpleObject().add("uuid", entry.formUuid).add("name", entry.formName))
			        .add("detail", entry.detail == null ? null : json.readValue(entry.detail, Map.class)));
		}
		return new SimpleObject().add("results", results);
	}
	
	private static LocalDate date(Object value) {
		if (value == null || StringUtils.isBlank(value.toString())) {
			return null;
		}
		try {
			return LocalDate.parse(value.toString().trim());
		}
		catch (DateTimeParseException e) {
			throw new IllegalRequestException("Not a date (yyyy-MM-dd): " + value);
		}
	}
}
