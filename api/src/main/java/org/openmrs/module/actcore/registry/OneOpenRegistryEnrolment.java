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

import java.text.SimpleDateFormat;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.apache.commons.lang3.StringUtils;
import org.openmrs.PatientProgram;
import org.openmrs.api.ValidationException;
import org.openmrs.api.context.Context;
import org.openmrs.util.PrivilegeConstants;
import org.springframework.validation.BindException;
import org.springframework.validation.Errors;

/**
 * Advice on ProgramWorkflowService that refuses an RHD Registry enrolment overlapping another one
 * when either is open, which core allows; the registry shows only the latest-dated one.
 */
public class OneOpenRegistryEnrolment implements MethodInterceptor {
	
	@Override
	public Object invoke(MethodInvocation invocation) throws Throwable {
		Object[] args = invocation.getArguments();
		if ("savePatientProgram".equals(invocation.getMethod().getName()) && args.length == 1
		        && args[0] instanceof PatientProgram) {
			refuseOverlappingEnrolment((PatientProgram) args[0]);
		}
		return invocation.proceed();
	}
	
	private static void refuseOverlappingEnrolment(PatientProgram enrolment) {
		String registry = Context.getAdministrationService().getGlobalProperty(EnrolOnRegistration.PROGRAM_PROPERTY);
		if (Boolean.TRUE.equals(enrolment.getVoided()) || enrolment.getProgram() == null || StringUtils.isBlank(registry)
		        || !registry.trim().equals(enrolment.getProgram().getUuid())) {
			return;
		}
		Context.addProxyPrivilege(PrivilegeConstants.GET_PATIENT_PROGRAMS);
		try {
			for (PatientProgram existing : Context.getProgramWorkflowService().getPatientPrograms(enrolment.getPatient(),
			    enrolment.getProgram(), null, null, null, null, false)) {
				boolean same = existing == enrolment || (enrolment.getPatientProgramId() != null
				        && enrolment.getPatientProgramId().equals(existing.getPatientProgramId()));
				boolean eitherOpen = existing.getDateCompleted() == null || enrolment.getDateCompleted() == null;
				if (!same && eitherOpen && start(existing) < end(enrolment) && start(enrolment) < end(existing)) {
					throw refusal(enrolment, existing);
				}
			}
		}
		finally {
			Context.removeProxyPrivilege(PrivilegeConstants.GET_PATIENT_PROGRAMS);
		}
	}
	
	private static long start(PatientProgram enrolment) {
		return enrolment.getDateEnrolled() == null ? Long.MIN_VALUE : enrolment.getDateEnrolled().getTime();
	}
	
	private static long end(PatientProgram enrolment) {
		return enrolment.getDateCompleted() == null ? Long.MAX_VALUE : enrolment.getDateCompleted().getTime();
	}
	
	private static ValidationException refusal(PatientProgram enrolment, PatientProgram existing) {
		SimpleDateFormat date = new SimpleDateFormat("dd-MMM-yyyy");
		String message = existing.getDateCompleted() == null
		        ? "The patient is already enrolled in " + existing.getProgram().getName() + " since "
		                + date.format(existing.getDateEnrolled()) + "; edit that enrolment instead"
		        : "This enrolment overlaps the patient's enrolment in " + existing.getProgram().getName() + " from "
		                + date.format(existing.getDateEnrolled()) + " to " + date.format(existing.getDateCompleted())
		                + "; edit that enrolment instead";
		// As a global error, so the REST API answers 400 with the message rather than a stack trace.
		Errors errors = new BindException(enrolment, "patientProgram");
		errors.reject("actcore.alreadyEnrolled", message);
		return new ValidationException(message, errors);
	}
}
