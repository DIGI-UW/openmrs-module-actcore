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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.openmrs.Form;
import org.openmrs.GlobalProperty;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.v1_0.controller.MainResourceController;
import org.openmrs.web.test.BaseModuleWebContextSensitiveTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

public class FormsForPatientSearchHandlerTest extends BaseModuleWebContextSensitiveTest {
	
	@Autowired
	@Qualifier("mainResourceController")
	private MainResourceController controller;
	
	private Form pregnancy;
	
	@Before
	public void setUp() {
		pregnancy = form("RHD Pregnancy", true);
		form("RHD Consultation Visit", true);
		form("RHD Draft", false);
		form("Vitals component", true);
		Context.getAdministrationService().saveGlobalProperty(
		    new GlobalProperty(FormsForPatientSearchHandler.FEMALE_ONLY_PROPERTY, pregnancy.getUuid()));
	}
	
	@Test
	public void leavesFemaleOnlyFormsOutForAPatientWhoIsNotFemale() throws Exception {
		List<String> forms = formNames(patientOfGender("M"));
		
		assertTrue(forms.contains("RHD Consultation Visit"));
		assertFalse(forms.contains("RHD Pregnancy"));
		assertFalse(formNames(patientOfGender("U")).contains("RHD Pregnancy"));
	}
	
	@Test
	public void offersFemaleOnlyFormsForAFemalePatient() throws Exception {
		List<String> forms = formNames(patientOfGender("F"));
		
		assertTrue(forms.contains("RHD Pregnancy"));
		assertTrue(forms.contains("RHD Consultation Visit"));
	}
	
	@Test
	public void leavesOutUnpublishedAndComponentFormsAsTheFormsListDoes() throws Exception {
		List<String> forms = formNames(patientOfGender("F"));
		
		assertFalse(forms.contains("RHD Draft"));
		assertFalse(forms.contains("Vitals component"));
	}
	
	@Test
	public void offersEveryFormWhenNoFormIsFemaleOnly() throws Exception {
		Context.getAdministrationService()
		        .saveGlobalProperty(new GlobalProperty(FormsForPatientSearchHandler.FEMALE_ONLY_PROPERTY, ""));
		
		assertTrue(formNames(patientOfGender("M")).contains("RHD Pregnancy"));
	}
	
	private List<String> formNames(Patient patient) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/rest/v1/form");
		request.addParameter("patient", patient.getUuid());
		request.addParameter("v", "custom:(uuid,name,display,published)");
		SimpleObject response = (SimpleObject) controller.get("form", request, new MockHttpServletResponse());
		List<String> names = new ArrayList<>();
		for (Object result : (List<?>) response.get("results")) {
			names.add((String) ((Map<?, ?>) result).get("name"));
		}
		return names;
	}
	
	private static Patient patientOfGender(String gender) {
		Patient patient = Context.getPatientService().getPatient(2);
		patient.setGender(gender);
		return Context.getPatientService().savePatient(patient);
	}
	
	private static Form form(String name, boolean published) {
		Form form = new Form();
		form.setName(name);
		form.setVersion("1.0");
		form.setPublished(published);
		return Context.getFormService().saveForm(form);
	}
}
