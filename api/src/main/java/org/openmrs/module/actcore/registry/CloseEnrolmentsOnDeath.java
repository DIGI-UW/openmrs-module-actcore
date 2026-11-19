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

import java.util.Date;
import java.util.List;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.openmrs.Patient;
import org.openmrs.PatientProgram;
import org.openmrs.PatientState;
import org.openmrs.Program;
import org.openmrs.Person;
import org.openmrs.ProgramWorkflow;
import org.openmrs.ProgramWorkflowState;
import org.openmrs.api.ProgramWorkflowService;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.studies.Studies;
import org.openmrs.util.PrivilegeConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Advice on PersonService and PatientService that ends a dead patient's open RHD Registry and study
 * enrolments in their death state, from the death date on, when they are saved or have a duplicate
 * merged in.
 */
public class CloseEnrolmentsOnDeath implements MethodInterceptor {
	
	public static final String DEATH_STATE_PROPERTY = "actcore.studies.deathStateConcept";
	
	public static final String REGISTRY_DEATH_STATE_PROPERTY = "actcore.registry.deathStateConcept";
	
	private static final Logger log = LoggerFactory.getLogger(CloseEnrolmentsOnDeath.class);
	
	// The user marking a patient deceased needs Edit People or Edit Patients, not program privileges.
	private static final String[] PRIVILEGES = { PrivilegeConstants.GET_GLOBAL_PROPERTIES, PrivilegeConstants.GET_PATIENTS,
	        PrivilegeConstants.GET_PATIENT_PROGRAMS, PrivilegeConstants.EDIT_PATIENT_PROGRAMS,
	        PrivilegeConstants.GET_PROGRAMS };
	
	@Override
	public Object invoke(MethodInvocation invocation) throws Throwable {
		String method = invocation.getMethod().getName();
		Object[] args = invocation.getArguments();
		Object saved = invocation.proceed();
		// Core's merge saves the preferred patient without passing through this advice.
		boolean save = ("savePerson".equals(method) || "savePatient".equals(method)) && args.length == 1;
		boolean merge = "mergePatients".equals(method) && args.length == 2;
		if ((save || merge) && args[0] instanceof Person && Boolean.TRUE.equals(((Person) args[0]).getDead())) {
			close((Person) args[0]);
		}
		return saved;
	}
	
	/**
	 * Ends the open enrolments of patients recorded dead before this module ended them on death, such
	 * as ACT 2.0's or an import's, and returns how many it ended.
	 */
	public static int closeEnrolmentsOfTheDead() {
		Context.addProxyPrivilege(PrivilegeConstants.SQL_LEVEL_ACCESS);
		List<List<Object>> rows;
		try {
			rows = Context.getAdministrationService().executeSQL(
			    "select distinct pp.patient_id from patient_program pp join person p on p.person_id ="
			            + " pp.patient_id where p.dead = 1 and p.voided = 0 and pp.voided = 0 and pp.date_completed is null",
			    true);
		}
		finally {
			Context.removeProxyPrivilege(PrivilegeConstants.SQL_LEVEL_ACCESS);
		}
		int ended = 0;
		for (List<Object> row : rows) {
			Person person = Context.getPersonService().getPerson(((Number) row.get(0)).intValue());
			if (person != null) {
				ended += close(person);
			}
		}
		return ended;
	}
	
