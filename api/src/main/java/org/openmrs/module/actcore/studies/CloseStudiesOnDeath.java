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
import org.apache.commons.lang3.time.DateUtils;
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
 * Inactive - Death, from their death date on, when they are saved or have a duplicate merged in.
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
				// Status dates are stored as days, so a death state already on that day would be a duplicate.
				if (!hasStateOnDay(enrolment, death, date)) {
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
