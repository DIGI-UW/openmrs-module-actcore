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
import static org.junit.Assert.fail;

import java.util.Date;

import org.junit.Before;
import org.junit.Test;
import org.openmrs.GlobalProperty;
import org.openmrs.Patient;
import org.openmrs.PatientProgram;
import org.openmrs.Program;
import org.openmrs.api.ProgramWorkflowService;
import org.openmrs.api.ValidationException;
import org.openmrs.api.context.Context;
import org.openmrs.test.BaseModuleContextSensitiveTest;
import org.springframework.aop.framework.ProxyFactory;

public class OneOpenRegistryEnrolmentContextTest extends BaseModuleContextSensitiveTest {
	
	private Program registry;
	
	private Program other;
	
	private Patient patient;
	
	private ProgramWorkflowService programs;
	
	@Before
	public void setUp() {
		registry = program("RHD Registry");
		other = program("Research Participation");
		Context.getAdministrationService()
		        .saveGlobalProperty(new GlobalProperty(EnrolOnRegistration.PROGRAM_PROPERTY, registry.getUuid()));
		patient = Context.getPatientService().getPatient(2);
		// The program service as the module's advice wraps it at runtime.
		ProxyFactory proxy = new ProxyFactory(Context.getProgramWorkflowService());
		proxy.addInterface(ProgramWorkflowService.class);
		proxy.addAdvice(new OneOpenRegistryEnrolment());
		programs = (ProgramWorkflowService) proxy.getProxy();
	}
	
	@Test
	public void savePatientProgram_shouldRefuseASecondOpenRegistryEnrolment() {
		programs.savePatientProgram(enrolment(registry, null));
		
		try {
			programs.savePatientProgram(enrolment(registry, null));
			fail("a second open enrolment was saved");
		}
		catch (ValidationException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("already enrolled"));
		}
		assertEquals(1, open(registry));
	}
	
	@Test
	public void savePatientProgram_shouldAllowEnrollingAgainOnceTheEnrolmentIsCompleted() {
		programs.savePatientProgram(enrolment(registry, daysAgo(30), daysAgo(1)));
		
		programs.savePatientProgram(enrolment(registry, daysAgo(1), null));
		
		assertEquals(1, open(registry));
	}
	
	@Test
	public void savePatientProgram_shouldAllowAddingACompletedEnrolmentThatEndsBeforeTheOpenOneStarts() {
		programs.savePatientProgram(enrolment(registry, daysAgo(1), null));
		
		programs.savePatientProgram(enrolment(registry, daysAgo(30), daysAgo(10)));
		
		assertEquals(2, all(registry));
	}
	
	@Test
	public void savePatientProgram_shouldAllowOverlappingCompletedEnrolments() {
		programs.savePatientProgram(enrolment(registry, daysAgo(30), daysAgo(10)));
		
		programs.savePatientProgram(enrolment(registry, daysAgo(20), daysAgo(5)));
		
		assertEquals(2, all(registry));
	}
	
	@Test
	public void savePatientProgram_shouldRefuseReopeningACompletedEnrolmentWhileAnotherIsOpen() {
		PatientProgram old = programs.savePatientProgram(enrolment(registry, daysAgo(30), daysAgo(10)));
		programs.savePatientProgram(enrolment(registry, daysAgo(1), null));
		old.setDateCompleted(null);
		
		try {
			programs.savePatientProgram(old);
			fail("a completed enrolment was reopened beside the open one");
		}
		catch (ValidationException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("already enrolled"));
		}
	}
	
	@Test
	public void savePatientProgram_shouldRefuseACompletedEnrolmentThatOverlapsTheOpenOne() {
		programs.savePatientProgram(enrolment(registry, daysAgo(10), null));
		
		try {
			programs.savePatientProgram(enrolment(registry, daysAgo(5), daysAgo(1)));
			fail("a completed enrolment overlapping the open one was saved");
		}
		catch (ValidationException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("already enrolled"));
		}
		assertEquals(1, all(registry));
	}
	
	@Test
	public void savePatientProgram_shouldRefuseAnOpenEnrolmentThatStartsBeforeACompletedOneEnds() {
		programs.savePatientProgram(enrolment(registry, daysAgo(10), daysAgo(5)));
		
		try {
			programs.savePatientProgram(enrolment(registry, daysAgo(20), null));
			fail("an open enrolment overlapping a completed one was saved");
		}
		catch (ValidationException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("overlaps"));
		}
		assertEquals(0, open(registry));
	}
	
	@Test
	public void savePatientProgram_shouldAllowEditingTheOpenEnrolment() {
		PatientProgram enrolment = programs.savePatientProgram(enrolment(registry, null));
		enrolment.setDateEnrolled(new Date(System.currentTimeMillis() - 86400000L * 30));
		
		programs.savePatientProgram(enrolment);
		
		assertEquals(1, open(registry));
	}
	
	@Test
	public void savePatientProgram_shouldLeaveOtherProgramsAlone() {
		programs.savePatientProgram(enrolment(other, null));
		programs.savePatientProgram(enrolment(other, null));
		
		assertEquals(2, open(other));
	}
	
	private int open(Program program) {
		int n = 0;
		for (PatientProgram pp : Context.getProgramWorkflowService().getPatientPrograms(patient, program, null, null, null,
		    null, false)) {
			n += pp.getDateCompleted() == null ? 1 : 0;
		}
		return n;
	}
	
	private int all(Program program) {
		return Context.getProgramWorkflowService().getPatientPrograms(patient, program, null, null, null, null, false)
		        .size();
	}
	
	private PatientProgram enrolment(Program program, Date completed) {
		return enrolment(program, daysAgo(1), completed);
	}
	
	private PatientProgram enrolment(Program program, Date enrolled, Date completed) {
		PatientProgram pp = new PatientProgram();
		pp.setPatient(patient);
		pp.setProgram(program);
		pp.setDateEnrolled(enrolled);
		pp.setDateCompleted(completed);
		return pp;
	}
	
	private static Date daysAgo(int days) {
		return new Date(System.currentTimeMillis() - 86400000L * days);
	}
	
	private static Program program(String name) {
		Program program = new Program();
		program.setName(name);
		program.setDescription(name);
		program.setConcept(Context.getConceptService().getConcept(5089));
		return Context.getProgramWorkflowService().saveProgram(program);
	}
}
