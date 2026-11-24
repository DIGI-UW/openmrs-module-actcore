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

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.apache.commons.lang3.StringUtils;
import org.openmrs.Patient;
import org.openmrs.PatientProgram;
import org.openmrs.Program;
import org.openmrs.api.ProgramWorkflowService;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.ActCoreConstants;
import org.openmrs.util.PrivilegeConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Advice on PatientService that enrols each newly registered patient in the RHD Registry, as ACT
 * 2.0 put every registered patient in the registry. Nothing is enrolled while the registry program
 * is not configured.
 */
public class EnrolOnRegistration implements MethodInterceptor {
	
	private static final Logger log = LoggerFactory.getLogger(EnrolOnRegistration.class);
	
	/** @see org.aopalliance.intercept.MethodInterceptor#invoke(MethodInvocation) */
	@Override
	public Object invoke(MethodInvocation invocation) throws Throwable {
		Object[] args = invocation.getArguments();
		boolean registering = "savePatient".equals(invocation.getMethod().getName()) && args.length == 1
		        && args[0] instanceof Patient && ((Patient) args[0]).getPatientId() == null;
		Object saved = invocation.proceed();
		if (registering) {
			enrol((Patient) saved);
		}
		return saved;
	}
	
	/**
	 * Tells whether the program is the RHD Registry, by comparing its uuid with the one in
	 * {@value ActCoreConstants#GP_REGISTRY_PROGRAM}.
	 *
	 * @param program the program, or null
	 * @return true when the program's uuid is the property's; false for a null program or when the
	 *         property is blank
	 */
	public static boolean isRegistry(Program program) {
		String registry = Context.getAdministrationService().getGlobalProperty(ActCoreConstants.GP_REGISTRY_PROGRAM);
		return program != null && StringUtils.isNotBlank(registry) && registry.trim().equals(program.getUuid());
	}
	
	private static void enrol(Patient patient) {
		String uuid = Context.getAdministrationService().getGlobalProperty(ActCoreConstants.GP_REGISTRY_PROGRAM);
		if (StringUtils.isBlank(uuid)) {
			return;
		}
		// The registering user needs Add Patients, not program privileges.
		String[] privileges = { PrivilegeConstants.GET_PROGRAMS, PrivilegeConstants.ADD_PATIENT_PROGRAMS,
		        PrivilegeConstants.GET_PATIENT_PROGRAMS };
		for (String privilege : privileges) {
			Context.addProxyPrivilege(privilege);
		}
		try {
			ProgramWorkflowService programs = Context.getProgramWorkflowService();
			Program registry = programs.getProgramByUuid(uuid.trim());
			if (registry == null) {
				log.warn("{} names no program ({}); {} is registered but not enrolled", ActCoreConstants.GP_REGISTRY_PROGRAM,
				    uuid, patient.getUuid());
				return;
			}
			PatientProgram enrolment = new PatientProgram();
			enrolment.setPatient(patient);
			enrolment.setProgram(registry);
			enrolment.setDateEnrolled(new Date());
			enrolment.setLocation(Context.getUserContext().getLocation());
			// A patient registered after they died is enrolled ended in the death state, never open.
			if (Boolean.TRUE.equals(patient.getDead())) {
				CloseEnrolmentsOnDeath.endInDeathState(enrolment, patient);
			}
			programs.savePatientProgram(enrolment);
		}
		catch (RuntimeException e) {
			// Registration stands; the chart's Programs page can enrol the patient instead.
			log.error("Could not enrol {} in the RHD Registry", patient.getUuid(), e);
		}
		finally {
			for (String privilege : privileges) {
				Context.removeProxyPrivilege(privilege);
			}
		}
	}
}
