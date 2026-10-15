/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.studies;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openmrs.Concept;
import org.openmrs.ConceptName;
import org.openmrs.GlobalProperty;
import org.openmrs.Patient;
import org.openmrs.PatientProgram;
import org.openmrs.PatientProgramAttribute;
import org.openmrs.PatientState;
import org.openmrs.Person;
import org.openmrs.PersonAttribute;
import org.openmrs.PersonAttributeType;
import org.openmrs.Program;
import org.openmrs.ProgramAttributeType;
import org.openmrs.ProgramWorkflow;
import org.openmrs.ProgramWorkflowState;
import org.openmrs.api.PatientService;
import org.openmrs.api.PersonService;
import org.openmrs.api.ProgramWorkflowService;
import org.openmrs.api.ValidationException;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.registry.OneOpenRegistryEnrolment;
import org.openmrs.customdatatype.datatype.FreeTextDatatype;
import org.openmrs.test.BaseModuleContextSensitiveTest;
import org.springframework.aop.framework.ProxyFactory;

public class StudiesContextTest extends BaseModuleContextSensitiveTest {
	
	private final Studies studies = new Studies();
	
	private final OneOpenRegistryEnrolment guard = new OneOpenRegistryEnrolment();
	
	private final StudyEnrolmentStatus status = new StudyEnrolmentStatus();
	
	private final StudyEnrolmentRules rules = new StudyEnrolmentRules();
	
	private final CloseStudiesOnDeath closeOnDeath = new CloseStudiesOnDeath();
	
	private Program template;
	
	private Concept statusWorkflow;
	
	private Concept death;
	
	@Before
	public void setUp() {
		statusWorkflow = concept("Research Study Status");
		template = new Program();
		template.setName("Research Participation");
		template.setDescription("Template");
		template.setConcept(concept("Research Participation Program"));
		ProgramWorkflow workflow = new ProgramWorkflow();
		workflow.setConcept(statusWorkflow);
		workflow.addState(state(concept("Active (study status)"), true, false));
		death = concept("Inactive - Death");
		workflow.addState(state(death, false, true));
		workflow.addState(state(concept("Inactive - Voluntary withdrawal"), false, true));
		ProgramWorkflowState retired = state(concept("Inactive"), false, true);
		retired.setRetired(true);
		workflow.addState(retired);
		template.addWorkflow(workflow);
		template.setRetired(true);
		Context.getProgramWorkflowService().saveProgram(template);
		Context.getAdministrationService()
		        .saveGlobalProperty(new GlobalProperty(Studies.TEMPLATE_PROPERTY, template.getUuid()));
	}
	
	@Test
	public void create_shouldGiveTheStudyACopyOfTheTemplatesUnretiredStates() {
		Program study = studies.create("  ARC  ");
		
		assertEquals("ARC", study.getName());
		assertEquals("ARC (research study)", study.getConcept().getName().getName());
		assertEquals(1, study.getWorkflows().size());
		ProgramWorkflow workflow = study.getWorkflows().iterator().next();
		assertEquals(statusWorkflow, workflow.getConcept());
		assertNotEquals(template.getWorkflows().iterator().next(), workflow);
		Set<String> states = workflow.getStates(false).stream().map(s -> s.getConcept().getName().getName()
		        + (s.getInitial() ? " initial" : "") + (s.getTerminal() ? " terminal" : "")).collect(Collectors.toSet());
		assertEquals(3, states.size());
		assertTrue(states.contains("Active (study status) initial"));
		assertTrue(states.contains("Inactive - Death terminal"));
	}
	
	@Test
	public void getStudies_shouldListStudiesByNameAndLeaveOutTheTemplateAndOtherPrograms() {
		studies.create("Miller");
		studies.create("ARC");
		
		List<String> names = studies.getStudies().stream().map(Program::getName).collect(Collectors.toList());
		
		assertEquals(2, names.size());
		assertEquals("ARC", names.get(0));
		assertEquals("Miller", names.get(1));
		assertFalse(studies.isStudy(template));
		assertFalse(studies.isStudy(Context.getProgramWorkflowService().getProgram(1)));
	}
	
