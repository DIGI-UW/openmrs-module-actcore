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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.aopalliance.intercept.MethodInterceptor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openmrs.GlobalProperty;
import org.openmrs.Location;
import org.openmrs.Patient;
import org.openmrs.PatientIdentifier;
import org.openmrs.PatientProgram;
import org.openmrs.PersonName;
import org.openmrs.Program;
import org.openmrs.api.PatientService;
import org.openmrs.api.ProgramWorkflowService;
import org.openmrs.api.ValidationException;
import org.openmrs.api.context.Context;
import org.openmrs.test.BaseModuleContextSensitiveTest;
import org.openmrs.util.PrivilegeConstants;
import org.springframework.aop.framework.ProxyFactory;

public class OneOpenRegistryEnrolmentContextTest extends BaseModuleContextSensitiveTest {
	
	private static final String[] MERGE_PRIVILEGES = { PrivilegeConstants.EDIT_PATIENTS, PrivilegeConstants.GET_ORDERS,
	        PrivilegeConstants.GET_VISITS, PrivilegeConstants.GET_ENCOUNTERS, PrivilegeConstants.GET_PATIENT_PROGRAMS,
	        PrivilegeConstants.ADD_PATIENT_PROGRAMS, PrivilegeConstants.GET_RELATIONSHIPS, PrivilegeConstants.GET_OBS,
	        PrivilegeConstants.DELETE_PATIENTS, PrivilegeConstants.GET_USERS, PrivilegeConstants.GET_PATIENT_COHORTS,
	        PrivilegeConstants.EDIT_PERSONS, PrivilegeConstants.GET_PATIENTS };
	
	private Program registry;
	
	private Program other;
	
	private Patient patient;
	
	private ProgramWorkflowService programs;
	
	private final EnrolOnRegistration enrolOnRegistration = new EnrolOnRegistration();
	
	private final OneOpenRegistryEnrolment guard = new OneOpenRegistryEnrolment();
	
	private final AtomicInteger voids = new AtomicInteger();
	
	// Fails a fold that never ends, which dropping its remove would make, instead of hanging the build.
	private final MethodInterceptor stopper = invocation -> {
		if ("voidPatientProgram".equals(invocation.getMethod().getName()) && voids.incrementAndGet() > 2) {
			throw new AssertionError("the fold voided more than two enrolments");
		}
		return invocation.proceed();
	};
	
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
	public void savePatientProgram_shouldAllowEnrollingAgainTheInstantTheEnrolmentIsCompleted() {
		Date completed = daysAgo(1);
		programs.savePatientProgram(enrolment(registry, daysAgo(30), completed));
		
		programs.savePatientProgram(enrolment(registry, completed, null));
		
		assertEquals(1, open(registry));
	}
	
