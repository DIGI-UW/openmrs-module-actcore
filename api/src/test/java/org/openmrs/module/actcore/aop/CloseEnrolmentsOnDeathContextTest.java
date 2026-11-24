/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.aop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openmrs.Concept;
import org.openmrs.ConceptName;
import org.openmrs.GlobalProperty;
import org.openmrs.Patient;
import org.openmrs.PatientIdentifier;
import org.openmrs.PatientProgram;
import org.openmrs.PatientState;
import org.openmrs.PersonName;
import org.openmrs.Program;
import org.openmrs.ProgramWorkflow;
import org.openmrs.ProgramWorkflowState;
import org.openmrs.api.PatientService;
import org.openmrs.api.PersonService;
import org.openmrs.api.ProgramWorkflowService;
import org.openmrs.api.ValidationException;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.ActCoreConstants;
import org.openmrs.test.BaseModuleContextSensitiveTest;

public class CloseEnrolmentsOnDeathContextTest extends BaseModuleContextSensitiveTest {
	
	private static final long TODAY = System.currentTimeMillis() / 1000 * 1000;
	
	private final EnrolOnRegistration enrolOnRegistration = new EnrolOnRegistration();
	
	private final CloseEnrolmentsOnDeath closeOnDeath = new CloseEnrolmentsOnDeath();
	
	private final OneOpenRegistryEnrolment guard = new OneOpenRegistryEnrolment();
	
	private Program registry;
	
	private ProgramWorkflowState active;
	
	private ProgramWorkflowState deceased;
	
	@Before
	public void setUp() {
		Concept deceasedConcept = concept("Deceased");
		ProgramWorkflow workflow = new ProgramWorkflow();
		workflow.setConcept(concept("RHD Registry status"));
		active = state(concept("Active in the registry"), true, false);
		deceased = state(deceasedConcept, false, true);
		workflow.addState(active);
		workflow.addState(deceased);
		workflow.addState(state(concept("Lost to follow-up"), false, true));
		registry = new Program();
		registry.setName("RHD Registry");
		registry.setDescription("RHD Registry");
		registry.setConcept(concept("RHD Registry program"));
		registry.addWorkflow(workflow);
		registry = Context.getProgramWorkflowService().saveProgram(registry);
		property(ActCoreConstants.GP_REGISTRY_PROGRAM, registry.getUuid());
		property(ActCoreConstants.GP_REGISTRY_DEATH_STATE_CONCEPT, deceasedConcept.getUuid());
		// As config.xml orders them: an earlier advice wraps a later one.
		Context.addAdvice(PatientService.class, enrolOnRegistration);
		Context.addAdvice(PatientService.class, closeOnDeath);
		Context.addAdvice(PatientService.class, guard);
		Context.addAdvice(ProgramWorkflowService.class, guard);
		Context.addAdvice(PersonService.class, closeOnDeath);
	}
	
	@After
	public void tearDown() {
		Context.removeAdvice(PatientService.class, enrolOnRegistration);
		Context.removeAdvice(PatientService.class, closeOnDeath);
		Context.removeAdvice(PatientService.class, guard);
		Context.removeAdvice(ProgramWorkflowService.class, guard);
		Context.removeAdvice(PersonService.class, closeOnDeath);
	}
	
	@Test
	public void savePerson_shouldEndTheOpenRegistryEnrolmentOfAPatientWhoDiedInDeceasedOnTheirDeathDate() {
		Patient patient = Context.getPatientService().getPatient(2);
		PatientProgram enrolment = enrol(patient, daysAgo(30));
		Date died = daysAgo(10);
		
		markDead(patient, died);
		
		assertEndedInDeceasedOn(enrolment, died);
	}
	
	@Test
	public void savePerson_shouldLeaveTheRegistryEnrolmentOpenWhenNoDeathStateIsConfigured() {
		property(ActCoreConstants.GP_REGISTRY_DEATH_STATE_CONCEPT, "");
		Patient patient = Context.getPatientService().getPatient(2);
		PatientProgram enrolment = enrol(patient, daysAgo(30));
		
		markDead(patient, daysAgo(10));
		
		assertNull(enrolment.getDateCompleted());
	}
	
