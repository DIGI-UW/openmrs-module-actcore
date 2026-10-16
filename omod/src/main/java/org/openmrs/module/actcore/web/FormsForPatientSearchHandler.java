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

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.openmrs.Form;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.webservices.rest.web.RequestContext;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.openmrs.module.webservices.rest.web.resource.api.PageableResult;
import org.openmrs.module.webservices.rest.web.resource.api.SearchConfig;
import org.openmrs.module.webservices.rest.web.resource.api.SearchHandler;
import org.openmrs.module.webservices.rest.web.resource.api.SearchQuery;
import org.openmrs.module.webservices.rest.web.resource.impl.NeedsPaging;
import org.openmrs.module.webservices.rest.web.response.ObjectNotFoundException;
import org.springframework.stereotype.Component;

/**
 * {@code GET /form?patient=<uuid>}, the patient chart forms list's {@code customFormsUrl}: its
 * forms, without the female-only forms for a patient whose gender isn't F.
 */
@Component
public class FormsForPatientSearchHandler implements SearchHandler {
	
	public static final String FEMALE_ONLY_PROPERTY = "actcore.forms.femaleOnly";
	
	private static final Pattern COMPONENT = Pattern.compile("component", Pattern.CASE_INSENSITIVE);
	
	private final SearchConfig searchConfig = new SearchConfig("actcore-patient", RestConstants.VERSION_1 + "/form",
	        Collections.singletonList("1.8.* - 9.*"),
	        new SearchQuery.Builder("The published forms to offer for a patient, female-only forms only for a woman")
	                .withRequiredParameters("patient").build());
	
	@Override
	public SearchConfig getSearchConfig() {
		return searchConfig;
	}
	
	@Override
	public PageableResult search(RequestContext context) {
		Patient patient = Context.getPatientService().getPatientByUuid(context.getParameter("patient"));
		if (patient == null) {
			throw new ObjectNotFoundException();
		}
		Set<String> hidden = "F".equals(patient.getGender()) ? Collections.emptySet() : femaleOnly();
		List<Form> forms = Context.getFormService().getAllForms(false).stream()
		        .filter(form -> offered(form) && !hidden.contains(form.getUuid())).collect(Collectors.toList());
		return new NeedsPaging<>(forms, context);
	}
	
	// The forms list's own filter, which it skips for a custom URL.
	private static boolean offered(Form form) {
		return Boolean.TRUE.equals(form.getPublished()) && !COMPONENT.matcher(form.getName()).find();
	}
	
	private static Set<String> femaleOnly() {
		String value = Context.getAdministrationService().getGlobalProperty(FEMALE_ONLY_PROPERTY);
		return new HashSet<>(Arrays.asList(StringUtils.split(StringUtils.defaultString(value), ", ")));
	}
}
