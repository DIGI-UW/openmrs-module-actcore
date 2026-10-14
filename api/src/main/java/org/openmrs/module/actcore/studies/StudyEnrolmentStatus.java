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
import org.openmrs.PatientProgram;
import org.openmrs.PatientState;
import org.openmrs.ProgramWorkflow;
import org.openmrs.ProgramWorkflowState;

/**
 * Advice on ProgramWorkflowService that starts a study enrolment saved without a status in its
 * study's initial one, from the enrolment date, as the chart's Programs page leaves it optional.
 */
public class StudyEnrolmentStatus implements MethodInterceptor {
	
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
		        || !new Studies().isStudy(enrolment.getProgram())) {
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
