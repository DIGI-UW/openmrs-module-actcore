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
import org.openmrs.PatientProgram;
import org.openmrs.PatientState;
import org.openmrs.ProgramWorkflow;
import org.openmrs.ProgramWorkflowState;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.api.StudyService;

/**
 * Advice on ProgramWorkflowService that gives a study enrolment saved without a status its study's
 * initial status, starting on the enrolment date. The chart's Programs page leaves the status
 * optional, so without this an enrolment could have none.
 */
public class StudyEnrolmentStatus implements MethodInterceptor {
	
	/** @see org.aopalliance.intercept.MethodInterceptor#invoke(MethodInvocation) */
	@Override
	public Object invoke(MethodInvocation invocation) throws Throwable {
		Object[] args = invocation.getArguments();
		if ("savePatientProgram".equals(invocation.getMethod().getName()) && args.length == 1
		        && args[0] instanceof PatientProgram) {
			startInInitialStatus((PatientProgram) args[0]);
		}
		return invocation.proceed();
	}
	
	private static void startInInitialStatus(PatientProgram enrolment) {
		if (Boolean.TRUE.equals(enrolment.getVoided()) || enrolment.getDateEnrolled() == null
		        || !Context.getService(StudyService.class).isStudy(enrolment.getProgram())) {
			return;
		}
		for (PatientState state : enrolment.getStates()) {
			if (!Boolean.TRUE.equals(state.getVoided())) {
				return;
			}
		}
		for (ProgramWorkflow workflow : enrolment.getProgram().getWorkflows()) {
			if (workflow.getRetired()) {
				continue;
			}
			for (ProgramWorkflowState state : workflow.getStates(false)) {
				if (Boolean.TRUE.equals(state.getInitial())) {
					enrolment.transitionToState(state, enrolment.getDateEnrolled());
					return;
				}
			}
		}
	}
}
