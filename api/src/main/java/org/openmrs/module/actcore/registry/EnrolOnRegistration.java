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

import java.util.Date;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.apache.commons.lang3.StringUtils;
import org.openmrs.Patient;
import org.openmrs.PatientProgram;
import org.openmrs.Program;
import org.openmrs.api.ProgramWorkflowService;
import org.openmrs.api.context.Context;
import org.openmrs.util.PrivilegeConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Advice on PatientService that enrols each newly registered patient in the RHD Registry, as ACT
 * 2.0 put every registered patient in the registry.
 */
public class EnrolOnRegistration implements MethodInterceptor {
	
	public static final String PROGRAM_PROPERTY = "actcore.registryProgram";
	
	private static final Logger log = LoggerFactory.getLogger(EnrolOnRegistration.class);
	
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
	
	private static void enrol(Patient patient) {
		String uuid = Context.getAdministrationService().getGlobalProperty(PROGRAM_PROPERTY);
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
				log.warn("{} names no program ({}); {} is registered but not enrolled", PROGRAM_PROPERTY, uuid,
				    patient.getUuid());
				return;
			}
			PatientProgram enrolment = new PatientProgram();
			enrolment.setPatient(patient);
			enrolment.setProgram(registry);
			enrolment.setDateEnrolled(new Date());
			enrolment.setLocation(Context.getUserContext().getLocation());
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