	@Test
	public void rename_shouldRenameTheProgramAndItsConceptAndFreeTheOldName() {
		Program study = studies.create("Stage Study 1");
		
		studies.rename(study, "Stage Study One");
		
		assertEquals("Stage Study One", study.getName());
		assertEquals("Stage Study One (research study)", study.getConcept().getName().getName());
		assertEquals("Stage Study 1", studies.create("Stage Study 1").getName());
	}
	
	@Test
	public void create_shouldRefuseANameAStudyAlreadyHasButNotOneAnotherConceptHas() {
		studies.create("ARC");
		concept("Miller");
		
		for (String name : new String[] { "arc", " " }) {
			try {
				studies.create(name);
				fail("created a study named " + name);
			}
			catch (ValidationException expected) {}
		}
		assertEquals(1, studies.getStudies().size());
		assertEquals("Miller", studies.create("Miller").getName());
	}
	
	@Test
	public void isStudy_shouldBeFalseForEveryProgramWithoutATemplate() {
		Program study = studies.create("ARC");
		Context.getAdministrationService().saveGlobalProperty(new GlobalProperty(Studies.TEMPLATE_PROPERTY, ""));
		
		assertFalse(studies.isStudy(study));
	}
	
	@Test
	public void savePatientProgram_shouldRefuseASecondOpenEnrolmentInTheSameStudy() {
		Program study = studies.create("ARC");
		ProxyFactory proxy = new ProxyFactory(Context.getProgramWorkflowService());
		proxy.addInterface(ProgramWorkflowService.class);
		proxy.addAdvice(new OneOpenRegistryEnrolment());
		ProgramWorkflowService programs = (ProgramWorkflowService) proxy.getProxy();
		Patient patient = Context.getPatientService().getPatient(2);
		programs.savePatientProgram(enrolment(patient, study));
		
		try {
			programs.savePatientProgram(enrolment(patient, study));
			fail("a second open enrolment in the study was saved");
		}
		catch (ValidationException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("already enrolled in ARC"));
		}
		programs.savePatientProgram(enrolment(patient, studies.create("Miller")));
	}
	
	@Test
	public void savePatientProgram_shouldStartAStudyEnrolmentWithoutAStatusInTheInitialOneFromItsEnrolmentDate() {
		Program study = studies.create("ARC");
		ProxyFactory proxy = new ProxyFactory(Context.getProgramWorkflowService());
		proxy.addInterface(ProgramWorkflowService.class);
		proxy.addAdvice(new StudyEnrolmentStatus());
		ProgramWorkflowService programs = (ProgramWorkflowService) proxy.getProxy();
		PatientProgram enrolment = enrolment(Context.getPatientService().getPatient(2), study);
		// Whole seconds, as the database keeps them.
		Date enrolled = new Date((enrolment.getDateEnrolled().getTime() / 1000 - 86400) * 1000);
		enrolment.setDateEnrolled(enrolled);
		
		programs.savePatientProgram(enrolment);
		
		assertEquals(1, enrolment.getStates().size());
		PatientState state = enrolment.getStates().iterator().next();
		assertEquals("Active (study status)", state.getState().getConcept().getName().getName());
		assertEquals(enrolled.getTime(), state.getStartDate().getTime());
	}
	
	@Test
	public void savePatientProgram_shouldLeaveTheStatusOfAnEnrolmentOutsideStudiesAlone() {
		ProxyFactory proxy = new ProxyFactory(Context.getProgramWorkflowService());
		proxy.addInterface(ProgramWorkflowService.class);
		proxy.addAdvice(new StudyEnrolmentStatus());
		ProgramWorkflowService programs = (ProgramWorkflowService) proxy.getProxy();
		PatientProgram enrolment = enrolment(Context.getPatientService().getPatient(2),
		    Context.getProgramWorkflowService().getProgram(1));
		
		programs.savePatientProgram(enrolment);
		
		assertTrue(enrolment.getStates().isEmpty());
	}
	
	@Test
	public void savePatientProgram_shouldRefuseANewStudyEnrolmentUntilConsentOptInAndStudyIdAreRecorded() {
		Program study = studies.create("ARC");
		Concept yes = concept("Agreed");
		Concept no = concept("Declined");
		PersonAttributeType consent = attributeType("Consent Given");
		PersonAttributeType optIn = attributeType("Research Opt-In");
		ProgramAttributeType studyId = new ProgramAttributeType();
		studyId.setName("Study Participant ID");
		studyId.setDatatypeClassname(FreeTextDatatype.class.getName());
		Context.getProgramWorkflowService().saveProgramAttributeType(studyId);
		property(StudyEnrolmentRules.YES_PROPERTY, yes.getUuid());
		property(StudyEnrolmentRules.CONSENT_PROPERTY, consent.getUuid());
		property(StudyEnrolmentRules.OPT_IN_PROPERTY, optIn.getUuid());
		property(StudyEnrolmentRules.STUDY_ID_PROPERTY, studyId.getUuid());
		ProxyFactory proxy = new ProxyFactory(Context.getProgramWorkflowService());
		proxy.addInterface(ProgramWorkflowService.class);
		proxy.addAdvice(new StudyEnrolmentRules());
		ProgramWorkflowService programs = (ProgramWorkflowService) proxy.getProxy();
		Patient patient = Context.getPatientService().getPatient(2);
		PatientProgram enrolment = enrolment(patient, study);
		
		assertRefused(programs, enrolment, "registry consent");
		patient.addAttribute(new PersonAttribute(consent, no.getConceptId().toString()));
		assertRefused(programs, enrolment, "registry consent");
		patient.addAttribute(new PersonAttribute(consent, yes.getConceptId().toString()));
		assertRefused(programs, enrolment, "considered for research");
		patient.addAttribute(new PersonAttribute(optIn, yes.getConceptId().toString()));
		assertRefused(programs, enrolment, "Study ID");
		PatientProgramAttribute id = new PatientProgramAttribute();
		id.setAttributeType(studyId);
		id.setValue("ARC-7");
		enrolment.addAttribute(id);
		
		programs.savePatientProgram(enrolment);
		
		patient.addAttribute(new PersonAttribute(optIn, no.getConceptId().toString()));
		enrolment.setDateCompleted(new Date());
		programs.savePatientProgram(enrolment);
		programs.savePatientProgram(enrolment(patient, Context.getProgramWorkflowService().getProgram(1)));
	}
	
	@Test
	public void savePatientProgram_shouldRefuseANewStudyEnrolmentForAPatientWhoDied() {
		Program study = studies.create("ARC");
		ProxyFactory proxy = new ProxyFactory(Context.getProgramWorkflowService());
		proxy.addInterface(ProgramWorkflowService.class);
		proxy.addAdvice(new StudyEnrolmentRules());
		ProgramWorkflowService programs = (ProgramWorkflowService) proxy.getProxy();
		Patient patient = Context.getPatientService().getPatient(2);
		patient.setDead(true);
		patient.setCauseOfDeath(Context.getConceptService().getConcept(3));
		
		assertRefused(programs, enrolment(patient, study), "has died");
	}
	
	@Test
	public void savePerson_shouldEndOpenStudyEnrolmentsOfAPatientWhoDiedInInactiveDeathOnTheirDeathDate() {
		Program arc = studies.create("ARC");
		Program miller = studies.create("Miller");
		property(CloseStudiesOnDeath.DEATH_STATE_PROPERTY, death.getUuid());
		ProgramWorkflowService programs = Context.getProgramWorkflowService();
		Patient patient = Context.getPatientService().getPatient(2);
		Date enrolled = new Date(System.currentTimeMillis() / 1000 * 1000 - 10 * 86400000L);
		Date died = new Date(enrolled.getTime() + 5 * 86400000L);
		PatientProgram open = enrolment(patient, arc);
		open.setDateEnrolled(enrolled);
		open.transitionToState(state(arc, "Active (study status)"), enrolled);
		programs.savePatientProgram(open);
		PatientProgram withdrawn = enrolment(patient, miller);
		withdrawn.setDateEnrolled(enrolled);
		withdrawn.transitionToState(state(miller, "Inactive - Voluntary withdrawal"), enrolled);
		programs.savePatientProgram(withdrawn);
		ProxyFactory proxy = new ProxyFactory(Context.getPersonService());
		proxy.addInterface(PersonService.class);
		proxy.addAdvice(new CloseStudiesOnDeath());
		PersonService people = (PersonService) proxy.getProxy();
		Person person = Context.getPersonService().getPerson(2);
		person.setDead(true);
		person.setDeathDate(died);
		person.setCauseOfDeath(Context.getConceptService().getConcept(3));
		
		people.savePerson(person);
		
		assertEquals(died.getTime(), open.getDateCompleted().getTime());
		assertEquals("Inactive - Death", current(open).getState().getConcept().getName().getName());
		assertEquals(died.getTime(), current(open).getStartDate().getTime());
		assertEquals(enrolled.getTime(), withdrawn.getDateCompleted().getTime());
		assertEquals("Inactive - Voluntary withdrawal", current(withdrawn).getState().getConcept().getName().getName());
	}
	
	@Test
	public void create_shouldRefuseANameLongerThanTheProgramTableHoldsBeforeSavingItsConcept() {
		String longest = StringUtils.repeat("A", 50);
		
		try {
			studies.create(longest + "B");
			fail("created a study whose name the program table cannot hold");
		}
		catch (ValidationException expected) {}
		assertNull(Context.getConceptService().getConceptByName(longest + "B (research study)"));
		assertEquals(longest, studies.create(longest).getName());
	}
	
	@Test
	public void savePatientProgram_shouldAcceptACompletedStudyEnrolmentForAPatientWhoDied() {
		Program study = studies.create("ARC");
		adviseAsTheModuleDoes();
		Patient patient = Context.getPatientService().getPatient(2);
		patient.setDead(true);
		patient.setCauseOfDeath(Context.getConceptService().getConcept(3));
		PatientProgram history = enrolment(patient, study);
		history.setDateEnrolled(new Date(System.currentTimeMillis() - 400 * 86400000L));
		history.setDateCompleted(new Date(System.currentTimeMillis() - 300 * 86400000L));
		
		Context.getProgramWorkflowService().savePatientProgram(history);
		
		assertRefused(Context.getProgramWorkflowService(), enrolment(patient, study), "has died");
	}
	
	@Test
	public void mergePatients_shouldFoldTheDuplicatesOpenEnrolmentsInAStudyIntoOne() throws Exception {
		Program study = studies.create("ARC");
		adviseAsTheModuleDoes();
		Patient preferred = Context.getPatientService().getPatient(2);
		Context.getProgramWorkflowService().savePatientProgram(enrolment(preferred, study));
		Context.getProgramWorkflowService().savePatientProgram(enrolment(Context.getPatientService().getPatient(6), study));
		
		Context.getPatientService().mergePatients(preferred, Context.getPatientService().getPatient(6));
		
		List<PatientProgram> left = Context.getProgramWorkflowService().getPatientPrograms(preferred, study, null, null,
		    null, null, false);
		assertEquals(1, left.size());
		assertNull(left.get(0).getDateCompleted());
	}
	
	@Test
	public void mergePatients_shouldCopyAStudyEnrolmentThoughTheCopyHasNoStudyId() throws Exception {
		Program study = studies.create("ARC");
		ProgramAttributeType studyId = new ProgramAttributeType();
		studyId.setName("Study Participant ID");
		studyId.setDatatypeClassname(FreeTextDatatype.class.getName());
		Context.getProgramWorkflowService().saveProgramAttributeType(studyId);
		property(StudyEnrolmentRules.STUDY_ID_PROPERTY, studyId.getUuid());
		adviseAsTheModuleDoes();
		PatientProgram enrolment = enrolment(Context.getPatientService().getPatient(6), study);
		PatientProgramAttribute id = new PatientProgramAttribute();
		id.setAttributeType(studyId);
		id.setValue("ARC-7");
		enrolment.addAttribute(id);
		Context.getProgramWorkflowService().savePatientProgram(enrolment);
		Patient preferred = Context.getPatientService().getPatient(2);
		
		Context.getPatientService().mergePatients(preferred, Context.getPatientService().getPatient(6));
		
		assertEquals(1,
		    Context.getProgramWorkflowService().getPatientPrograms(preferred, study, null, null, null, null, false).size());
	}
	
	@Test
	public void savePerson_shouldEndBothOfTwoOverlappingOpenEnrolmentsInAStudy() {
		Program study = studies.create("ARC");
		property(CloseStudiesOnDeath.DEATH_STATE_PROPERTY, death.getUuid());
		Patient patient = Context.getPatientService().getPatient(2);
		Date enrolled = new Date(System.currentTimeMillis() / 1000 * 1000 - 10 * 86400000L);
		PatientProgram first = enrolment(patient, study);
		first.setDateEnrolled(enrolled);
		Context.getProgramWorkflowService().savePatientProgram(first);
		PatientProgram second = enrolment(patient, study);
		second.setDateEnrolled(enrolled);
		Context.getProgramWorkflowService().savePatientProgram(second);
		adviseAsTheModuleDoes();
		Person person = Context.getPersonService().getPerson(2);
		person.setDead(true);
		person.setDeathDate(new Date(enrolled.getTime() + 5 * 86400000L));
		person.setCauseOfDeath(Context.getConceptService().getConcept(3));
		
		Context.getPersonService().savePerson(person);
		
		assertNotNull(first.getDateCompleted());
		assertNotNull(second.getDateCompleted());
	}
	
	@Test
	public void savePatient_shouldEndOpenStudyEnrolmentsOfAPatientWhoDied() {
		Program study = studies.create("ARC");
		property(CloseStudiesOnDeath.DEATH_STATE_PROPERTY, death.getUuid());
		adviseAsTheModuleDoes();
		Patient patient = Context.getPatientService().getPatient(2);
		PatientProgram open = enrolment(patient, study);
		open.setDateEnrolled(new Date(System.currentTimeMillis() / 1000 * 1000 - 10 * 86400000L));
		Context.getProgramWorkflowService().savePatientProgram(open);
		Date died = new Date(open.getDateEnrolled().getTime() + 5 * 86400000L);
		patient.setDead(true);
		patient.setDeathDate(died);
		patient.setCauseOfDeath(Context.getConceptService().getConcept(3));
		
		Context.getPatientService().savePatient(patient);
		
		assertEquals(died.getTime(), open.getDateCompleted().getTime());
		assertEquals("Inactive - Death", current(open).getState().getConcept().getName().getName());
	}
	
	@Test
	public void savePatient_shouldEndAgainAReopenedStudyEnrolmentOfAPatientWhoDied() {
		Program study = studies.create("ARC");
		property(CloseStudiesOnDeath.DEATH_STATE_PROPERTY, death.getUuid());
		adviseAsTheModuleDoes();
		Patient patient = Context.getPatientService().getPatient(2);
		PatientProgram open = enrolment(patient, study);
		open.setDateEnrolled(new Date(System.currentTimeMillis() / 1000 * 1000 - 10 * 86400000L));
		Context.getProgramWorkflowService().savePatientProgram(open);
		patient.setDead(true);
		patient.setDeathDate(new Date(open.getDateEnrolled().getTime() + 5 * 86400000L));
		patient.setCauseOfDeath(Context.getConceptService().getConcept(3));
		Context.getPatientService().savePatient(patient);
		open.setDateCompleted(null);
		Context.getProgramWorkflowService().savePatientProgram(open);
		
		Context.getPatientService().savePatient(patient);
		
		assertNotNull(open.getDateCompleted());
		assertEquals(1,
		    open.getStates().stream().filter(
		        state -> !state.getVoided() && "Inactive - Death".equals(state.getState().getConcept().getName().getName()))
		            .count());
	}
	
	@Test
	public void mergePatients_shouldEndTheStudyEnrolmentsAPatientWhoDiedGainsFromTheDuplicate() throws Exception {
		Program study = studies.create("ARC");
		property(CloseStudiesOnDeath.DEATH_STATE_PROPERTY, death.getUuid());
		adviseAsTheModuleDoes();
		Date enrolled = new Date(System.currentTimeMillis() / 1000 * 1000 - 10 * 86400000L);
		Patient preferred = Context.getPatientService().getPatient(2);
		preferred.setDead(true);
		preferred.setDeathDate(new Date(enrolled.getTime() + 5 * 86400000L));
		preferred.setCauseOfDeath(Context.getConceptService().getConcept(3));
		Context.getPatientService().savePatient(preferred);
		PatientProgram duplicates = enrolment(Context.getPatientService().getPatient(6), study);
		duplicates.setDateEnrolled(enrolled);
		Context.getProgramWorkflowService().savePatientProgram(duplicates);
		
		Context.getPatientService().mergePatients(preferred, Context.getPatientService().getPatient(6));
		
		List<PatientProgram> left = Context.getProgramWorkflowService().getPatientPrograms(preferred, study, null, null,
		    null, null, false);
		assertEquals(1, left.size());
		assertNotNull(left.get(0).getDateCompleted());
		assertEquals("Inactive - Death", current(left.get(0)).getState().getConcept().getName().getName());
	}
	
	private void adviseAsTheModuleDoes() {
		Context.addAdvice(PatientService.class, guard);
		Context.addAdvice(ProgramWorkflowService.class, guard);
		Context.addAdvice(ProgramWorkflowService.class, status);
		Context.addAdvice(ProgramWorkflowService.class, rules);
		Context.addAdvice(PersonService.class, closeOnDeath);
		Context.addAdvice(PatientService.class, closeOnDeath);
	}
	
	@After
	public void removeModuleAdvice() {
		Context.removeAdvice(PatientService.class, guard);
		Context.removeAdvice(ProgramWorkflowService.class, guard);
		Context.removeAdvice(ProgramWorkflowService.class, status);
		Context.removeAdvice(ProgramWorkflowService.class, rules);
		Context.removeAdvice(PersonService.class, closeOnDeath);
		Context.removeAdvice(PatientService.class, closeOnDeath);
	}
	
	private static void assertRefused(ProgramWorkflowService programs, PatientProgram enrolment, String reason) {
		try {
			programs.savePatientProgram(enrolment);
			fail("enrolled without " + reason);
		}
		catch (ValidationException e) {
			assertTrue(e.getMessage(), e.getMessage().contains(reason));
		}
	}
	
	private static ProgramWorkflowState state(Program study, String name) {
		for (ProgramWorkflowState state : study.getWorkflows().iterator().next().getStates(false)) {
			if (name.equals(state.getConcept().getName().getName())) {
				return state;
			}
		}
		throw new AssertionError("no state " + name);
	}
	
	/** The status that started last, as the participants report reads it: completing ends them all. */
	private static PatientState current(PatientProgram enrolment) {
		return enrolment.getStates().stream().filter(state -> !state.getVoided())
		        .max(Comparator.comparing(PatientState::getStartDate)).orElseThrow(AssertionError::new);
	}
	
	private static PersonAttributeType attributeType(String name) {
		PersonAttributeType type = new PersonAttributeType();
		type.setName(name);
		type.setFormat(Concept.class.getName());
		return Context.getPersonService().savePersonAttributeType(type);
	}
	
	private static void property(String name, String value) {
		Context.getAdministrationService().saveGlobalProperty(new GlobalProperty(name, value));
	}
	
	private static PatientProgram enrolment(Patient patient, Program program) {
		PatientProgram enrolment = new PatientProgram();
		enrolment.setPatient(patient);
		enrolment.setProgram(program);
		enrolment.setDateEnrolled(new Date());
		return enrolment;
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
