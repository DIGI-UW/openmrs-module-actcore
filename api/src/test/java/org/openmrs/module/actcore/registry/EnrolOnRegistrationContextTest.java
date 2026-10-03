/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.registry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Date;
import java.util.List;

import org.aopalliance.intercept.MethodInterceptor;
import org.junit.Before;
import org.junit.Test;
import org.openmrs.GlobalProperty;
import org.openmrs.Location;
import org.openmrs.Patient;
import org.openmrs.PatientIdentifier;
import org.openmrs.PatientProgram;
import org.openmrs.PersonName;
import org.openmrs.Program;
import org.openmrs.api.APIException;
import org.openmrs.api.PatientService;
import org.openmrs.api.ProgramWorkflowService;
import org.openmrs.api.context.Context;
import org.openmrs.test.BaseModuleContextSensitiveTest;
import org.openmrs.util.PrivilegeConstants;
import org.springframework.aop.framework.ProxyFactory;

public class EnrolOnRegistrationContextTest extends BaseModuleContextSensitiveTest {
	
	private Program registry;
	
	private PatientService patients;
	
	@Before
	public void setUp() {
		registry = new Program();
		registry.setName("RHD Registry");
		registry.setDescription("RHD Registry");
		registry.setConcept(Context.getConceptService().getConcept(5089));
		registry = Context.getProgramWorkflowService().saveProgram(registry);
		Context.getAdministrationService()
		        .saveGlobalProperty(new GlobalProperty(EnrolOnRegistration.PROGRAM_PROPERTY, registry.getUuid()));
		// The patient service as the module's advice wraps it at runtime.
		ProxyFactory proxy = new ProxyFactory(Context.getPatientService());
		proxy.addInterface(PatientService.class);
		proxy.addAdvice(new EnrolOnRegistration());
		patients = (PatientService) proxy.getProxy();
	}
	
	@Test
	public void savePatient_shouldEnrolANewPatientInTheRegistryAtTheSessionLocationToday() {
		Location clinic = Context.getLocationService().getLocation(2);
		Context.getUserContext().setLocation(clinic);
		
		Patient paul = patients.savePatient(newPatient());
		
		List<PatientProgram> enrolments = enrolments(paul);
		assertEquals(1, enrolments.size());
		assertEquals(clinic, enrolments.get(0).getLocation());
		assertTrue(new Date().getTime() - enrolments.get(0).getDateEnrolled().getTime() < 60000);
	}
	
	@Test
	public void savePatient_shouldNotEnrolAnExistingPatientWhenTheyAreSavedAgain() {
		Patient existing = Context.getPatientService().getPatient(2);
		int before = enrolments(existing).size();
		existing.setGender("F");
		
		patients.savePatient(existing);
		
		assertEquals(before, enrolments(existing).size());
	}
	
	@Test
	public void savePatient_shouldEnrolNobodyWhenNoProgramIsConfigured() {
		Context.getAdministrationService().saveGlobalProperty(new GlobalProperty(EnrolOnRegistration.PROGRAM_PROPERTY, ""));
		
		Patient paul = patients.savePatient(newPatient());
		
		assertEquals(0, enrolments(paul).size());
	}
	
	@Test
	public void savePatient_shouldStillRegisterThePatientWhenTheProgramDoesNotExist() {
		Context.getAdministrationService().saveGlobalProperty(
		    new GlobalProperty(EnrolOnRegistration.PROGRAM_PROPERTY, "00000000-0000-0000-0000-000000000000"));
		
		Patient paul = patients.savePatient(newPatient());
		
		assertTrue(paul.getPatientId() != null);
		assertEquals(0, enrolments(paul).size());
	}
	
	@Test
	public void savePatient_shouldEnrolANewPatientRegisteredByAUserWithoutProgramPrivileges() {
		String[] registering = { PrivilegeConstants.ADD_PATIENTS, PrivilegeConstants.GET_PATIENTS,
		        PrivilegeConstants.GET_IDENTIFIER_TYPES, PrivilegeConstants.GET_LOCATIONS };
		Context.becomeUser("butch");
		for (String privilege : registering) {
			Context.addProxyPrivilege(privilege);
		}
		
		Patient paul = patients.savePatient(newPatient());
		
		for (String privilege : registering) {
			Context.removeProxyPrivilege(privilege);
		}
		authenticate();
		assertEquals(1, enrolments(paul).size());
	}
	
	@Test
	public void savePatient_shouldStillRegisterThePatientWhenEnrolmentFails() {
		MethodInterceptor failing = invocation -> {
			if ("savePatientProgram".equals(invocation.getMethod().getName())) {
				throw new APIException("enrolment failed");
			}
			return invocation.proceed();
		};
		Context.addAdvice(ProgramWorkflowService.class, failing);
		Patient paul;
		try {
			paul = patients.savePatient(newPatient());
		}
		finally {
			Context.removeAdvice(ProgramWorkflowService.class, failing);
		}
		
		assertTrue(paul.getPatientId() != null);
		assertEquals(0, enrolments(paul).size());
	}
	
	private List<PatientProgram> enrolments(Patient patient) {
		return Context.getProgramWorkflowService().getPatientPrograms(patient, registry, null, null, null, null, false);
	}
	
	private static Patient newPatient() {
		Patient patient = new Patient();
		patient.addName(new PersonName("Paul", null, "Ocen"));
		patient.setGender("M");
		patient.setBirthdate(new Date());
		PatientIdentifier id = new PatientIdentifier("1234-4", Context.getPatientService().getPatientIdentifierType(2),
		        Context.getLocationService().getLocation(1));
		id.setPreferred(true);
		patient.addIdentifier(id);
		return patient;
	}
}
