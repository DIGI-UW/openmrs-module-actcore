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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Date;
import java.util.List;

import org.junit.After;
import org.junit.Test;
import org.openmrs.GlobalProperty;
import org.openmrs.PatientProgram;
import org.openmrs.PatientProgramAttribute;
import org.openmrs.Program;
import org.openmrs.ProgramAttributeType;
import org.openmrs.ProgramWorkflow;
import org.openmrs.ProgramWorkflowState;
import org.openmrs.api.ProgramWorkflowService;
import org.openmrs.api.ValidationException;
import org.openmrs.api.context.Context;
import org.openmrs.customdatatype.datatype.FreeTextDatatype;
import org.openmrs.test.BaseModuleContextSensitiveTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The rules as a REST request meets them: outside a transaction, where each service call the rules
 * make commits its own, and that commit would flush the edit being checked.
 */
public class StudyEnrolmentRulesCommitContextTest extends BaseModuleContextSensitiveTest {
	
	private final StudyEnrolmentRules rules = new StudyEnrolmentRules();
	
	private Program template;
	
	private Program study;
	
	private ProgramAttributeType studyId;
	
	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public void savePatientProgram_shouldRefuseDroppingTheStudyIdOfAnOpenEnrolmentAndKeepItStored() {
		PatientProgram enrolment = enrolledWithAStudyId();
		
		// As REST's attribute DELETE does.
		PatientProgramAttribute id = programs().getPatientProgramAttributeByUuid(attributeUuid(enrolment));
		id.setVoided(true);
		id.setVoidReason("removed");
		assertRefused(id.getPatientProgram());
		Context.clearSession();
		
		assertEquals(1, activeStudyIds(enrolment));
	}
	
	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public void savePatientProgram_shouldRefuseReopeningACompletedEnrolmentWithoutAStudyIdAndKeepItCompleted() {
		PatientProgram enrolment = enrolledWithAStudyId();
		enrolment = programs().getPatientProgramByUuid(enrolment.getUuid());
		enrolment.setDateCompleted(new Date());
		programs().savePatientProgram(enrolment);
		Context.clearSession();
		PatientProgramAttribute id = programs().getPatientProgramAttributeByUuid(attributeUuid(enrolment));
		id.setVoided(true);
		id.setVoidReason("removed");
		programs().savePatientProgram(id.getPatientProgram());
		Context.clearSession();
		
		// As REST's update of dateCompleted does.
		enrolment = programs().getPatientProgramByUuid(enrolment.getUuid());
		enrolment.setDateCompleted(null);
		assertRefused(enrolment);
		Context.clearSession();
		
		assertNotNull(programs().getPatientProgramByUuid(enrolment.getUuid()).getDateCompleted());
	}
	
	private PatientProgram enrolledWithAStudyId() {
		studyId = new ProgramAttributeType();
		studyId.setName("Study Participant ID");
		studyId.setDatatypeClassname(FreeTextDatatype.class.getName());
		Context.getProgramWorkflowService().saveProgramAttributeType(studyId);
		template = program("Research Participation");
		template.setRetired(true);
		Context.getProgramWorkflowService().saveProgram(template);
		study = Context.getProgramWorkflowService().saveProgram(program("ARC"));
		property(Studies.TEMPLATE_PROPERTY, template.getUuid());
		property(StudyEnrolmentRules.STUDY_ID_PROPERTY, studyId.getUuid());
		Context.addAdvice(ProgramWorkflowService.class, rules);
		
		PatientProgram enrolment = new PatientProgram();
		enrolment.setPatient(Context.getPatientService().getPatient(2));
		enrolment.setProgram(study);
		enrolment.setDateEnrolled(new Date());
		PatientProgramAttribute id = new PatientProgramAttribute();
		id.setAttributeType(studyId);
		id.setValue("ARC-7");
		enrolment.addAttribute(id);
		programs().savePatientProgram(enrolment);
		Context.clearSession();
		return enrolment;
	}
	
	// A workflow on concept 5089, as a study's copy of the template's status workflow.
	private static Program program(String name) {
		Program program = new Program();
		program.setName(name);
		program.setDescription(name);
		program.setConcept(Context.getConceptService().getConcept(5497));
		ProgramWorkflow workflow = new ProgramWorkflow();
		workflow.setConcept(Context.getConceptService().getConcept(5089));
		ProgramWorkflowState active = new ProgramWorkflowState();
		active.setConcept(Context.getConceptService().getConcept(5497));
		active.setInitial(true);
		active.setTerminal(false);
		workflow.addState(active);
		program.addWorkflow(workflow);
		return program;
	}
	
	private static ProgramWorkflowService programs() {
		return Context.getProgramWorkflowService();
	}
	
	private String attributeUuid(PatientProgram enrolment) {
		return programs().getPatientProgramByUuid(enrolment.getUuid()).getActiveAttributes(studyId).get(0).getUuid();
	}
	
	private static int activeStudyIds(PatientProgram enrolment) {
		List<List<Object>> rows = Context.getAdministrationService()
		        .executeSQL("select count(*) from patient_program_attribute a join patient_program p"
		                + " on p.patient_program_id = a.patient_program_id where a.voided = false and p.uuid = '"
		                + enrolment.getUuid() + "'",
		            true);
		return ((Number) rows.get(0).get(0)).intValue();
	}
	
	private static void assertRefused(PatientProgram enrolment) {
		try {
			programs().savePatientProgram(enrolment);
			fail("saved without a Study ID");
		}
		catch (ValidationException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("Study ID"));
		}
	}
	
	private static void property(String name, String value) {
		Context.getAdministrationService().saveGlobalProperty(new GlobalProperty(name, value));
	}
	
	// These tests commit, so no rollback undoes their rows.
	@After
	public void removeAdviceAndCommittedRows() {
		Context.removeAdvice(ProgramWorkflowService.class, rules);
		Context.clearSession();
		for (Program program : new Program[] { study, template }) {
			if (program == null) {
				continue;
			}
			String id = program.getProgramId().toString();
			sql("delete from patient_program_attribute where patient_program_id in"
			        + " (select patient_program_id from patient_program where program_id = " + id + ")");
			sql("delete from patient_state where patient_program_id in"
			        + " (select patient_program_id from patient_program where program_id = " + id + ")");
			sql("delete from patient_program where program_id = " + id);
			sql("delete from program_workflow_state where program_workflow_id in"
			        + " (select program_workflow_id from program_workflow where program_id = " + id + ")");
			sql("delete from program_workflow where program_id = " + id);
			sql("delete from program where program_id = " + id);
		}
		if (studyId != null) {
			sql("delete from program_attribute_type where program_attribute_type_id = "
			        + studyId.getProgramAttributeTypeId());
		}
		sql("delete from global_property where property in ('" + Studies.TEMPLATE_PROPERTY + "', '"
		        + StudyEnrolmentRules.STUDY_ID_PROPERTY + "')");
	}
	
	private static void sql(String statement) {
		Context.getAdministrationService().executeSQL(statement, false);
	}
}
