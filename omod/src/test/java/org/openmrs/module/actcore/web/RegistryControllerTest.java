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
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.junit.Before;
import org.junit.Test;
import org.openmrs.GlobalProperty;
import org.openmrs.Location;
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
import org.openmrs.module.actcore.adherence.AdherenceRefresh;
import org.openmrs.module.actcore.registry.EnrolOnRegistration;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.util.PrivilegeConstants;
import org.openmrs.web.test.BaseModuleWebContextSensitiveTest;
import org.springframework.beans.factory.annotation.Autowired;

public class RegistryControllerTest extends BaseModuleWebContextSensitiveTest {
	
	@Autowired
	private RegistryController controller;
	
	private Program registry;
	
	@Before
	public void setUp() {
		// The module's liquibase changeset is not run by the test context, so the adherence table is made
		// here; H2 commits DDL at once, so it outlives the test and only its rows are cleared.
		Context.getAdministrationService().executeSQL("create table if not exists " + AdherenceRefresh.TABLE
		        + " (patient_id int primary key, regimen_concept_id int, injection_interval_days int, adherence double,"
		        + " last_given date, next_due date, date_computed datetime not null)",
		    false);
		Context.getAdministrationService().executeSQL("delete from " + AdherenceRefresh.TABLE, false);
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
	
	@Test
	@SuppressWarnings("unchecked")
	public void groupsActivePatientsByFacilityAndAveragesTheirAdherence() {
		Location one = Context.getLocationService().getLocation(1);
		Location two = Context.getLocationService().getLocation(2);
		enrol(Context.getPatientService().getPatient(2), one, null);
		enrol(Context.getPatientService().getPatient(6), one, null);
		enrol(Context.getPatientService().getPatient(7), two, null);
		enrol(Context.getPatientService().getPatient(8), two, new Date());
		saveAdherence(2, 0.90);
		saveAdherence(6, 0.80);
		saveAdherence(7, 0.70);
		saveAdherence(8, 0.10);
		
		List<SimpleObject> results = (List<SimpleObject>) controller.getByFacility().get("results");
		
		assertEquals(2, results.size());
		assertEquals(one.getName(), results.get(0).get("facility"));
		assertEquals(one.getUuid(), results.get(0).get("facilityUuid"));
		assertEquals(Integer.valueOf(2), results.get(0).get("patients"));
		assertEquals(0.85, (Double) results.get(0).get("adherence"), 1e-9);
		assertEquals(two.getName(), results.get(1).get("facility"));
		assertEquals(Integer.valueOf(1), results.get(1).get("patients"));
		assertEquals(0.70, (Double) results.get(1).get("adherence"), 1e-9);
	}
	
	@Test
	@SuppressWarnings("unchecked")
	public void returnsAFacilityWithNoAdherenceAsNull() {
		Location location = Context.getLocationService().getLocation(1);
		enrol(Context.getPatientService().getPatient(2), location, null);
		
		List<SimpleObject> results = (List<SimpleObject>) controller.getByFacility().get("results");
		
		assertEquals(1, results.size());
		assertEquals(Integer.valueOf(1), results.get(0).get("patients"));
		assertNull(results.get(0).get("adherence"));
	}
	
	@Test
	@SuppressWarnings("unchecked")
	public void groupsEnrolmentsWithNoFacilityIntoATrailingNullBucket() {
		Location location = Context.getLocationService().getLocation(1);
		enrol(Context.getPatientService().getPatient(2), location, null);
		enrol(Context.getPatientService().getPatient(6), null, null);
		
		List<SimpleObject> results = (List<SimpleObject>) controller.getByFacility().get("results");
		
		assertEquals(2, results.size());
		assertEquals(location.getName(), results.get(0).get("facility"));
		assertNull(results.get(1).get("facility"));
		assertNull(results.get(1).get("facilityUuid"));
	}
	
	@Test
	@SuppressWarnings("unchecked")
	public void answersEmptyWhenNoProgramIsConfigured() {
		Context.getAdministrationService().saveGlobalProperty(new GlobalProperty(EnrolOnRegistration.PROGRAM_PROPERTY, ""));
		
		List<SimpleObject> results = (List<SimpleObject>) controller.getByFacility().get("results");
		
		assertTrue(results.isEmpty());
	}
	
	@Test
	public void refusesAUserWhoMayNotSeePatientProgramsFromByFacility() {
		authenticateWith("Get Patients");
		try {
			controller.getByFacility();
			throw new AssertionError("expected the request to be refused");
		}
		catch (APIAuthenticationException e) {
			assertTrue(e.getMessage().contains("Get Patient Programs"));
		}
	}
	
	@Test
	public void refusesAUserWhoMayNotSeeLocations() {
		authenticateWith("Get Patient Programs");
		try {
			controller.getByFacility();
			throw new AssertionError("expected the request to be refused");
		}
		catch (APIAuthenticationException e) {
			assertTrue(e.getMessage().contains("Get Locations"));
		}
	}
	
	private void enrol(Patient patient, Date dateCompleted) {
		enrol(patient, null, dateCompleted);
	}
	
	private void enrol(Patient patient, Location location, Date dateCompleted) {
		PatientProgram enrolment = new PatientProgram();
		enrolment.setPatient(patient);
		enrolment.setProgram(registry);
		enrolment.setDateEnrolled(new Date());
		enrolment.setDateCompleted(dateCompleted);
		enrolment.setLocation(location);
		Context.getProgramWorkflowService().savePatientProgram(enrolment);
	}
	
	private void saveAdherence(int patientId, double adherence) {
		Context.addProxyPrivilege(PrivilegeConstants.SQL_LEVEL_ACCESS);
		try {
			Context.getAdministrationService()
			        .executeSQL(
			            "insert into " + AdherenceRefresh.TABLE + " (patient_id, adherence, date_computed) values ("
			                    + patientId + ", " + adherence + ", '" + new Timestamp(System.currentTimeMillis()) + "')",
			            false);
		}
		finally {
			Context.removeProxyPrivilege(PrivilegeConstants.SQL_LEVEL_ACCESS);
		}
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
