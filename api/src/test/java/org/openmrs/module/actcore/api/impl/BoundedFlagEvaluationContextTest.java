/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.api.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.HashMap;

import org.junit.Test;
import org.openmrs.Patient;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.module.patientflags.Flag;
import org.openmrs.module.patientflags.evaluator.GroovyFlagEvaluator;
import org.openmrs.test.BaseModuleContextSensitiveTest;

public class BoundedFlagEvaluationContextTest extends BaseModuleContextSensitiveTest {
	
	private static Flag groovyFlag(String criteria) {
		Flag flag = new Flag();
		flag.setName("groovy");
		flag.setCriteria(criteria);
		flag.setEvaluator(GroovyFlagEvaluator.class.getName());
		flag.setEnabled(Boolean.TRUE);
		return flag;
	}
	
	@Test(timeout = 20000)
	public void within_shouldEndPatientflagsWaitForAGroovyScriptThatRunsPastTheBound() {
		Patient patient = Context.getPatientService().getPatient(2);
		// It would match: the script gives the patient back once it wakes.
		Flag flag = groovyFlag("Thread.sleep(3000); return testCohort");
		try {
			BoundedFlagEvaluation.within(flag, () -> flag.eval(patient, new HashMap<Object, Object>()), 300);
			fail("The evaluation was not given up");
		}
		catch (APIException e) {
			assertTrue(e.getMessage(), e.getMessage().startsWith("Gave up evaluating flag 'groovy'"));
			assertTrue(String.valueOf(e.getCause()),
			    e.getCause().getMessage().startsWith("Unable to evaluate Groovy Flag groovy"));
		}
		assertFalse(Thread.interrupted());
	}
	
	@Test(timeout = 20000)
	public void within_shouldGiveWhatAGroovyScriptThatEndsInTimeGives() {
		Patient patient = Context.getPatientService().getPatient(2);
		Flag flag = groovyFlag("return testCohort");
		
		assertEquals(Boolean.TRUE,
		    BoundedFlagEvaluation.within(flag, () -> flag.eval(patient, new HashMap<Object, Object>()), 10000));
	}
}