	/**
	 * Ends a dead patient's enrolment in its death state, without saving it; false when it is not the
	 * registry or a study, or no death state is configured for it.
	 */
	public static boolean endInDeathState(PatientProgram enrolment, Person person) {
		for (String privilege : PRIVILEGES) {
			Context.addProxyPrivilege(privilege);
		}
		try {
			String concept = deathConceptFor(enrolment.getProgram());
			if (StringUtils.isBlank(concept)) {
				return false;
			}
			ProgramWorkflowState death = deathState(enrolment, concept.trim());
			if (death == null) {
				log.warn("{} has no state on concept {}; enrolment {} stays open", enrolment.getProgram().getName(), concept,
				    enrolment.getUuid());
				return false;
			}
			Date date = notBefore(person.getDeathDate() == null ? new Date() : person.getDeathDate(), enrolment);
			// Status dates are stored as days, so a death state already on that day would be a duplicate.
			if (!hasStateOnDay(enrolment, death, date)) {
				enrolment.transitionToState(death, date);
			}
			enrolment.setDateCompleted(date);
			return true;
		}
		finally {
			for (String privilege : PRIVILEGES) {
				Context.removeProxyPrivilege(privilege);
			}
		}
	}
	
	/**
	 * The death state concept for the registry or a study, by its property; null for any other program.
	 */
	static String deathConceptFor(Program program) {
		if (program == null) {
			return null;
		}
		String registry = Context.getAdministrationService().getGlobalProperty(EnrolOnRegistration.PROGRAM_PROPERTY);
		if (StringUtils.isNotBlank(registry) && registry.trim().equals(program.getUuid())) {
			return Context.getAdministrationService().getGlobalProperty(REGISTRY_DEATH_STATE_PROPERTY);
		}
		return new Studies().isStudy(program) ? Context.getAdministrationService().getGlobalProperty(DEATH_STATE_PROPERTY)
		        : null;
	}
	
	private static int close(Person person) {
		for (String privilege : PRIVILEGES) {
			Context.addProxyPrivilege(privilege);
		}
		try {
			Patient patient = person instanceof Patient ? (Patient) person
			        : Context.getPatientService().getPatient(person.getPersonId());
			if (patient == null) {
				return 0;
			}
			ProgramWorkflowService programs = Context.getProgramWorkflowService();
			int ended = 0;
			for (PatientProgram enrolment : programs.getPatientPrograms(patient, null, null, null, null, null, false)) {
				if (enrolment.getDateCompleted() == null && endInDeathState(enrolment, person)) {
					// Ending each one leaves no overlap, but ending the first of two overlapping ones would be refused.
					OneOpenRegistryEnrolment.withoutRefusing(() -> programs.savePatientProgram(enrolment));
					ended++;
				}
			}
			return ended;
		}
		finally {
			for (String privilege : PRIVILEGES) {
				Context.removeProxyPrivilege(privilege);
			}
		}
	}
	
	private static ProgramWorkflowState deathState(PatientProgram enrolment, String conceptUuid) {
		for (ProgramWorkflow workflow : enrolment.getProgram().getWorkflows()) {
			if (workflow.getRetired()) {
				continue;
			}
			for (ProgramWorkflowState state : workflow.getStates(false)) {
				if (conceptUuid.equals(state.getConcept().getUuid())) {
					return state;
				}
			}
		}
		return null;
	}
	
	private static boolean hasStateOnDay(PatientProgram enrolment, ProgramWorkflowState state, Date day) {
		for (PatientState recorded : enrolment.getStates()) {
			if (!Boolean.TRUE.equals(recorded.getVoided()) && state.equals(recorded.getState())
			        && recorded.getStartDate() != null && DateUtils.isSameDay(recorded.getStartDate(), day)) {
				return true;
			}
		}
		return false;
	}
	
	/**
	 * The death date, moved up to the enrolment's latest recorded start or end, since a death state
	 * cannot overlap the statuses recorded before it and close() does not rewrite them.
	 */
	private static Date notBefore(Date date, PatientProgram enrolment) {
		Date earliest = later(date, enrolment.getDateEnrolled());
		for (PatientState state : enrolment.getStates()) {
			if (!Boolean.TRUE.equals(state.getVoided())) {
				earliest = later(later(earliest, state.getStartDate()), state.getEndDate());
			}
		}
		return earliest;
	}
	
	private static Date later(Date date, Date other) {
		return other != null && other.after(date) ? other : date;
	}
}
