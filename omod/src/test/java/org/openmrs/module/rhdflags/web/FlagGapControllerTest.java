/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.rhdflags.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.Before;
import org.junit.Test;
import org.openmrs.Person;
import org.openmrs.PersonName;
import org.openmrs.Privilege;
import org.openmrs.Role;
import org.openmrs.User;
import org.openmrs.api.UserService;
import org.openmrs.api.context.Context;
import org.openmrs.module.patientflags.Flag;
import org.openmrs.module.patientflags.api.FlagService;
import org.openmrs.module.patientflags.evaluator.SQLFlagEvaluator;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.response.IllegalRequestException;
import org.openmrs.module.webservices.rest.web.response.ObjectNotFoundException;
import org.openmrs.web.test.BaseModuleWebContextSensitiveTest;
import org.springframework.beans.factory.annotation.Autowired;

public class FlagGapControllerTest extends BaseModuleWebContextSensitiveTest {
	
	private static final String PATIENT_7 = "5946f880-b197-400b-9caa-a3c661d23041";
	
	private static final String WEIGHT = "c607c80f-1ea9-4da3-bb88-6276ce8868dd";
	
	private static final String ENCOUNTER_3 = "6519d653-393b-4118-9c83-a3715b82d4ac";
	
	private static final String BASIC_FORM = "d9218f76-6c39-45f4-8efa-4c5c6c199f50";
	
	@Autowired
	private FlagGapController controller;
	
	private Flag flag;
	
	@Before
	public void setUp() {
		flag = new Flag();
		flag.setUuid(UUID.randomUUID().toString());
		flag.setName("outcome missing");
		flag.setCriteria("select p.patient_id from patient p where p.patient_id = 7");
		flag.setEvaluator(SQLFlagEvaluator.class.getName());
		flag.setMessage("outcome missing");
		flag.setEnabled(Boolean.TRUE);
		Context.getService(FlagService.class).saveFlag(flag);
	}
	
	@Test
	public void describesEachGapByEncounterFormDateAndQuestion() {
		configureOneGap();
		
		SimpleObject response = controller.getGaps(PATIENT_7, flag.getUuid());
		
		assertEquals(Boolean.TRUE, response.get("configured"));
		List<Map<String, Object>> results = response.get("results");
		assertEquals(1, results.size());
		Map<String, Object> gap = results.get(0);
		assertEquals(ENCOUNTER_3, gap.get("encounter"));
		assertTrue(gap.get("encounterDatetime").toString().startsWith("2008-08-01T00:00:00.000"));
		assertEquals(BASIC_FORM, ((Map<String, Object>) gap.get("form")).get("uuid"));
		assertEquals("Basic Form", ((Map<String, Object>) gap.get("form")).get("display"));
		assertEquals(WEIGHT, ((Map<String, Object>) gap.get("concept")).get("uuid"));
		assertEquals(Context.getConceptService().getConceptByUuid(WEIGHT).getName().getName(),
		    ((Map<String, Object>) gap.get("concept")).get("display"));
		// references only, so the response carries no form fields or concept answers
		Set<String> ref = new HashSet<String>(Arrays.asList("uuid", "display", "links"));
		assertEquals(ref, ((Map<String, Object>) gap.get("form")).keySet());
		assertEquals(ref, ((Map<String, Object>) gap.get("concept")).keySet());
	}
	
	@Test
	public void leavesTheFormOutForAUserWhoMayNotSeeForms() {
		configureOneGap();
		authenticateWith("View Patient Flags", "Get Patients", "Get Encounters", "Get Concepts");
		
		Map<String, Object> gap = ((List<Map<String, Object>>) controller.getGaps(PATIENT_7, flag.getUuid()).get("results"))
		        .get(0);
		
		assertEquals(ENCOUNTER_3, gap.get("encounter"));
		assertNull(gap.get("form"));
	}
	
	@Test
	public void saysSoWhenTheFlagHasNoGapQuery() {
		SimpleObject response = controller.getGaps(PATIENT_7, flag.getUuid());
		
		assertEquals(Boolean.FALSE, response.get("configured"));
		assertTrue(((List<?>) response.get("results")).isEmpty());
	}
	
	@Test(expected = IllegalRequestException.class)
	public void requiresThePatient() {
		controller.getGaps(null, flag.getUuid());
	}
	
	@Test(expected = IllegalRequestException.class)
	public void requiresTheFlag() {
		controller.getGaps(PATIENT_7, " ");
	}
	
	@Test(expected = ObjectNotFoundException.class)
	public void answersNotFoundForAnUnknownPatient() {
		controller.getGaps(UUID.randomUUID().toString(), flag.getUuid());
	}
	
	@Test(expected = ObjectNotFoundException.class)
	public void answersNotFoundForAnUnknownFlag() {
		controller.getGaps(PATIENT_7, UUID.randomUUID().toString());
	}
	
	private void configureOneGap() {
		flag.setCriteria("select e.patient_id, e.uuid, '" + WEIGHT + "' from encounter e where e.encounter_id = 3");
		Context.getService(FlagService.class).saveFlag(flag);
	}
	
	private void authenticateWith(String... privileges) {
		UserService users = Context.getUserService();
		Role role = new Role("gap reader " + UUID.randomUUID());
		for (String name : privileges) {
			Privilege privilege = users.getPrivilege(name);
			role.addPrivilege(privilege != null ? privilege : users.savePrivilege(new Privilege(name)));
		}
		users.saveRole(role);
		Person person = new Person();
		person.addName(new PersonName("Gap", null, "Reader"));
		person.setGender("F");
		User user = new User(person);
		user.setUsername("gapreader");
		user.addRole(role);
		users.createUser(user, "Gapreader123");
		Context.logout();
		Context.authenticate("gapreader", "Gapreader123");
	}
}
