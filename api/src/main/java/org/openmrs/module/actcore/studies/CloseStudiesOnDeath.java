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

import java.util.Date;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.apache.commons.lang3.StringUtils;
import org.openmrs.Patient;
import org.openmrs.PatientProgram;
import org.openmrs.PatientState;
import org.openmrs.Person;
import org.openmrs.ProgramWorkflow;
import org.openmrs.ProgramWorkflowState;
import org.openmrs.api.ProgramWorkflowService;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.registry.OneOpenRegistryEnrolment;
import org.openmrs.util.PrivilegeConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Advice on PersonService and PatientService that ends a dead patient's open study enrolments in
 * Inactive - Death, on their death date, when they are saved or have a duplicate merged in.
 */
public class CloseStudiesOnDeath implements MethodInterceptor {
	
	public static final String DEATH_STATE_PROPERTY = "actcore.studies.deathStateConcept";
	
	private static final Logger log = LoggerFactory.getLogger(CloseStudiesOnDeath.class);
	
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
	
	private static void close(Person person) {
		for (String privilege : PRIVILEGES) {
			Context.addProxyPrivilege(privilege);
		}
		try {
			String concept = Context.getAdministrationService().getGlobalProperty(DEATH_STATE_PROPERTY);
			Patient patient = person instanceof Patient ? (Patient) person
			        : Context.getPatientService().getPatient(person.getPersonId());
			if (StringUtils.isBlank(concept) || patient == null) {
				return;
			}
			ProgramWorkflowService programs = Context.getProgramWorkflowService();
			Studies studies = new Studies();
			for (PatientProgram enrolment : programs.getPatientPrograms(patient, null, null, null, null, null, false)) {
				if (enrolment.getDateCompleted() != null || !studies.isStudy(enrolment.getProgram())) {
					continue;
				}
				ProgramWorkflowState death = deathState(enrolment, concept.trim());
				if (death == null) {
					log.warn("{} has no state on {} {}; enrolment {} stays open", enrolment.getProgram().getName(),
					    DEATH_STATE_PROPERTY, concept, enrolment.getUuid());
					continue;
				}
				Date date = notBefore(person.getDeathDate() == null ? new Date() : person.getDeathDate(), enrolment);
				// A reopened enrolment already ended in death; a second death state on that date is refused.
				if (!death.equals(latestState(enrolment, death.getProgramWorkflow()))) {
					enrolment.transitionToState(death, date);
				}
				enrolment.setDateCompleted(date);
				// Ending each one leaves no overlap, but ending the first of two overlapping ones would be refused.
				OneOpenRegistryEnrolment.withoutRefusing(() -> programs.savePatientProgram(enrolment));
			}
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
	
	private static ProgramWorkflowState latestState(PatientProgram enrolment, ProgramWorkflow workflow) {
		PatientState latest = null;
		for (PatientState state : enrolment.getStates()) {
			if (!Boolean.TRUE.equals(state.getVoided()) && workflow.equals(state.getState().getProgramWorkflow())
			        && state.getStartDate() != null
			        && (latest == null || state.getStartDate().after(latest.getStartDate()))) {
				latest = state;
			}
		}
		return latest == null ? null : latest.getState();
	}
	
	/**
	 * The death date, moved up to the enrolment's start or its current status's, which it cannot
	 * precede.
	 */
	private static Date notBefore(Date date, PatientProgram enrolment) {
		Date earliest = enrolment.getDateEnrolled();
		for (PatientState state : enrolment.getStates()) {
			if (!Boolean.TRUE.equals(state.getVoided()) && state.getEndDate() == null && state.getStartDate() != null
			        && (earliest == null || state.getStartDate().after(earliest))) {
				earliest = state.getStartDate();
			}
		}
		return earliest != null && date.before(earliest) ? earliest : date;
	}
}
