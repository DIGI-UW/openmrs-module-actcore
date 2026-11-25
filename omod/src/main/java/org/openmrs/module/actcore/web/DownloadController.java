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
import java.time.Period;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.openmrs.Concept;
import org.openmrs.Form;
import org.openmrs.Location;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.ValidationException;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.ActCoreConstants;
import org.openmrs.module.actcore.ActCorePrivileges;
import org.openmrs.module.actcore.api.DownloadLogService;
import org.openmrs.module.actcore.api.impl.FormDownload;
import org.openmrs.module.actcore.api.impl.Names;
import org.openmrs.module.actcore.api.search.DownloadSearchCriteria;
import org.openmrs.module.actcore.model.DownloadLogItem;
import org.openmrs.module.actcore.model.DownloadPage;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.ConversionUtil;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.openmrs.module.webservices.rest.web.response.IllegalRequestException;
import org.openmrs.module.webservices.rest.web.response.ObjectNotFoundException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Serves ACT 2.0's Download, which exports saved forms as CSV, and the download log. Every download
 * is recorded in the download log before its data is returned. A download gives the patient fields
 * and questions that identify a patient only to a user who also holds
 * ActCorePrivileges.DOWNLOAD_IDENTIFIERS, and a research download never gives them.
 */
@Controller
@RequestMapping("/rest/" + RestConstants.VERSION_1 + "/actcore/download")
public class DownloadController extends ActCoreRestController {
	
	static final int MAX_LABEL = 255;
	
	static final int MAX_COLUMNS = 200;
	
	/**
	 * The names the Logs page gives its tabs, keyed by the name the app records a tab's CSV under.
	 */
	static final Map<String, String> LOG_TABS = new LinkedHashMap<String, String>();
	
	static {
		LOG_TABS.put("activity", "Activity");
		LOG_TABS.put("reads", "Patients opened");
		LOG_TABS.put("downloads", "Downloads");
		LOG_TABS.put("signIns", "Sign-ins");
	}
	
