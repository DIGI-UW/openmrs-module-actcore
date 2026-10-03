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
 * Advice on ProgramWorkflowService that refuses a second open RHD Registry enrolment, which core
 * allows and which would list the patient twice in the registry.
 */
public class OneOpenRegistryEnrolment implements MethodInterceptor {
	
	@Override
	public Object invoke(MethodInvocation invocation) throws Throwable {
		Object[] args = invocation.getArguments();
		if ("savePatientProgram".equals(invocation.getMethod().getName()) && args.length == 1
		        && args[0] instanceof PatientProgram) {
			refuseSecondOpenEnrolment((PatientProgram) args[0]);
		}
		return invocation.proceed();
	}
	
	private static void refuseSecondOpenEnrolment(PatientProgram enrolment) {
		String registry = Context.getAdministrationService().getGlobalProperty(EnrolOnRegistration.PROGRAM_PROPERTY);
		if (enrolment.getPatientProgramId() != null || enrolment.getDateCompleted() != null
		        || Boolean.TRUE.equals(enrolment.getVoided()) || enrolment.getProgram() == null
		        || StringUtils.isBlank(registry) || !registry.trim().equals(enrolment.getProgram().getUuid())) {
			return;
		}
		Context.addProxyPrivilege(PrivilegeConstants.GET_PATIENT_PROGRAMS);
		try {
			for (PatientProgram existing : Context.getProgramWorkflowService().getPatientPrograms(enrolment.getPatient(),
			    enrolment.getProgram(), null, null, null, null, false)) {
				if (existing.getDateCompleted() == null) {
					String message = "The patient is already enrolled in " + existing.getProgram().getName() + " since "
					        + new SimpleDateFormat("dd-MMM-yyyy").format(existing.getDateEnrolled())
					        + "; edit that enrolment instead";
					// As a global error, so the REST API answers 400 with the message rather than a stack trace.
					Errors errors = new BindException(enrolment, "patientProgram");
					errors.reject("actcore.alreadyEnrolled", message);
					throw new ValidationException(message, errors);
				}
			}
		}
		finally {
			Context.removeProxyPrivilege(PrivilegeConstants.GET_PATIENT_PROGRAMS);
		}
	}
}
