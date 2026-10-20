/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.task;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.openmrs.Patient;
import org.openmrs.module.patientflags.PatientFlag;

/**
 * Advice on FlagService that leaves a patient's dismissed flags out of their flag rows, which the
 * patient flags REST search, and so the banner, reads. Filtering on read, rather than voiding the
 * rows, holds whenever patientflags' own thread rewrites them.
 */
public class HideDismissedFlags implements MethodInterceptor {
	
	@Override
	@SuppressWarnings("unchecked")
	public Object invoke(MethodInvocation invocation) throws Throwable {
		Object result = invocation.proceed();
		Object[] args = invocation.getArguments();
		if (!"getPatientFlags".equals(invocation.getMethod().getName()) || !(result instanceof List)
		        || ((List<?>) result).isEmpty() || args.length == 0 || !(args[0] instanceof Patient)
		        || ((Patient) args[0]).getPatientId() == null) {
			return result;
		}
		Set<Integer> dismissed = RaisedFlags.dismissedFlags(((Patient) args[0]).getPatientId());
		if (dismissed.isEmpty()) {
			return result;
		}
		List<PatientFlag> shown = new ArrayList<PatientFlag>();
		for (PatientFlag row : (List<PatientFlag>) result) {
			if (row.getFlag() == null || !dismissed.contains(row.getFlag().getFlagId())) {
				shown.add(row);
			}
		}
		return shown;
	}
}