	/**
	 * Gets the CSV of the forms asked for and records the download: a row per save of one form, or a
	 * row per patient across several forms or when asked. Without
	 * {@link ActCorePrivileges#DOWNLOAD_DATA} it is of the patients who opted in to research.
	 *
	 * @param body the request body: {@code forms}, each a form uuid and its chosen fields, or one
	 *            {@code form} and its {@code fields}; and optionally {@code rowPerPatient},
	 *            {@code status}, {@code cardiacClinic}, {@code primaryCareClinic}, {@code startDate},
	 *            {@code endDate} and the {@code patient} fields
	 * @return the headers and rows, whether identifiers were given, whether it is research only, and
	 *         the labels of the fields withheld
	 * @throws APIAuthenticationException when the user lacks both
	 *             {@link ActCorePrivileges#DOWNLOAD_DATA} and
	 *             {@link ActCorePrivileges#DOWNLOAD_RESEARCH_DATA}; lacks Get Forms, Get Concepts, Get
	 *             Encounters or Get Observations, which the forms and their answers are read with; or,
	 *             with a status filter, lacks Get Patient Programs
	 * @throws ObjectNotFoundException when a form is missing or no form has its uuid
	 * @throws IllegalRequestException when no form is asked for, a form is asked for twice or has no
	 *             field, a field, status, clinic, date or patient field is not recognised, the start is
	 *             after the end, or the record of the download would be too long
	 * @throws JsonProcessingException when the record of the download cannot be written as JSON
	 */
	@RequestMapping(method = RequestMethod.POST)
	@ResponseBody
	public SimpleObject download(@RequestBody Map<String, Object> body) throws JsonProcessingException {
		boolean all = Context.hasPrivilege(ActCorePrivileges.DOWNLOAD_DATA);
		if (!all && !Context.hasPrivilege(ActCorePrivileges.DOWNLOAD_RESEARCH_DATA)) {
			throw new APIAuthenticationException("Privilege required: " + ActCorePrivileges.DOWNLOAD_DATA);
		}
		boolean identified = all && Context.hasPrivilege(ActCorePrivileges.DOWNLOAD_IDENTIFIERS);
		boolean researchOnly = !all;
		List<FormDownload.Choice> choices = choices(body);
		boolean rowPerPatient = choices.size() > 1 || Boolean.TRUE.equals(body.get("rowPerPatient"));
		Object status = body.get("status");
		if (status != null && !Arrays.asList("active", "inactive").contains(status.toString())) {
			throw new IllegalRequestException("status must be active or inactive");
		}
		FormDownload.Filters filters = new FormDownload.Filters(status == null ? null : status.toString(),
		        clinic(body.get("cardiacClinic")), clinic(body.get("primaryCareClinic")));
		LocalDate from = date("startDate", body.get("startDate"), null);
		LocalDate to = date("endDate", body.get("endDate"), null);
		if (from != null && to != null && from.isAfter(to)) {
			throw new IllegalRequestException("The start date is after the end date");
		}
		List<String> patient = patient(body.get("patient"));
		FormDownload.Choice only = choices.get(0);
		FormDownload.Table table = rowPerPatient
		        ? new FormDownload().buildPerPatient(choices, from, to, identified, researchOnly, filters, patient)
		        : new FormDownload().build(only.form, only.concepts, only.labels, from, to, identified, researchOnly,
		            filters, patient);
		List<String> withheld = new ArrayList<String>();
		table.withheldPatient.forEach(key -> withheld.add(FormDownload.PATIENT_FIELDS.get(key)));
		withheld.addAll(table.withheld);
		List<String> given = new ArrayList<String>();
		List<Map<String, Object>> forms = new ArrayList<Map<String, Object>>();
		for (FormDownload.Choice choice : choices) {
			List<String> fields = new ArrayList<String>();
			for (String label : choice.labels) {
				if (!table.withheld.contains(rowPerPatient ? choice.name + ": " + label : label)) {
					fields.add(label);
					given.add(choices.size() > 1 ? choice.name + ": " + label : label);
				}
			}
			Map<String, Object> form = new LinkedHashMap<String, Object>();
			form.put("uuid", choice.form.getUuid());
			form.put("name", choice.name);
			form.put("fields", fields);
			forms.add(form);
		}
		Map<String, Object> shown = new LinkedHashMap<String, Object>();
		shown.put("status", filters.status);
		shown.put("cardiacClinic", filters.cardiacClinic == null ? null : filters.cardiacClinic.getName());
		shown.put("primaryCareClinic", filters.primaryCareClinic == null ? null : filters.primaryCareClinic.getName());
		Map<String, Object> detail = new LinkedHashMap<String, Object>();
		detail.put("fields", given);
		detail.put("withheld", withheld);
		detail.put("startDate", from == null ? null : from.toString());
		detail.put("endDate", to == null ? null : to.toString());
		detail.put("rows", table.rows.size());
		detail.put("identifiers", table.identifiers);
		detail.put("researchOnly", researchOnly);
		detail.put("forms", forms);
		detail.put("rowPerPatient", rowPerPatient);
		detail.put("filters", shown);
		detail.put("patient", table.patient);
		// Recorded before the rows are returned, so no download goes unrecorded.
		log(choices.size() == 1 ? only.form.getFormId() : null, detail);
		
		return new SimpleObject().add("headers", table.headers).add("rows", table.rows).add("identifiers", table.identifiers)
		        .add("researchOnly", researchOnly).add("withheld", withheld);
	}
	