	@Test
	public void savePatientProgram_shouldAllowAddingACompletedEnrolmentThatEndsTheInstantTheOpenOneStarts() {
		Date enrolled = daysAgo(1);
		programs.savePatientProgram(enrolment(registry, enrolled, null));
		
		programs.savePatientProgram(enrolment(registry, daysAgo(30), enrolled));
		
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
	
	@Test
	public void mergePatients_shouldKeepTheEarlierOfTwoOpenRegistryEnrolments() throws Exception {
		adviseAsTheModuleDoes();
		Patient preferred = register("M-1");
		Patient duplicate = register("M-2");
		Date enrolled = daysAgo(30);
		Location clinic = Context.getLocationService().getLocation(2);
		PatientProgram earlier = registryEnrolments(duplicate).get(0);
		earlier.setDateEnrolled(enrolled);
		earlier.setLocation(clinic);
		Context.getProgramWorkflowService().savePatientProgram(earlier);
		
		Context.getPatientService().mergePatients(preferred, duplicate);
		
		List<PatientProgram> left = registryEnrolments(preferred);
		assertEquals(1, left.size());
		assertNull(left.get(0).getDateCompleted());
		assertEquals(enrolled.getTime(), left.get(0).getDateEnrolled().getTime());
		assertEquals(clinic, left.get(0).getLocation());
	}
	
	@Test
	public void mergePatients_shouldFoldTheOpenEnrolmentsOfTwoDuplicatesMergedAtOnce() throws Exception {
		adviseAsTheModuleDoes();
		Patient preferred = register("M-1");
		Patient first = register("M-2");
		Patient second = register("M-3");
		
		Context.getPatientService().mergePatients(preferred, Arrays.asList(first, second));
		
		List<PatientProgram> left = registryEnrolments(preferred);
		assertEquals(1, left.size());
		assertNull(left.get(0).getDateCompleted());
		List<PatientProgram> withVoided = Context.getProgramWorkflowService().getPatientPrograms(preferred, registry, null,
		    null, null, null, true);
		assertEquals(3, withVoided.size());
	}
	
	@Test
	public void mergePatients_shouldLeaveAnOpenEnrolmentInAnotherProgramAlone() throws Exception {
		adviseAsTheModuleDoes();
		Patient preferred = register("M-1");
		Patient duplicate = register("M-2");
		PatientProgram research = enrolment(other, null);
		research.setPatient(preferred);
		Context.getProgramWorkflowService().savePatientProgram(research);
		
		Context.getPatientService().mergePatients(preferred, duplicate);
		
		assertEquals(1, registryEnrolments(preferred).size());
		assertEquals(1,
		    Context.getProgramWorkflowService().getPatientPrograms(preferred, other, null, null, null, null, false).size());
	}
	
	@Test
	public void mergePatients_shouldFoldEnrolmentsForAUserWithoutDeletePatientPrograms() throws Exception {
		adviseAsTheModuleDoes();
		Patient preferred = register("M-1");
		Patient duplicate = register("M-2");
		Context.becomeUser("butch");
		for (String privilege : MERGE_PRIVILEGES) {
			Context.addProxyPrivilege(privilege);
		}
		try {
			Context.getPatientService().mergePatients(preferred, duplicate);
		}
		finally {
			for (String privilege : MERGE_PRIVILEGES) {
				Context.removeProxyPrivilege(privilege);
			}
			authenticate();
		}
		
		assertEquals(1, registryEnrolments(preferred).size());
	}
	
	@Test
	public void savePatientProgram_shouldEnrolForAUserWithoutGetPatientPrograms() {
		Context.becomeUser("butch");
		Context.addProxyPrivilege(PrivilegeConstants.ADD_PATIENT_PROGRAMS);
		try {
			programs.savePatientProgram(enrolment(registry, null));
		}
		finally {
			Context.removeProxyPrivilege(PrivilegeConstants.ADD_PATIENT_PROGRAMS);
			authenticate();
		}
		
		assertEquals(1, open(registry));
	}
	
	@Test
	public void voidPatientProgram_shouldVoidOneOfTwoOverlappingEnrolments() {
		Context.getProgramWorkflowService().savePatientProgram(enrolment(registry, null));
		PatientProgram duplicate = Context.getProgramWorkflowService().savePatientProgram(enrolment(registry, null));
		adviseAsTheModuleDoes();
		
		Context.getProgramWorkflowService().voidPatientProgram(duplicate, "duplicate");
		
		assertEquals(1, open(registry));
	}
	
	@Test
	public void unvoidPatientProgram_shouldRefuseRestoringAnEnrolmentThatOverlapsTheOpenOne() {
		Context.getProgramWorkflowService().savePatientProgram(enrolment(registry, null));
		PatientProgram duplicate = Context.getProgramWorkflowService().savePatientProgram(enrolment(registry, null));
		Context.getProgramWorkflowService().voidPatientProgram(duplicate, "duplicate");
		adviseAsTheModuleDoes();
		
		try {
			Context.getProgramWorkflowService().unvoidPatientProgram(duplicate);
			fail("a voided enrolment was restored beside the open one");
		}
		catch (ValidationException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("already enrolled"));
		}
	}
	
