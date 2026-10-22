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
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
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
import org.openmrs.module.actcore.logs.Names;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.ConversionUtil;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.openmrs.module.webservices.rest.web.response.IllegalRequestException;
import org.openmrs.module.webservices.rest.web.response.ObjectNotFoundException;
import org.openmrs.module.webservices.rest.web.v1_0.controller.BaseRestController;
import org.openmrs.util.PrivilegeConstants;
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
	
	static final int MAX_COLUMNS = 200;
	
	/** MariaDB's TEXT, the log's detail column, which refuses a longer value. */
	static final int MAX_DETAIL_BYTES = 65535;
	
	/** How far back a log without its own default reaches when the request gives no start date. */
	static final int DEFAULT_MONTHS = 12;
	
	/** The Logs page's tabs as it names them, by the key the app records a tab's CSV under. */
	static final Map<String, String> LOG_TABS = new LinkedHashMap<String, String>();
	
	static {
		LOG_TABS.put("activity", "Activity");
		LOG_TABS.put("reads", "Patients opened");
		LOG_TABS.put("downloads", "Downloads");
		LOG_TABS.put("signIns", "Sign-ins");
	}
	
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
		log(form.getFormId(), detail);
		
		return new SimpleObject().add("headers", table.headers).add("rows", table.rows).add("identifiers", identified)
		        .add("researchOnly", researchOnly).add("withheld", table.withheld);
	}
	
	/**
	 * The downloads matching the filters, newest first, for a user who may read the audit log's reads.
	 */
	@RequestMapping(method = RequestMethod.GET)
	@ResponseBody
	public SimpleObject getDownloads(@RequestParam(value = "username", required = false) String username,
	        @RequestParam(value = "startDate", required = false) String startDate,
	        @RequestParam(value = "endDate", required = false) String endDate,
	        @RequestParam(value = "form", required = false) String formUuid,
	        @RequestParam(value = "identifiers", required = false) String identifiers,
	        @RequestParam(value = "startIndex", required = false) Integer startIndex,
	        @RequestParam(value = "limit", required = false) Integer limit) throws IOException {
		if (!Context.hasPrivilege(DownloadLog.VIEW_PRIVILEGE)) {
			throw new APIAuthenticationException("Privilege required: " + DownloadLog.VIEW_PRIVILEGE);
		}
		LocalDate to = date(endDate);
		LocalDate from = from(startDate, to);
		SimpleObject none = new SimpleObject().add("results", new ArrayList<SimpleObject>()).add("totalCount", 0)
		        .add("from", from.toString()).add("to", to == null ? null : to.toString());
		Integer userId = null;
		if (StringUtils.isNotBlank(username)) {
			userId = Names.userId(username.trim());
			if (userId == null) {
				return none;
			}
		}
		Integer formId = null;
		if (StringUtils.isNotBlank(formUuid)) {
			Form form = Names.proxied(PrivilegeConstants.GET_FORMS,
			    () -> Context.getFormService().getFormByUuid(formUuid.trim()));
			if (form == null) {
				return none;
			}
			formId = form.getFormId();
		}
		if (identifiers != null && !Arrays.asList("with", "without", "research").contains(identifiers)) {
			throw new IllegalRequestException("identifiers must be with, without or research");
		}
		ObjectMapper json = new ObjectMapper();
		List<SimpleObject> matching = new ArrayList<SimpleObject>();
		for (DownloadLog.Entry entry : downloadLog.list(userId, from, to, formId)) {
			Map<?, ?> detail = entry.detail == null ? null : json.readValue(entry.detail, Map.class);
			if (identifiers != null && !identifiers.equals(identifiersOf(detail))) {
				continue;
			}
			Object list = detail == null ? null : detail.get("list");
			matching.add(new SimpleObject().add("date", ConversionUtil.convertToRepresentation(entry.date, null))
			        .add("user", new SimpleObject().add("uuid", entry.userUuid).add("username", entry.username))
			        .add("form",
			            entry.formName == null ? null
			                    : new SimpleObject().add("uuid", entry.formUuid).add("name", entry.formName))
			        .add("list", list == null ? null : list(list.toString())).add("detail", detail));
		}
		int start = Math.min(startIndex == null ? 0 : Math.max(startIndex, 0), matching.size());
		int size = limit == null || limit < 1 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
		return new SimpleObject().add("results", matching.subList(start, Math.min(start + size, matching.size())))
		        .add("totalCount", matching.size()).add("from", from.toString())
		        .add("to", to == null ? null : to.toString());
	}
	
	/**
	 * Records a CSV the app made itself: the registry, a worklist or a Logs tab. Recorded before the
	 * app writes the file, which it does not when this fails.
	 */
	@RequestMapping(value = "/record", method = RequestMethod.POST)
	@ResponseBody
	public SimpleObject record(@RequestBody Map<String, Object> body) throws JsonProcessingException {
		boolean all = Context.hasPrivilege(DOWNLOAD_PRIVILEGE);
		if (!all && !Context.hasPrivilege(RESEARCH_PRIVILEGE)) {
			throw new APIAuthenticationException("Privilege required: " + DOWNLOAD_PRIVILEGE);
		}
		Object list = body == null ? null : body.get("list");
		if (list == null || StringUtils.isBlank(list.toString())) {
			throw new IllegalRequestException("The list is required: registry, worklist:<name> or logs:<tab>");
		}
		Object columns = body.get("columns");
		if (!(columns instanceof List) || ((List<?>) columns).size() > MAX_COLUMNS) {
			throw new IllegalRequestException("The columns are required, at most " + MAX_COLUMNS);
		}
		List<String> names = new ArrayList<String>();
		for (Object column : (List<?>) columns) {
			names.add(StringUtils.left(String.valueOf(column), MAX_LABEL));
		}
		Object rows = body.get("rows");
		Map<String, Object> detail = new LinkedHashMap<String, Object>();
		detail.put("list", StringUtils.left(list.toString().trim(), MAX_LABEL));
		detail.put("fields", names);
		detail.put("withheld", new ArrayList<String>());
		detail.put("startDate", null);
		detail.put("endDate", null);
		detail.put("rows", rows instanceof Number ? ((Number) rows).intValue() : 0);
		detail.put("identifiers", Boolean.TRUE.equals(body.get("identifiers")));
		// The app's lists are not limited to opted-in patients, whoever downloads them.
		detail.put("researchOnly", false);
		detail.put("filters",
		    body.get("filters") instanceof Map ? body.get("filters") : new LinkedHashMap<String, Object>());
		log(null, detail);
		return new SimpleObject().add("recorded", true);
	}
	
	private void log(Integer formId, Map<String, Object> detail) throws JsonProcessingException {
		String json = new ObjectMapper().writeValueAsString(detail);
		if (json.getBytes(StandardCharsets.UTF_8).length > MAX_DETAIL_BYTES) {
			throw new IllegalRequestException("Too many or too long columns and filters to record");
		}
		downloadLog.record(formId, json);
	}
	
	/** with, without or research, as the identifiers filter names what a download held. */
	private static String identifiersOf(Map<?, ?> detail) {
		if (detail == null) {
			return "without";
		}
		if (Boolean.TRUE.equals(detail.get("researchOnly"))) {
			return "research";
		}
		return Boolean.TRUE.equals(detail.get("identifiers")) ? "with" : "without";
	}
	
	/** The list a recorded CSV was of, named as the Logs page shows it. */
	static SimpleObject list(String list) {
		if (list.equals("registry")) {
			return new SimpleObject().add("name", "Registry list").add("kind", "registry");
		}
		if (list.startsWith("worklist:")) {
			return new SimpleObject().add("name", list.substring("worklist:".length()).trim() + " worklist").add("kind",
			    "worklist");
		}
		if (list.startsWith("logs:")) {
			String tab = list.substring("logs:".length()).trim();
			return new SimpleObject()
			        .add("name", "Logs: " + StringUtils.defaultString(LOG_TABS.get(tab), StringUtils.capitalize(tab)))
			        .add("kind", "logs");
		}
		return new SimpleObject().add("name", list).add("kind", "other");
	}
	
	static LocalDate from(String startDate, LocalDate to) {
		return from(startDate, to, Period.ofMonths(DEFAULT_MONTHS));
	}
	
	/**
	 * The start date the log reads from: the one given, else {@code back} before the end date or today,
	 * so a request without dates does not read the whole log.
	 */
	static LocalDate from(String startDate, LocalDate to, Period back) {
		LocalDate from = date(startDate);
		if (from == null) {
			return (to == null ? LocalDate.now() : to).minus(back);
		}
		if (to != null && from.isAfter(to)) {
			throw new IllegalRequestException("The start date is after the end date");
		}
		return from;
	}
	
	static LocalDate date(Object value) {
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
