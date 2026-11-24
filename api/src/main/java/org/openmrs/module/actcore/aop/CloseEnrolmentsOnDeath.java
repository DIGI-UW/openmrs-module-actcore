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

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.openmrs.Patient;
import org.openmrs.PatientProgram;
import org.openmrs.PatientState;
import org.openmrs.Person;
import org.openmrs.Program;
import org.openmrs.ProgramWorkflow;
import org.openmrs.ProgramWorkflowState;
import org.openmrs.api.ProgramWorkflowService;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.ActCoreConstants;
import org.openmrs.module.actcore.api.StudyService;
import org.openmrs.module.actcore.api.db.RegistryDAO;
import org.openmrs.module.actcore.utils.Transactions;
import org.openmrs.util.PrivilegeConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Advice that ends a dead patient's open RHD Registry and study enrolments in each program's death
 * state, when the patient is saved or has a duplicate merged into them.
 */
public class CloseEnrolmentsOnDeath implements MethodInterceptor {
	
	private static final Logger log = LoggerFactory.getLogger(CloseEnrolmentsOnDeath.class);
	
	// The user marking a patient deceased needs Edit People or Edit Patients, not program privileges.
	private static final String[] PRIVILEGES = { PrivilegeConstants.GET_GLOBAL_PROPERTIES, PrivilegeConstants.GET_PATIENTS,
	        PrivilegeConstants.GET_PATIENT_PROGRAMS, PrivilegeConstants.EDIT_PATIENT_PROGRAMS,
	        PrivilegeConstants.GET_PROGRAMS };
	
	/** @see org.aopalliance.intercept.MethodInterceptor#invoke(MethodInvocation) */
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
	 * as ACT 2.0's or an import's, and returns how many it ended. A patient whose enrolments fail to
	 * close is logged and skipped.
	 *
	 * @return the number of enrolments ended; 0 when no program has a death state configured
	 */
	public static int closeEnrolmentsOfTheDead() {
		List<Integer> programIds = programsWithADeathState();
		if (programIds.isEmpty()) {
			return 0;
		}
		List<Integer> personIds = Transactions.supplyInTransaction(
		    () -> Context.getRegisteredComponents(RegistryDAO.class).get(0).getDeadPatientsWithOpenEnrolments(programIds));
		int ended = 0;
		for (Integer personId : personIds) {
			try {
				Person person = Context.getPersonService().getPerson(personId);
				if (person != null) {
					ended += close(person);
				}
			}
			catch (RuntimeException e) {
				log.error("Could not end the open enrolments of dead patient {}", personId, e);
			}
		}
		return ended;
	}
	
	/**
	 * Ends a dead patient's enrolment in its program's death state, without saving the enrolment. It
	 * reads what it needs with proxy privileges, since the user marking a patient deceased need not
	 * hold program privileges.
	 *
	 * @param enrolment the enrolment to end
	 * @param person the patient, whose death date, or now without one, ends the enrolment, moved no
	 *            earlier than the enrolment's own dates
	 * @return true when the enrolment was ended; false, leaving it unchanged, when its program has no
	 *         death state configured
	 */
	public static boolean endInDeathState(PatientProgram enrolment, Person person) {
		for (String privilege : PRIVILEGES) {
			Context.addProxyPrivilege(privilege);
		}
		try {
			ProgramWorkflowState death = deathStateOf(enrolment.getProgram());
			if (death == null) {
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
	 * Gets the state that a dead patient's enrolment in the program ends in: the state, in an unretired
	 * workflow, whose concept the registry's or the studies' death state property names. It is null
	 * when the program is neither the registry nor a study, or no such state is configured.
	 */
	static ProgramWorkflowState deathStateOf(Program program) {
		String property = deathPropertyFor(program);
		String concept = property == null ? null : Context.getAdministrationService().getGlobalProperty(property);
		if (StringUtils.isBlank(concept)) {
			return null;
		}
		for (ProgramWorkflow workflow : program.getWorkflows()) {
			if (workflow.getRetired()) {
				continue;
			}
			for (ProgramWorkflowState state : workflow.getStates(false)) {
				if (concept.trim().equals(state.getConcept().getUuid())) {
					return state;
				}
			}
		}
		log.warn("{} has no state on the concept {} names ({}); its enrolments stay open on death", program.getName(),
		    property, concept);
		return null;
	}
	
	private static String deathPropertyFor(Program program) {
		if (EnrolOnRegistration.isRegistry(program)) {
			return ActCoreConstants.GP_REGISTRY_DEATH_STATE_CONCEPT;
		}
		return program != null && Context.getService(StudyService.class).isStudy(program)
		        ? ActCoreConstants.GP_STUDIES_DEATH_STATE_CONCEPT
		        : null;
	}
	
	private static List<Integer> programsWithADeathState() {
		for (String privilege : PRIVILEGES) {
			Context.addProxyPrivilege(privilege);
		}
		try {
			return Context.getProgramWorkflowService().getAllPrograms().stream()
			        .filter(program -> deathPropertyFor(program) != null && deathStateOf(program) != null)
			        .map(Program::getProgramId).collect(Collectors.toList());
		}
		finally {
			for (String privilege : PRIVILEGES) {
				Context.removeProxyPrivilege(privilege);
			}
		}
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
	 * Moves the death date up, when needed, to the enrolment's latest recorded date: its enrolment date
	 * or a status's start or end. A death state cannot overlap the statuses recorded before it, and
	 * endInDeathState does not rewrite them.
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