	/**
	 * Gets a page of the downloads recorded that match the filters, newest first, for a user who may
	 * read the audit log's reads.
	 *
	 * @param username the user whose downloads to list, or blank for no user filter
	 * @param startDate the first day as YYYY-MM-DD; blank for {@value #DEFAULT_MONTHS} months before
	 *            the end date or today
	 * @param endDate the last day as YYYY-MM-DD; blank for no end
	 * @param formUuid the uuid of a form the downloads must hold, or blank for no form filter
	 * @param identifiers with, without or research, or null for no identifiers filter
	 * @param startIndex the first download to give, from 0; null for 0
	 * @param limit the most downloads to give, capped at {@value ActCoreConstants#DOWNLOAD_MAX_LIMIT};
	 *            null or below 1 for {@value ActCoreConstants#DOWNLOAD_DEFAULT_LIMIT}
	 * @return the downloads as {@code results}, with {@code totalCount} and the days read; empty when
	 *         no user has the username or no form has the uuid. Each download gives its {@code date},
	 *         {@code user}, {@code form}, {@code list} and {@code detail}; {@code identifiers}, the
	 *         value of the identifiers filter that matches it; and {@code unreadable}, true when its
	 *         recorded detail cannot be read as a JSON object, which leaves {@code detail} and
	 *         {@code list} null and {@code identifiers} {@code "with"}
	 * @throws APIAuthenticationException when the user lacks
	 *             {@link ActCoreConstants#AUDITLOGWEB_VIEW_READ_AUDIT_LOGS}
	 * @throws IllegalRequestException when a date or identifiers is not recognised, or the start is
	 *             after the end
	 */
	@RequestMapping(method = RequestMethod.GET)
	@ResponseBody
	public SimpleObject getDownloads(@RequestParam(value = "username", required = false) String username,
	        @RequestParam(value = "startDate", required = false) String startDate,
	        @RequestParam(value = "endDate", required = false) String endDate,
	        @RequestParam(value = "form", required = false) String formUuid,
	        @RequestParam(value = "identifiers", required = false) String identifiers,
	        @RequestParam(value = "startIndex", required = false) Integer startIndex,
	        @RequestParam(value = "limit", required = false) Integer limit) {
		if (identifiers != null && !Arrays.asList("with", "without", "research").contains(identifiers)) {
			throw new IllegalRequestException("identifiers must be with, without or research");
		}
		DownloadSearchCriteria filter = new DownloadSearchCriteria();
		filter.username = username;
		filter.to = date("endDate", endDate, null);
		filter.from = from(startDate, filter.to, Period.ofMonths(DEFAULT_MONTHS));
		filter.formUuid = formUuid;
		filter.identifiers = identifiers;
		filter.startIndex = startIndex == null ? 0 : startIndex;
		filter.limit = limit == null ? ActCoreConstants.DOWNLOAD_DEFAULT_LIMIT : limit;
		DownloadPage page = Context.getService(DownloadLogService.class).getDownloads(filter);
		List<SimpleObject> results = new ArrayList<SimpleObject>();
		for (DownloadLogItem download : page.results) {
			Object list = download.detail == null ? null : download.detail.get("list");
			results.add(new SimpleObject().add("date", ConversionUtil.convertToRepresentation(download.date, null))
			        .add("user", who(download.user))
			        .add("form",
			            download.formName == null ? null
			                    : new SimpleObject().add("uuid", download.formUuid).add("name", download.formName))
			        .add("list", list == null ? null : list(list.toString())).add("detail", download.detail)
			        .add("identifiers", download.identifiers).add("unreadable", download.unreadable));
		}
		return new SimpleObject().add("results", results).add("totalCount", page.totalCount)
		        .add("from", filter.from.toString()).add("to", filter.to == null ? null : filter.to.toString());
	}
	
