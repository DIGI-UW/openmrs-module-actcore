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

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.apache.commons.lang3.StringUtils;
import org.openmrs.Concept;
import org.openmrs.PatientProgram;
import org.openmrs.PatientProgramAttribute;
import org.openmrs.PersonAttribute;
import org.openmrs.PersonAttributeType;
import org.openmrs.ProgramAttributeType;
import org.openmrs.api.ValidationException;
import org.openmrs.api.context.Context;
import org.openmrs.util.PrivilegeConstants;
import org.springframework.validation.BindException;
import org.springframework.validation.Errors;

/**
 * Advice on ProgramWorkflowService that refuses a new study enrolment unless the patient is alive,
 * gave registry consent, would like to be considered for research, and has a Study ID in the study,
 * whichever page enrols them.
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
		if (enrolment.getPatientProgramId() != null || Boolean.TRUE.equals(enrolment.getVoided())
		        || enrolment.getPatient() == null || !new Studies().isStudy(enrolment.getProgram())) {
			return;
		}
		for (String privilege : PRIVILEGES) {
			Context.addProxyPrivilege(privilege);
		}
		try {
			if (Boolean.TRUE.equals(enrolment.getPatient().getDead())) {
				throw refusal(enrolment, "The patient has died, so they cannot be enrolled in a study");
			}
			Concept yes = conceptByProperty(YES_PROPERTY);
			if (!answeredYes(enrolment, CONSENT_PROPERTY, yes)) {
				throw refusal(enrolment, "The patient has not given registry consent, so they cannot be enrolled in a study."
				        + " Record Consent Obtained as Yes on their registration first");
			}
			if (!answeredYes(enrolment, OPT_IN_PROPERTY, yes)) {
				throw refusal(enrolment,
				    "The patient has not said they would like to be considered for research, so they cannot be enrolled"
				            + " in a study. Record their answer on the Research participation page first");
			}
			if (!hasStudyId(enrolment)) {
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
