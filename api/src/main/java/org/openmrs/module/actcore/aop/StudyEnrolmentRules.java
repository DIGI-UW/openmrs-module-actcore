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

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.apache.commons.lang3.StringUtils;
import org.openmrs.Concept;
import org.openmrs.PatientProgram;
import org.openmrs.PatientProgramAttribute;
import org.openmrs.Person;
import org.openmrs.PersonAttribute;
import org.openmrs.PersonAttributeType;
import org.openmrs.Program;
import org.openmrs.ProgramAttributeType;
import org.openmrs.api.ValidationException;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.ActCoreConstants;
import org.openmrs.module.actcore.api.StudyService;
import org.openmrs.module.actcore.utils.Transactions;
import org.openmrs.util.PrivilegeConstants;
import org.springframework.validation.BindException;
import org.springframework.validation.Errors;

/**
 * Advice on ProgramWorkflowService that enforces the rules for enrolling patients in research
 * studies. It refuses a new study enrolment without the patient's consent, their opt-in to research
 * or a Study ID, and a new open one for a patient who has died. It also refuses an edit that moves
 * an enrolment into or out of a study, or that takes the Study ID away from an open study
 * enrolment.
 */
public class StudyEnrolmentRules implements MethodInterceptor {
	
	private static final String[] PRIVILEGES = { PrivilegeConstants.GET_GLOBAL_PROPERTIES,
	        PrivilegeConstants.GET_PERSON_ATTRIBUTE_TYPES, PrivilegeConstants.GET_CONCEPTS };
	
	/** @see org.aopalliance.intercept.MethodInterceptor#invoke(MethodInvocation) */
	@Override
	public Object invoke(MethodInvocation invocation) throws Throwable {
		Object[] args = invocation.getArguments();
		if ("savePatientProgram".equals(invocation.getMethod().getName()) && args.length == 1
		        && args[0] instanceof PatientProgram) {
			// The rules read the stored row, which a commit of their own service calls would overwrite with the edit.
			return Transactions.inTransaction(() -> {
				refuseUnlessAllowed((PatientProgram) args[0]);
				return invocation.proceed();
			});
		}
		return invocation.proceed();
	}
	
	private static void refuseUnlessAllowed(PatientProgram enrolment) {
		// A merge copies each enrolment to the preferred patient as a new one, without its Study ID.
		if (Boolean.TRUE.equals(enrolment.getVoided()) || enrolment.getPatient() == null
		        || OneOpenRegistryEnrolment.isSuspended()) {
			return;
		}
		StudyService studies = Context.getService(StudyService.class);
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
			// Not on an edit, so a withdrawal after consent is revoked and CloseEnrolmentsOnDeath still save.
			if (!existing) {
				// A completed enrolment is history, such as ACT 2.0's, which a later death does not undo.
				if (Boolean.TRUE.equals(enrolment.getPatient().getDead()) && enrolment.getDateCompleted() == null) {
					throw refusal(enrolment, "The patient has died, so they cannot be enrolled in a study");
				}
				Concept yes = conceptByProperty(ActCoreConstants.GP_STUDIES_YES_CONCEPT);
				if (!answeredYes(enrolment, ActCoreConstants.GP_STUDIES_CONSENT_ATTRIBUTE_TYPE, yes)) {
					throw refusal(enrolment,
					    "The patient has not given registry consent, so they cannot be enrolled in a study."
					            + " Record Consent Obtained as Yes on their registration first");
				}
				if (!answeredYes(enrolment, ActCoreConstants.GP_STUDIES_OPT_IN_ATTRIBUTE_TYPE, yes)) {
					throw refusal(enrolment,
					    "The patient has not said they would like to be considered for research, so they cannot be"
					            + " enrolled in a study. Record their answer on the Research participation page first");
				}
			}
			// Attributes can be edited, so an open enrolment keeps the Study ID it was saved with.
			if (!hasStudyId(enrolment)
			        && (!existing || enrolment.getDateCompleted() == null && !studies.isSavedOpenWithoutAttribute(enrolment,
			            property(ActCoreConstants.GP_STUDIES_PARTICIPANT_ID_ATTRIBUTE_TYPE)))) {
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
	 * Refuses to move a saved enrolment to another program when either program is a study. Such a move
	 * would skip the enrolment rules and carry the Study ID of one study into another.
	 */
	private static void refuseMoveAcrossStudies(PatientProgram enrolment, StudyService studies) {
		Program was = studies.getSavedProgram(enrolment);
		if (was == null || enrolment.getProgram() == null
		        || was.getProgramId().equals(enrolment.getProgram().getProgramId())) {
			return;
		}
		if (studies.isStudy(enrolment.getProgram()) || studies.isStudy(was)) {
			throw refusal(enrolment, "A study enrolment stays in its study: end this enrolment and enrol the patient"
			        + " in the other study from their Research participation page");
		}
	}
	
	/**
	 * Tells whether the enrolment's patient answered Yes to the attribute that the property names. It
	 * is true when the property is blank, which turns the rule off, for example while migrating, and
	 * when the attribute type or the Yes concept cannot be found.
	 */
	private static boolean answeredYes(PatientProgram enrolment, String property, Concept yes) {
		String uuid = property(property);
		if (uuid == null) {
			return true;
		}
		PersonAttributeType type = Context.getPersonService().getPersonAttributeTypeByUuid(uuid);
		if (type == null || yes == null) {
			return true;
		}
		return answeredYes(enrolment.getPatient(), type, yes);
	}
	
	/**
	 * Tells whether the person's attribute of the type holds the Yes concept's id. The research
	 * download reads the research opt-in through this method too.
	 *
	 * @param person the person whose attribute to read
	 * @param type the attribute type holding the answer
	 * @param yes the Yes concept
	 * @return true when the person's attribute of the type holds the concept's id; false when they have
	 *         none
	 */
	public static boolean answeredYes(Person person, PersonAttributeType type, Concept yes) {
		PersonAttribute answer = person.getAttribute(type);
		return answer != null && yes.getConceptId().toString().equals(answer.getValue());
	}
	
	private static boolean hasStudyId(PatientProgram enrolment) {
		String uuid = property(ActCoreConstants.GP_STUDIES_PARTICIPANT_ID_ATTRIBUTE_TYPE);
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
	
	private static ValidationException refusal(PatientProgram enrolment, String message) {
		Errors errors = new BindException(enrolment, "patientProgram");
		errors.reject("actcore.studyEnrolment", message);
		return new ValidationException(message, errors);
	}
}
