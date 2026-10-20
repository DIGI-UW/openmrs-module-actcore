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

import java.util.List;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.apache.commons.lang3.StringUtils;
import org.openmrs.Concept;
import org.openmrs.PatientProgram;
import org.openmrs.PatientProgramAttribute;
import org.openmrs.PersonAttribute;
import org.openmrs.PersonAttributeType;
import org.openmrs.Program;
import org.openmrs.ProgramAttributeType;
import org.openmrs.api.ValidationException;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.registry.OneOpenRegistryEnrolment;
import org.openmrs.util.PrivilegeConstants;
import org.springframework.validation.BindException;
import org.springframework.validation.Errors;

/**
 * Refuses a new study enrolment without consent, the opt-in, a Study ID or a living patient, and an
 * edit that changes a study enrolment's program or strips an open one of its Study ID.
 */
public class StudyEnrolmentRules implements MethodInterceptor {
	
	public static final String CONSENT_PROPERTY = "actcore.studies.consentAttributeType";
	
	public static final String OPT_IN_PROPERTY = "actcore.studies.optInAttributeType";
	
	public static final String YES_PROPERTY = "actcore.studies.yesConcept";
	
	public static final String STUDY_ID_PROPERTY = "actcore.studies.participantIdAttributeType";
	
	private static final String[] PRIVILEGES = { PrivilegeConstants.GET_GLOBAL_PROPERTIES,
	        PrivilegeConstants.GET_PERSON_ATTRIBUTE_TYPES, PrivilegeConstants.GET_CONCEPTS };
	
	@Override
	public Object invoke(MethodInvocation invocation) throws Throwable {
		Object[] args = invocation.getArguments();
		if ("savePatientProgram".equals(invocation.getMethod().getName()) && args.length == 1
		        && args[0] instanceof PatientProgram) {
			refuseUnlessAllowed((PatientProgram) args[0]);
		}
		return invocation.proceed();
	}
	
	private static void refuseUnlessAllowed(PatientProgram enrolment) {
		// A merge copies each enrolment to the preferred patient as a new one, without its Study ID.
		if (Boolean.TRUE.equals(enrolment.getVoided()) || enrolment.getPatient() == null
		        || OneOpenRegistryEnrolment.isSuspended()) {
			return;
		}
		Studies studies = new Studies();
		boolean existing = enrolment.getPatientProgramId() != null;
		if (existing) {
			refuseMoveAcrossStudies(enrolment, studies);
		}
		if (!studies.isStudy(enrolment.getProgram())) {
			return;
		}
		for (String privilege : PRIVILEGES) {
			Context.addProxyPrivilege(privilege);
		}
		try {
			// Not on an edit, so a withdrawal after consent is revoked and CloseStudiesOnDeath still save.
			if (!existing) {
				// A completed enrolment is history, such as ACT 2.0's, which a later death does not undo.
				if (Boolean.TRUE.equals(enrolment.getPatient().getDead()) && enrolment.getDateCompleted() == null) {
					throw refusal(enrolment, "The patient has died, so they cannot be enrolled in a study");
				}
				Concept yes = conceptByProperty(YES_PROPERTY);
				if (!answeredYes(enrolment, CONSENT_PROPERTY, yes)) {
					throw refusal(enrolment,
					    "The patient has not given registry consent, so they cannot be enrolled in a study."
					            + " Record Consent Obtained as Yes on their registration first");
				}
				if (!answeredYes(enrolment, OPT_IN_PROPERTY, yes)) {
					throw refusal(enrolment,
					    "The patient has not said they would like to be considered for research, so they cannot be"
					            + " enrolled in a study. Record their answer on the Research participation page first");
				}
			}
			// Attributes can be edited, so an open enrolment keeps its Study ID; a completed one is history.
			if ((!existing || enrolment.getDateCompleted() == null) && !hasStudyId(enrolment)) {
				throw refusal(enrolment,
				    "A study enrolment needs a Study ID. Enrol the patient from their Research participation page");
			}
		}
		finally {
			for (String privilege : PRIVILEGES) {
				Context.removeProxyPrivilege(privilege);
			}
		}
	}
	
	/**
	 * An enrolment stays in the program it was saved in when that or the new one is a study: moving it
	 * would skip the enrolment rules and carry the Study ID of one study into another.
	 */
	private static void refuseMoveAcrossStudies(PatientProgram enrolment, Studies studies) {
		Integer stored = storedProgramId(enrolment.getPatientProgramId());
		if (stored == null || enrolment.getProgram() == null || stored.equals(enrolment.getProgram().getProgramId())) {
			return;
		}
		Context.addProxyPrivilege(PrivilegeConstants.GET_PROGRAMS);
		try {
			Program was = Context.getProgramWorkflowService().getProgram(stored);
			if (studies.isStudy(enrolment.getProgram()) || (was != null && studies.isStudy(was))) {
				throw refusal(enrolment, "A study enrolment stays in its study: end this enrolment and enrol the patient"
				        + " in the other study from their Research participation page");
			}
		}
		finally {
			Context.removeProxyPrivilege(PrivilegeConstants.GET_PROGRAMS);
		}
	}
	
	/**
	 * The program the enrolment is saved in, read from the table as the session holds the edited one.
	 */
	private static Integer storedProgramId(int patientProgramId) {
		Context.addProxyPrivilege(PrivilegeConstants.SQL_LEVEL_ACCESS);
		try {
			List<List<Object>> rows = Context.getAdministrationService().executeSQL(
			    "select program_id from patient_program where patient_program_id = " + patientProgramId, true);
			return rows == null || rows.isEmpty() || rows.get(0).get(0) == null ? null
			        : ((Number) rows.get(0).get(0)).intValue();
		}
		finally {
			Context.removeProxyPrivilege(PrivilegeConstants.SQL_LEVEL_ACCESS);
		}
	}
	
	/** True when the property is blank, which turns the rule off, for example while migrating. */
	private static boolean answeredYes(PatientProgram enrolment, String property, Concept yes) {
		String uuid = property(property);
		if (uuid == null) {
			return true;
		}
		PersonAttributeType type = Context.getPersonService().getPersonAttributeTypeByUuid(uuid);
		if (type == null || yes == null) {
			return true;
		}
		PersonAttribute answer = enrolment.getPatient().getAttribute(type);
		return answer != null && yes.getConceptId().toString().equals(answer.getValue());
	}
	
	private static boolean hasStudyId(PatientProgram enrolment) {
		String uuid = property(STUDY_ID_PROPERTY);
		if (uuid == null) {
			return true;
		}
		for (PatientProgramAttribute attribute : enrolment.getActiveAttributes()) {
			ProgramAttributeType type = attribute.getAttributeType();
			if (type != null && uuid.equals(type.getUuid()) && attribute.getValue() != null
			        && StringUtils.isNotBlank(attribute.getValue().toString())) {
				return true;
			}
		}
		return false;
	}
	
	private static Concept conceptByProperty(String property) {
		String uuid = property(property);
		return uuid == null ? null : Context.getConceptService().getConceptByUuid(uuid);
	}
	
	private static String property(String property) {
		String value = Context.getAdministrationService().getGlobalProperty(property);
		return StringUtils.isBlank(value) ? null : value.trim();
	}
	
	// As a global error, so the REST API answers 400 with the message rather than a stack trace.
	private static ValidationException refusal(PatientProgram enrolment, String message) {
		Errors errors = new BindException(enrolment, "patientProgram");
		errors.reject("actcore.studyEnrolment", message);
		return new ValidationException(message, errors);
	}
}
