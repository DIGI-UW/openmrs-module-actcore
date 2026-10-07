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

import java.util.Date;
import java.util.UUID;

import org.junit.Before;
import org.junit.Test;
import org.openmrs.GlobalProperty;
import org.openmrs.Patient;
import org.openmrs.PatientProgram;
import org.openmrs.Person;
import org.openmrs.PersonName;
import org.openmrs.Privilege;
import org.openmrs.Program;
import org.openmrs.Role;
import org.openmrs.User;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.UserService;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.registry.EnrolOnRegistration;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.web.test.BaseModuleWebContextSensitiveTest;
import org.springframework.beans.factory.annotation.Autowired;

public class RegistryControllerTest extends BaseModuleWebContextSensitiveTest {
	
	@Autowired
	private RegistryController controller;
	
	private Program registry;
	
	@Before
	public void setUp() {
		registry = new Program();
		registry.setName("RHD Registry " + UUID.randomUUID());
		registry.setDescription("RHD Registry");
		registry.setConcept(Context.getConceptService().getConcept(5089));
		registry = Context.getProgramWorkflowService().saveProgram(registry);
		Context.getAdministrationService()
		        .saveGlobalProperty(new GlobalProperty(EnrolOnRegistration.PROGRAM_PROPERTY, registry.getUuid()));
	}
	
	@Test
	public void countsEachActivelyEnrolledPatientOnce() {
		enrol(Context.getPatientService().getPatient(2), null);
		enrol(Context.getPatientService().getPatient(6), null);
		enrol(Context.getPatientService().getPatient(6), null);
		
		SimpleObject result = controller.getCount();
		
		assertEquals(Integer.valueOf(2), result.get("count"));
	}
	
	@Test
	public void doesNotCountCompletedEnrolments() {
		enrol(Context.getPatientService().getPatient(2), new Date());
		enrol(Context.getPatientService().getPatient(6), null);
		
		SimpleObject result = controller.getCount();
		
		assertEquals(Integer.valueOf(1), result.get("count"));
	}
	
	@Test
	public void answersZeroWhenNoProgramIsConfigured() {
		Context.getAdministrationService().saveGlobalProperty(new GlobalProperty(EnrolOnRegistration.PROGRAM_PROPERTY, ""));
		
		assertEquals(Integer.valueOf(0), controller.getCount().get("count"));
	}
	
	@Test
	public void answersZeroWhenTheProgramDoesNotExist() {
		Context.getAdministrationService().saveGlobalProperty(
		    new GlobalProperty(EnrolOnRegistration.PROGRAM_PROPERTY, "00000000-0000-0000-0000-000000000000"));
		
		assertEquals(Integer.valueOf(0), controller.getCount().get("count"));
	}
	
	@Test
	public void refusesAUserWhoMayNotSeePatientPrograms() {
		authenticateWith("Get Patients");
		try {
			controller.getCount();
			throw new AssertionError("expected the request to be refused");
		}
		catch (APIAuthenticationException e) {
			assertTrue(e.getMessage().contains("Get Patient Programs"));
		}
	}
	
	private void enrol(Patient patient, Date dateCompleted) {
		PatientProgram enrolment = new PatientProgram();
		enrolment.setPatient(patient);
		enrolment.setProgram(registry);
		enrolment.setDateEnrolled(new Date());
		enrolment.setDateCompleted(dateCompleted);
		Context.getProgramWorkflowService().savePatientProgram(enrolment);
	}
	
	private void authenticateWith(String... privileges) {
		UserService users = Context.getUserService();
		Role role = new Role("registry reader " + UUID.randomUUID());
		for (String name : privileges) {
			Privilege privilege = users.getPrivilege(name);
			role.addPrivilege(privilege != null ? privilege : users.savePrivilege(new Privilege(name)));
		}
		users.saveRole(role);
		Person person = new Person();
		person.addName(new PersonName("Registry", null, "Reader"));
		person.setGender("F");
		User user = new User(person);
		user.setUsername("registryreader");
		user.addRole(role);
		users.createUser(user, "Registry123");
		Context.logout();
		Context.authenticate("registryreader", "Registry123");
	}
}
