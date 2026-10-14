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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.Before;
import org.junit.Test;
import org.openmrs.Concept;
import org.openmrs.ConceptName;
import org.openmrs.GlobalProperty;
import org.openmrs.Patient;
import org.openmrs.PatientProgram;
import org.openmrs.Program;
import org.openmrs.ProgramWorkflow;
import org.openmrs.ProgramWorkflowState;
import org.openmrs.api.ProgramWorkflowService;
import org.openmrs.api.ValidationException;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.registry.OneOpenRegistryEnrolment;
import org.openmrs.test.BaseModuleContextSensitiveTest;
import org.springframework.aop.framework.ProxyFactory;

public class StudiesContextTest extends BaseModuleContextSensitiveTest {
	
	private final Studies studies = new Studies();
	
	private Program template;
	
	private Concept statusWorkflow;
	
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
		workflow.addState(state(concept("Inactive - Death"), false, true));
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
		assertEquals(2, states.size());
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
