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

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.UUID;

import org.junit.Before;
import org.junit.Test;
import org.openmrs.Concept;
import org.openmrs.ConceptName;
import org.openmrs.Encounter;
import org.openmrs.Obs;
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
			property(property, WEIGHT);
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
	
	@Test
	public void describesAnOverdueInjectionRegimenWithItsDatesAndOnTimeCount() {
		Concept prescription = concept("Prophylaxis", "Coded");
		Concept q21 = concept("Q21 day BPG", "N/A");
		Concept started = concept("Date started", "Datetime");
		Concept injection = concept("Date of injection", "Datetime");
		property("prescriptionConcept", prescription.getUuid());
		property("dateStartedConcept", started.getUuid());
		property("injectionDateConcept", injection.getUuid());
		property("injectionIntervals", q21.getUuid() + ":21");
		Encounter consultation = new Encounter();
		consultation.setPatient(Context.getPatientService().getPatientByUuid(PATIENT_7));
		consultation.setEncounterType(Context.getEncounterService().getEncounterType(1));
		consultation.setLocation(Context.getLocationService().getLocation(1));
		consultation.setEncounterDatetime(daysAgo(27));
		Context.getEncounterService().saveEncounter(consultation);
		Obs group = obs(consultation, prescription);
		Obs regimen = obs(consultation, prescription);
		regimen.setValueCoded(q21);
		group.addGroupMember(regimen);
		Obs start = obs(consultation, started);
		start.setValueDatetime(daysAgo(27));
		group.addGroupMember(start);
		Context.getObsService().saveObs(group, null);
		Obs given = obs(consultation, injection);
		given.setValueDatetime(daysAgo(27));
		Context.getObsService().saveObs(given, null);
		
		SimpleObject summary = controller.getSummary(PATIENT_7);
		
		assertEquals(q21.getName().getName(), summary.get("regimen"));
		assertEquals("BPG", summary.get("type"));
		assertEquals(Integer.valueOf(21), summary.get("intervalDays"));
		assertEquals(LocalDate.now().minusDays(27).toString(), summary.get("lastGiven"));
		assertEquals(LocalDate.now().minusDays(6).toString(), summary.get("nextDue"));
		assertEquals("overdue", summary.get("status"));
		SimpleObject onTime = (SimpleObject) summary.get("onTime");
		assertEquals(Integer.valueOf(1), onTime.get("given"));
		assertEquals(Integer.valueOf(1), onTime.get("total"));
		assertEquals(Integer.valueOf(6), onTime.get("months"));
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
			assertTrue(e.getMessage().contains("Get Observations"));
		}
	}
	
	private static void property(String name, String value) {
		Context.getAdministrationService().setGlobalProperty("actcore.adherence." + name, value);
	}
	
	private static Concept concept(String name, String datatype) {
		Concept concept = new Concept();
		concept.addName(new ConceptName(name + " " + System.nanoTime(), Context.getLocale()));
		concept.setDatatype(Context.getConceptService().getConceptDatatypeByName(datatype));
		concept.setConceptClass(Context.getConceptService().getConceptClass(1));
		return Context.getConceptService().saveConcept(concept);
	}
	
	private static Obs obs(Encounter encounter, Concept concept) {
		Obs obs = new Obs(encounter.getPatient(), concept, encounter.getEncounterDatetime(), encounter.getLocation());
		obs.setEncounter(encounter);
		return obs;
	}
	
	private static Date daysAgo(int days) {
		return Timestamp.valueOf(LocalDate.now().minusDays(days).atTime(10, 0));
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