	@Test
	public void savePatientProgram_shouldRefuseAnOpenRegistryEnrolmentForAPatientWhoHasDied() {
		Patient patient = Context.getPatientService().getPatient(2);
		markDead(patient, daysAgo(10));
		
		try {
			Context.getProgramWorkflowService().savePatientProgram(enrolment(patient, daysAgo(5), null));
			fail("an open RHD Registry enrolment was saved for a patient who has died");
		}
		catch (ValidationException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("has died"));
		}
	}
	
	@Test
	public void savePatientProgram_shouldRefuseReopeningTheEndedRegistryEnrolmentOfAPatientWhoHasDied() {
		Patient patient = Context.getPatientService().getPatient(2);
		PatientProgram enrolment = enrol(patient, daysAgo(30));
		markDead(patient, daysAgo(10));
		assertNotNull(enrolment.getDateCompleted());
		
		enrolment.setDateCompleted(null);
		try {
			Context.getProgramWorkflowService().savePatientProgram(enrolment);
			fail("the ended RHD Registry enrolment of a patient who has died was reopened");
		}
		catch (ValidationException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("has died"));
		}
	}
	
	@Test
	public void savePatientProgram_shouldAcceptACompletedRegistryEnrolmentForAPatientWhoHasDied() {
		Patient patient = Context.getPatientService().getPatient(2);
		markDead(patient, daysAgo(10));
		
		PatientProgram history = Context.getProgramWorkflowService()
		        .savePatientProgram(enrolment(patient, daysAgo(400), daysAgo(300)));
		
		assertNotNull(history.getPatientProgramId());
	}
	
	@Test
	public void savePatientProgram_shouldAcceptAnOpenRegistryEnrolmentForTheDeadWhenNoDeathStateIsConfigured() {
		Patient patient = Context.getPatientService().getPatient(2);
		markDead(patient, daysAgo(10));
		property(ActCoreConstants.GP_REGISTRY_DEATH_STATE_CONCEPT, "");
		
		PatientProgram open = Context.getProgramWorkflowService().savePatientProgram(enrolment(patient, daysAgo(5), null));
		
		assertNull(open.getDateCompleted());
	}
	
	@Test
	public void savePatientProgram_shouldAcceptAnOpenRegistryEnrolmentForTheDeadWhenTheWorkflowHasNoDeathState() {
		Patient patient = Context.getPatientService().getPatient(2);
		markDead(patient, daysAgo(10));
		property(ActCoreConstants.GP_REGISTRY_DEATH_STATE_CONCEPT, concept("Not a registry state").getUuid());
		
		PatientProgram open = Context.getProgramWorkflowService().savePatientProgram(enrolment(patient, daysAgo(5), null));
		
		assertNull(open.getDateCompleted());
	}
	
	@Test
	public void closeEnrolmentsOfTheDead_shouldLeaveEnrolmentsInProgramsWithNoDeathStateAlone() {
		Program other = new Program();
		other.setName("Other program");
		other.setDescription("Other program");
		other.setConcept(concept("Other program concept"));
		other = Context.getProgramWorkflowService().saveProgram(other);
		Patient dead = Context.getPatientService().getPatient(2);
		PatientProgram elsewhere = enrolment(dead, daysAgo(30), null);
		elsewhere.setProgram(other);
		Context.getProgramWorkflowService().savePatientProgram(elsewhere);
		markDead(dead, daysAgo(10));
		
		assertEquals(0, CloseEnrolmentsOnDeath.closeEnrolmentsOfTheDead());
		
		assertNull(
		    Context.getProgramWorkflowService().getPatientProgram(elsewhere.getPatientProgramId()).getDateCompleted());
	}
	
	@Test
	public void savePatient_shouldEndTheRegistryEnrolmentOfAPatientRegisteredAfterTheyDied() {
		Date died = daysAgo(10);
		Patient patient = newPatient();
		patient.setDead(true);
		patient.setDeathDate(died);
		patient.setCauseOfDeath(Context.getConceptService().getConcept(3));
		
		Context.getPatientService().savePatient(patient);
		
		List<PatientProgram> enrolments = enrolments(patient);
		assertEquals(1, enrolments.size());
		PatientProgram enrolment = enrolments.get(0);
		assertNotNull(enrolment.getDateCompleted());
		// Registered today, after the death, so it ends on the day it starts.
		assertEquals(enrolment.getDateEnrolled().getTime(), enrolment.getDateCompleted().getTime());
		assertEquals(deceased, current(enrolment).getState());
	}
	
	@Test
	public void savePerson_shouldKeepOneDeceasedStateWhenTheEnrolmentWasEndedInDeceasedBeforeTheDeathWasSaved() {
		Patient patient = Context.getPatientService().getPatient(2);
		PatientProgram enrolment = enrol(patient, daysAgo(30));
		Date died = daysAgo(10);
		enrolment.transitionToState(deceased, died);
		enrolment.setDateCompleted(died);
		Context.getProgramWorkflowService().savePatientProgram(enrolment);
		
		markDead(patient, died);
		
		assertEndedInDeceasedOn(enrolment, died);
	}
	
	@Test
	public void closeEnrolmentsOfTheDead_shouldEndTheOpenEnrolmentsOfPatientsAlreadyDeadAndLeaveTheLivingAlone() {
		Patient dead = Context.getPatientService().getPatient(2);
		PatientProgram deadsEnrolment = enrol(dead, daysAgo(30));
		Patient living = Context.getPatientService().getPatient(6);
		PatientProgram livingsEnrolment = enrol(living, daysAgo(30));
		Date died = daysAgo(10);
		// Recorded dead before this module ended enrolments on death, as an import or ACT 2.0 did.
		property(ActCoreConstants.GP_REGISTRY_DEATH_STATE_CONCEPT, "");
		markDead(dead, died);
		property(ActCoreConstants.GP_REGISTRY_DEATH_STATE_CONCEPT, deceased.getConcept().getUuid());
		assertNull(deadsEnrolment.getDateCompleted());
		
		assertEquals(1, CloseEnrolmentsOnDeath.closeEnrolmentsOfTheDead());
		assertEquals(0, CloseEnrolmentsOnDeath.closeEnrolmentsOfTheDead());
		
		assertEndedInDeceasedOn(Context.getProgramWorkflowService().getPatientProgram(deadsEnrolment.getPatientProgramId()),
		    died);
		assertNull(Context.getProgramWorkflowService().getPatientProgram(livingsEnrolment.getPatientProgramId())
		        .getDateCompleted());
	}
	
	private PatientProgram enrol(Patient patient, Date enrolled) {
		PatientProgram enrolment = enrolment(patient, enrolled, null);
		enrolment.transitionToState(active, enrolled);
		return Context.getProgramWorkflowService().savePatientProgram(enrolment);
	}
	
	private PatientProgram enrolment(Patient patient, Date enrolled, Date completed) {
		PatientProgram enrolment = new PatientProgram();
		enrolment.setPatient(patient);
		enrolment.setProgram(registry);
		enrolment.setDateEnrolled(enrolled);
		enrolment.setDateCompleted(completed);
		return enrolment;
	}
	
	private List<PatientProgram> enrolments(Patient patient) {
		return Context.getProgramWorkflowService().getPatientPrograms(patient, registry, null, null, null, null, false);
	}
	
	private void assertEndedInDeceasedOn(PatientProgram enrolment, Date date) {
		assertNotNull("the enrolment was not ended", enrolment.getDateCompleted());
		assertEquals(date.getTime(), enrolment.getDateCompleted().getTime());
		List<PatientState> deaths = enrolment.getStates().stream()
		        .filter(state -> !state.getVoided() && deceased.equals(state.getState())).collect(Collectors.toList());
		assertEquals(1, deaths.size());
		assertEquals(date.getTime(), deaths.get(0).getStartDate().getTime());
	}
	
	private static PatientState current(PatientProgram enrolment) {
		return enrolment.getStates().stream().filter(state -> !state.getVoided())
		        .max((a, b) -> a.getStartDate().compareTo(b.getStartDate())).orElseThrow(AssertionError::new);
	}
	
	private static void markDead(Patient patient, Date died) {
		patient.setDead(true);
		patient.setDeathDate(died);
		patient.setCauseOfDeath(Context.getConceptService().getConcept(3));
		Context.getPersonService().savePerson(patient);
	}
	
	private static Patient newPatient() {
		Patient patient = new Patient();
		patient.addName(new PersonName("Deceased", null, "Registrant"));
		patient.setGender("F");
		patient.setBirthdate(daysAgo(10000));
		PatientIdentifier identifier = new PatientIdentifier("1234-4",
		        Context.getPatientService().getPatientIdentifierType(2), Context.getLocationService().getLocation(1));
		identifier.setPreferred(true);
		patient.addIdentifier(identifier);
		return patient;
	}
	
	private static Date daysAgo(int days) {
		return new Date(TODAY - days * 86400000L);
	}
	
	private static void property(String name, String value) {
		Context.getAdministrationService().saveGlobalProperty(new GlobalProperty(name, value));
	}
	
	private static ProgramWorkflowState state(Concept concept, boolean initial, boolean terminal) {
		ProgramWorkflowState state = new ProgramWorkflowState();
		state.setConcept(concept);
		state.setInitial(initial);
		state.setTerminal(terminal);
		return state;
	}
	
	private static Concept concept(String name) {
		Concept concept = new Concept();
		concept.setFullySpecifiedName(new ConceptName(name, Context.getLocale()));
		concept.setConceptClass(Context.getConceptService().getConceptClass(1));
		concept.setDatatype(Context.getConceptService().getConceptDatatypeByName("N/A"));
		return Context.getConceptService().saveConcept(concept);
	}
}
