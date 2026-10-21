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
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.openmrs.Patient;
import org.openmrs.module.patientflags.Flag;
import org.openmrs.module.patientflags.PatientFlag;

/**
 * Advice on FlagService that filters a patient's dismissed flags out on read, which holds whenever
 * patientflags' own thread rewrites their rows, as voiding them would not.
 */
public class HideDismissedFlags implements MethodInterceptor {
	
	// Not generateFlagsForPatient: patientflags' own thread writes the patient's rows from it.
	private static final List<String> FILTERED = Arrays.asList("getPatientFlags", "getFlagsForPatient");
	
	@Override
	public Object invoke(MethodInvocation invocation) throws Throwable {
		Object result = invocation.proceed();
		Object[] args = invocation.getArguments();
		if (!FILTERED.contains(invocation.getMethod().getName()) || !(result instanceof List) || ((List<?>) result).isEmpty()
		        || args.length == 0 || !(args[0] instanceof Patient) || ((Patient) args[0]).getPatientId() == null) {
			return result;
		}
		Set<Integer> dismissed = RaisedFlags.dismissedFlags(((Patient) args[0]).getPatientId());
		if (dismissed.isEmpty()) {
			return result;
		}
		List<Object> shown = new ArrayList<Object>();
		for (Object row : (List<?>) result) {
			Flag flag = row instanceof PatientFlag ? ((PatientFlag) row).getFlag() : (Flag) row;
			if (flag == null || !dismissed.contains(flag.getFlagId())) {
				shown.add(row);
			}
		}
		return shown;
	}
}
