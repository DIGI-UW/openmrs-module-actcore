/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.api.impl;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openmrs.Form;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.api.impl.BaseOpenmrsService;
import org.openmrs.module.actcore.ActCoreConstants;
import org.openmrs.module.actcore.ActCorePrivileges;
import org.openmrs.module.actcore.api.DownloadLogService;
import org.openmrs.module.actcore.api.FormDownloadService;
import org.openmrs.module.actcore.model.FormDownloadChoice;
import org.openmrs.module.actcore.model.FormDownloadFilters;
import org.openmrs.module.actcore.model.FormDownloadTable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Default implementation of {@link org.openmrs.module.actcore.api.FormDownloadService}. */
public class FormDownloadServiceImpl extends BaseOpenmrsService implements FormDownloadService {
	
	/**
	 * @see org.openmrs.module.actcore.api.FormDownloadService#download(List, boolean,
	 *      FormDownloadFilters, LocalDate, LocalDate, List)
	 */
	@Override
	// No @Transactional: the record commits in its own, before the rows are returned.
	public FormDownloadTable download(List<FormDownloadChoice> choices, boolean rowPerPatient, FormDownloadFilters filters,
	        LocalDate from, LocalDate to, List<String> patientFields) {
		if (choices == null || choices.isEmpty()) {
			throw Rows.refusal("download", "The form is required");
		}
		Set<Form> chosen = new HashSet<Form>();
		for (FormDownloadChoice choice : choices) {
			if (!chosen.add(choice.getForm())) {
				throw Rows.refusal("download", "A form is asked for twice: " + choice.getForm().getUuid());
			}
		}
		boolean all = Context.hasPrivilege(ActCorePrivileges.DOWNLOAD_DATA);
		boolean identified = all && Context.hasPrivilege(ActCorePrivileges.DOWNLOAD_IDENTIFIERS);
		boolean researchOnly = !all;
		boolean perPatient = choices.size() > 1 || rowPerPatient;
		FormDownloadChoice only = choices.get(0);
		FormDownload.Table table = perPatient
		        ? new FormDownload().buildPerPatient(choices, from, to, identified, researchOnly, filters, patientFields)
		        : new FormDownload().build(only.getForm(), only.getConcepts(), only.getLabels(), from, to, identified,
		            researchOnly, filters, patientFields);
		List<String> withheld = new ArrayList<String>();
		table.withheldPatient.forEach(key -> withheld.add(ActCoreConstants.DOWNLOAD_PATIENT_FIELDS.get(key)));
		withheld.addAll(table.withheld);
		List<String> given = new ArrayList<String>();
		List<Map<String, Object>> forms = new ArrayList<Map<String, Object>>();
		for (FormDownloadChoice choice : choices) {
			List<String> fields = new ArrayList<String>();
			for (String label : choice.getLabels()) {
				if (!table.withheld.contains(perPatient ? choice.getName() + ": " + label : label)) {
					fields.add(label);
					given.add(choices.size() > 1 ? choice.getName() + ": " + label : label);
				}
			}
			Map<String, Object> form = new LinkedHashMap<String, Object>();
			form.put("uuid", choice.getForm().getUuid());
			form.put("name", choice.getName());
			form.put("fields", fields);
			forms.add(form);
		}
		Map<String, Object> shown = new LinkedHashMap<String, Object>();
		shown.put("status", filters.getStatus());
		shown.put("cardiacClinic", filters.getCardiacClinic() == null ? null : filters.getCardiacClinic().getName());
		shown.put("primaryCareClinic",
		    filters.getPrimaryCareClinic() == null ? null : filters.getPrimaryCareClinic().getName());
		Map<String, Object> detail = new LinkedHashMap<String, Object>();
		detail.put("fields", given);
		detail.put("withheld", withheld);
		detail.put("startDate", from == null ? null : from.toString());
		detail.put("endDate", to == null ? null : to.toString());
		detail.put("rows", table.rows.size());
		detail.put("identifiers", table.identifiers);
		detail.put("researchOnly", researchOnly);
		detail.put("forms", forms);
		detail.put("rowPerPatient", perPatient);
		detail.put("filters", shown);
		detail.put("patient", table.patient);
		String json;
		try {
			json = new ObjectMapper().writeValueAsString(detail);
		}
		catch (JsonProcessingException e) {
			throw new APIException("The record of the download cannot be written as JSON", e);
		}
		// Recorded before the rows are returned, so no download goes unrecorded.
		Context.getService(DownloadLogService.class).recordDownload(choices.size() == 1 ? only.getForm().getFormId() : null,
		    json);
		return new FormDownloadTable(table.headers, table.rows, table.identifiers, researchOnly, withheld);
	}
}