	/**
	 * Records a download of a CSV that the app made itself: the registry list, a worklist or a Logs
	 * tab. The app records it before writing the file, and does not write the file when this fails.
	 *
	 * @param body the request body: the {@code list}, as registry, worklist:&lt;name&gt; or
	 *            logs:&lt;tab&gt;, its {@code columns}, and optionally the number of {@code rows},
	 *            whether it holds {@code identifiers} and its {@code filters}
	 * @return {@code recorded} true
	 * @throws APIAuthenticationException when the user lacks both
	 *             {@link ActCorePrivileges#DOWNLOAD_DATA} and
	 *             {@link ActCorePrivileges#DOWNLOAD_RESEARCH_DATA}
	 * @throws IllegalRequestException when the list or columns are missing, there are more than
	 *             {@value #MAX_COLUMNS} columns, or the record would be too long
	 * @throws JsonProcessingException when the record cannot be written as JSON
	 */
	@RequestMapping(value = "/record", method = RequestMethod.POST)
	@ResponseBody
	public SimpleObject record(@RequestBody Map<String, Object> body) throws JsonProcessingException {
		boolean all = Context.hasPrivilege(ActCorePrivileges.DOWNLOAD_DATA);
		if (!all && !Context.hasPrivilege(ActCorePrivileges.DOWNLOAD_RESEARCH_DATA)) {
			throw new APIAuthenticationException("Privilege required: " + ActCorePrivileges.DOWNLOAD_DATA);
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
	
	/**
	 * Reads the forms a download asks for, each with its chosen fields. The body gives them either as
	 * {@code forms}, a list of form and fields pairs, or as one {@code form} with its {@code fields}. A
	 * field asked for twice in one form is kept once.
	 */
	private static List<FormDownload.Choice> choices(Map<String, Object> body) {
		List<?> asked = body == null ? null
		        : body.get("forms") instanceof List ? (List<?>) body.get("forms")
		                : body.get("form") == null ? null : Collections.singletonList(body);
		if (asked == null || asked.isEmpty()) {
			throw new IllegalRequestException("The form is required");
		}
		List<FormDownload.Choice> choices = new ArrayList<FormDownload.Choice>();
		for (Object each : asked) {
			Object formUuid = each instanceof Map ? ((Map<?, ?>) each).get("form") : null;
			Form form = formUuid == null ? null : Context.getFormService().getFormByUuid(formUuid.toString());
			if (form == null) {
				throw new ObjectNotFoundException();
			}
			if (choices.stream().anyMatch(choice -> choice.form.equals(form))) {
				throw new IllegalRequestException("A form is asked for twice: " + formUuid);
			}
			List<Concept> concepts = new ArrayList<Concept>();
			List<String> labels = new ArrayList<String>();
			Object fields = ((Map<?, ?>) each).get("fields");
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
			choices.add(
			    new FormDownload.Choice(form, Names.shown("Form", form.getUuid(), form.getName()), concepts, labels));
		}
		return choices;
	}
	
	/**
	 * Reads the patient fields a download asks for, as keys of FormDownload.PATIENT_FIELDS. It is null
	 * when the request does not say, which leaves the default fields to FormDownload.
	 */
	private static List<String> patient(Object asked) {
		if (asked == null) {
			return null;
		}
		if (!(asked instanceof List)) {
			throw new IllegalRequestException("patient must be a list of " + FormDownload.PATIENT_FIELDS.keySet());
		}
		List<String> keys = new ArrayList<String>();
		for (Object key : (List<?>) asked) {
			if (!FormDownload.PATIENT_FIELDS.containsKey(String.valueOf(key))) {
				throw new IllegalRequestException(
				        "Not a patient field: " + key + "; one of " + FormDownload.PATIENT_FIELDS.keySet());
			}
			keys.add(String.valueOf(key));
		}
		return keys;
	}
	
	private static Location clinic(Object uuid) {
		if (uuid == null || StringUtils.isBlank(uuid.toString())) {
			return null;
		}
		Location clinic = location(uuid.toString());
		if (clinic == null) {
			throw new IllegalRequestException("Not a clinic: " + uuid);
		}
		return clinic;
	}
	
	private void log(Integer formId, Map<String, Object> detail) throws JsonProcessingException {
		String json = new ObjectMapper().writeValueAsString(detail);
		try {
			Context.getService(DownloadLogService.class).recordDownload(formId, json);
		}
		catch (ValidationException e) {
			throw new IllegalRequestException(e.getMessage());
		}
	}
	
	/**
	 * Names the list that a recorded CSV was of, as the Logs page shows it, with its kind: registry,
	 * worklist, logs or other.
	 */
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
}