	@Test
	public void mergePatients_shouldFoldACompletedEnrolmentIntoTheOpenOneItOverlaps() throws Exception {
		adviseAsTheModuleDoes();
		Patient preferred = register("M-1");
		Patient duplicate = register("M-2");
		Date enrolled = daysAgo(30);
		PatientProgram completed = registryEnrolments(preferred).get(0);
		completed.setDateEnrolled(enrolled);
		completed.setDateCompleted(daysAgo(10));
		Context.getProgramWorkflowService().savePatientProgram(completed);
		PatientProgram open = registryEnrolments(duplicate).get(0);
		open.setDateEnrolled(daysAgo(20));
		Context.getProgramWorkflowService().savePatientProgram(open);
		
		Context.getPatientService().mergePatients(preferred, duplicate);
		
		List<PatientProgram> left = registryEnrolments(preferred);
		assertEquals(1, left.size());
		assertNull(left.get(0).getDateCompleted());
		assertEquals(enrolled.getTime(), left.get(0).getDateEnrolled().getTime());
	}
	
	@Test
	public void mergePatients_shouldKeepACompletedEnrolmentThatEndedBeforeTheOpenOneStarted() throws Exception {
		adviseAsTheModuleDoes();
		Patient preferred = register("M-1");
		Patient duplicate = register("M-2");
		PatientProgram past = registryEnrolments(preferred).get(0);
		past.setDateEnrolled(daysAgo(30));
		past.setDateCompleted(daysAgo(10));
		Context.getProgramWorkflowService().savePatientProgram(past);
		
		Context.getPatientService().mergePatients(preferred, duplicate);
		
		assertEquals(2, registryEnrolments(preferred).size());
	}
	
	@Test
	public void mergePatients_shouldStillRefuseASecondOpenEnrolmentOnceTheMergeIsDone() throws Exception {
		adviseAsTheModuleDoes();
		Patient preferred = register("M-1");
		Patient duplicate = register("M-2");
		Context.getPatientService().mergePatients(preferred, duplicate);
		PatientProgram second = enrolment(registry, null);
		second.setPatient(preferred);
		
		try {
			Context.getProgramWorkflowService().savePatientProgram(second);
			fail("a second open enrolment was saved after the merge");
		}
		catch (ValidationException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("already enrolled"));
		}
	}
	
	private void adviseAsTheModuleDoes() {
		Context.addAdvice(PatientService.class, enrolOnRegistration);
		Context.addAdvice(PatientService.class, guard);
		Context.addAdvice(ProgramWorkflowService.class, guard);
		Context.addAdvice(ProgramWorkflowService.class, stopper);
	}
	
	@After
	public void removeModuleAdvice() {
		Context.removeAdvice(PatientService.class, enrolOnRegistration);
		Context.removeAdvice(PatientService.class, guard);
		Context.removeAdvice(ProgramWorkflowService.class, guard);
		Context.removeAdvice(ProgramWorkflowService.class, stopper);
	}
	
	private List<PatientProgram> registryEnrolments(Patient patient) {
		return Context.getProgramWorkflowService().getPatientPrograms(patient, registry, null, null, null, null, false);
	}
	
	private static Patient register(String identifier) {
		Patient patient = new Patient();
		patient.addName(new PersonName("Paul", null, "Ocen"));
		patient.setGender("M");
		patient.setBirthdate(daysAgo(3650));
		PatientIdentifier id = new PatientIdentifier(identifier, Context.getPatientService().getPatientIdentifierType(2),
		        Context.getLocationService().getLocation(1));
		id.setPreferred(true);
		patient.addIdentifier(id);
		return Context.getPatientService().savePatient(patient);
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
		// Whole seconds, as core stores dates, so a date shared by two enrolments stays equal after a flush.
		return new Date((System.currentTimeMillis() / 1000 - 86400L * days) * 1000);
	}
	
	private static Program program(String name) {
		Program program = new Program();
		program.setName(name);
		program.setDescription(name);
		program.setConcept(Context.getConceptService().getConcept(5089));
		return Context.getProgramWorkflowService().saveProgram(program);
	}
}
