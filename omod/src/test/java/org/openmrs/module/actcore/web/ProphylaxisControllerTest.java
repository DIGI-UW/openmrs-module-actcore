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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.UUID;

import org.junit.Before;
import org.junit.Test;
import org.openmrs.GlobalProperty;
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

public class ProphylaxisControllerTest extends BaseModuleWebContextSensitiveTest {
	
	private static final String PATIENT_7 = "5946f880-b197-400b-9caa-a3c661d23041";
	
	private static final String WEIGHT = "c607c80f-1ea9-4da3-bb88-6276ce8868dd";
	
	@Autowired
	private ProphylaxisController controller;
	
	@Before
	public void setUp() {
		// The readers need each concept named; patient 7 has no prescription on any of them.
		for (String property : Arrays.asList("prescriptionConcept", "dateStartedConcept", "dateStoppedConcept",
		    "injectionDateConcept", "estimateConcept", "prescriptionDurationConcept", "consultationDateConcept",
		    "dataEntryConcept")) {
			Context.getAdministrationService()
			        .saveGlobalProperty(new GlobalProperty("actcore.adherence." + property, WEIGHT));
		}
	}
	
	@Test
	public void saysNoneForAPatientWithNoPrescription() {
		SimpleObject summary = controller.getSummary(PATIENT_7);
		
		assertEquals("none", summary.get("status"));
		assertEquals(
		    new HashSet<String>(
		            Arrays.asList("regimen", "type", "intervalDays", "lastGiven", "nextDue", "status", "onTime")),
		    summary.keySet());
		assertNull(summary.get("regimen"));
		assertNull(summary.get("onTime"));
	}
	
	@Test(expected = IllegalRequestException.class)
	public void requiresThePatient() {
		controller.getSummary(null);
	}
	
	@Test(expected = ObjectNotFoundException.class)
	public void answersNotFoundForAnUnknownPatient() {
		controller.getSummary(UUID.randomUUID().toString());
	}
	
	@Test
	public void refusesAUserWhoMayNotSeeObservations() {
		authenticateWith("Get Patients");
		try {
			controller.getSummary(PATIENT_7);
			throw new AssertionError("expected the request to be refused");
		}
		catch (APIAuthenticationException e) {
			// The REST layer answers this exception, unlike requirePrivilege's, with 403 rather than 500.
			assertTrue(e.getMessage().contains("Get Observations"));
		}
	}
	
	private void authenticateWith(String... privileges) {
		UserService users = Context.getUserService();
		Role role = new Role("prophylaxis reader " + UUID.randomUUID());
		for (String name : privileges) {
			Privilege privilege = users.getPrivilege(name);
			role.addPrivilege(privilege != null ? privilege : users.savePrivilege(new Privilege(name)));
		}
		users.saveRole(role);
		Person person = new Person();
		person.addName(new PersonName("Prophylaxis", null, "Reader"));
		person.setGender("F");
		User user = new User(person);
		user.setUsername("prophylaxisreader");
		user.addRole(role);
		users.createUser(user, "Prophylaxis123");
		Context.logout();
		Context.authenticate("prophylaxisreader", "Prophylaxis123");
	}
}
