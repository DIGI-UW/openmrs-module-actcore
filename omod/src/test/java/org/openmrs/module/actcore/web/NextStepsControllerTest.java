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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.Before;
import org.junit.Test;
import org.openmrs.Person;
import org.openmrs.PersonName;
import org.openmrs.Privilege;
import org.openmrs.Role;
import org.openmrs.User;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.UserService;
import org.openmrs.api.context.Context;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.response.IllegalRequestException;
import org.openmrs.module.webservices.rest.web.response.ObjectNotFoundException;
import org.openmrs.web.test.BaseModuleWebContextSensitiveTest;
import org.springframework.beans.factory.annotation.Autowired;

public class NextStepsControllerTest extends BaseModuleWebContextSensitiveTest {
	
	private static final String PATIENT_7 = "5946f880-b197-400b-9caa-a3c661d23041";
	
	private static final String WEIGHT = "c607c80f-1ea9-4da3-bb88-6276ce8868dd";
	
	@Autowired
	private NextStepsController controller;
	
	@Before
	public void setUp() {
		// The dose rule reads the prophylaxis summary, which needs each concept named; patient 7 has no prescription.
		for (String property : Arrays.asList("prescriptionConcept", "dateStartedConcept", "dateStoppedConcept",
		    "injectionDateConcept", "estimateConcept", "prescriptionDurationConcept", "consultationDateConcept",
		    "dataEntryConcept")) {
			Context.getAdministrationService().setGlobalProperty("actcore.adherence." + property, WEIGHT);
		}
	}
	
	@Test
	@SuppressWarnings("unchecked")
	public void listsEachStepWithItsFormReasonAndState() {
		String echo = Context.getEncounterService().getEncounterType(1).getUuid();
		Context.getAdministrationService().setGlobalProperty("actcore.nextSteps.encounterTypes", "echo:" + echo);
		
		List<SimpleObject> steps = (List<SimpleObject>) controller.getNextSteps(PATIENT_7).get("steps");
		
		assertEquals(1, steps.size());
		SimpleObject step = steps.get(0);
		assertEquals("echo", step.get("key"));
		assertEquals("Echocardiogram", step.get("title"));
		assertEquals("No echocardiogram recorded", step.get("reason"));
		assertEquals(false, step.get("isNew"));
		assertEquals(false, step.get("done"));
		assertTrue(step.containsKey("form"));
	}
	
	@Test
	public void listsNoStepsWhenNoStepsEncounterTypeExists() {
		Context.getAdministrationService().setGlobalProperty("actcore.nextSteps.encounterTypes",
		    "echo:" + UUID.randomUUID());
		
		assertEquals(Collections.emptyList(), controller.getNextSteps(PATIENT_7).get("steps"));
	}
	
	@Test(expected = IllegalRequestException.class)
	public void requiresThePatient() {
		controller.getNextSteps(null);
	}
	
	@Test(expected = ObjectNotFoundException.class)
	public void answersNotFoundForAnUnknownPatient() {
		controller.getNextSteps(UUID.randomUUID().toString());
	}
	
	@Test
	public void refusesAUserWhoMayNotSeeObservations() {
		UserService users = Context.getUserService();
		Role role = new Role("next steps reader " + UUID.randomUUID());
		Privilege privilege = users.getPrivilege("Get Patients");
		role.addPrivilege(privilege != null ? privilege : users.savePrivilege(new Privilege("Get Patients")));
		users.saveRole(role);
		Person person = new Person();
		person.addName(new PersonName("Next", null, "Reader"));
		person.setGender("F");
		User user = new User(person);
		user.setUsername("nextstepsreader");
		user.addRole(role);
		users.createUser(user, "NextSteps123");
		Context.logout();
		Context.authenticate("nextstepsreader", "NextSteps123");
		try {
			controller.getNextSteps(PATIENT_7);
			throw new AssertionError("expected the request to be refused");
		}
		catch (APIAuthenticationException e) {
			assertTrue(e.getMessage().contains("Get Observations"));
		}
	}
}
