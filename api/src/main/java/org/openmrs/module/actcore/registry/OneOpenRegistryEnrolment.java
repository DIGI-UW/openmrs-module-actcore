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
import java.util.ArrayList;
import java.util.List;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.apache.commons.lang3.StringUtils;
import org.openmrs.Patient;
import org.openmrs.PatientProgram;
import org.openmrs.api.ProgramWorkflowService;
import org.openmrs.api.ValidationException;
import org.openmrs.api.context.Context;
import org.openmrs.util.PrivilegeConstants;
import org.springframework.validation.BindException;
import org.springframework.validation.Errors;

/**
 * Refuses an RHD Registry enrolment overlapping another one when either is open, which core allows,
 * and folds such pairs into one when PatientService merges two patients.
 */
public class OneOpenRegistryEnrolment implements MethodInterceptor {
	
	private static final ThreadLocal<Boolean> MERGING = ThreadLocal.withInitial(() -> false);
	
	@Override
	public Object invoke(MethodInvocation invocation) throws Throwable {
		Object[] args = invocation.getArguments();
		String method = invocation.getMethod().getName();
		if ("mergePatients".equals(method) && args.length == 2 && args[0] instanceof Patient && !MERGING.get()) {
			return merge(invocation, (Patient) args[0]);
		}
		if ("savePatientProgram".equals(method) && args.length == 1 && args[0] instanceof PatientProgram && !MERGING.get()) {
			refuseOverlappingEnrolment((PatientProgram) args[0]);
		}
		return invocation.proceed();
	}
	
	private static Object merge(MethodInvocation invocation, Patient preferred) throws Throwable {
		// Refusing mid-merge would fail core's merge, which moves or copies each enrolment in its own save.
		MERGING.set(true);
		try {
			Object merged = invocation.proceed();
			foldOverlappingEnrolments(preferred);
			return merged;
		}
		finally {
			MERGING.remove();
		}
	}
	
	private static void foldOverlappingEnrolments(Patient patient) {
		String registry = Context.getAdministrationService().getGlobalProperty(EnrolOnRegistration.PROGRAM_PROPERTY);
		if (StringUtils.isBlank(registry)) {
			return;
		}
		// The merging user needs Edit Patients, not Delete Patient Programs, to void the duplicate.
		String[] privileges = { PrivilegeConstants.GET_PATIENT_PROGRAMS, PrivilegeConstants.EDIT_PATIENT_PROGRAMS,
		        PrivilegeConstants.DELETE_PATIENT_PROGRAMS };
		for (String privilege : privileges) {
			Context.addProxyPrivilege(privilege);
		}
		try {
			ProgramWorkflowService programs = Context.getProgramWorkflowService();
			List<PatientProgram> enrolments = new ArrayList<>();
			for (PatientProgram pp : programs.getPatientPrograms(patient, null, null, null, null, null, false)) {
				if (pp.getProgram() != null && registry.trim().equals(pp.getProgram().getUuid())) {
					enrolments.add(pp);
				}
			}
			PatientProgram[] pair;
			while ((pair = firstOverlap(enrolments)) != null) {
				boolean firstOpen = pair[0].getDateCompleted() == null;
				PatientProgram keep = firstOpen ? pair[0] : pair[1];
				PatientProgram drop = firstOpen ? pair[1] : pair[0];
				if (start(drop) < start(keep)) {
					keep.setDateEnrolled(drop.getDateEnrolled());
				}
				programs.voidPatientProgram(drop, "Merged into RHD Registry enrolment " + keep.getUuid());
				programs.savePatientProgram(keep);
				enrolments.remove(drop);
			}
		}
		finally {
			for (String privilege : privileges) {
				Context.removeProxyPrivilege(privilege);
			}
		}
	}
	
	private static PatientProgram[] firstOverlap(List<PatientProgram> enrolments) {
		for (int i = 0; i < enrolments.size(); i++) {
			for (int j = i + 1; j < enrolments.size(); j++) {
				if (overlap(enrolments.get(i), enrolments.get(j))) {
					return new PatientProgram[] { enrolments.get(i), enrolments.get(j) };
				}
			}
		}
		return null;
	}
	
	private static boolean overlap(PatientProgram a, PatientProgram b) {
		boolean eitherOpen = a.getDateCompleted() == null || b.getDateCompleted() == null;
		return eitherOpen && start(a) < end(b) && start(b) < end(a);
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
				if (!same && overlap(existing, enrolment)) {
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
