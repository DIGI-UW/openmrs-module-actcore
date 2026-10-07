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

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.adherence.AdherenceContextTest;
import org.openmrs.module.cohort.CohortType;
import org.openmrs.module.cohort.api.CohortTypeService;
import org.openmrs.module.patientflags.Flag;
import org.openmrs.module.patientflags.api.FlagService;
import org.openmrs.module.patientflags.evaluator.SQLFlagEvaluator;

/**
 * The flag refresh against the adherence table, as the distribution's RHD prophylaxis overdue flag
 * reads it.
 */
public class PatientFlagRefreshAdherenceContextTest extends AdherenceContextTest {
	
	private static final String OVERDUE = "select a.patient_id from actcore_prophylaxis_adherence a"
	        + " where a.injection_interval_days > 0 and a.next_due < current_date";
	
	@Test
	public void raisesAFlagOnTheAdherenceTableAsOfTheSameRunWhenTheAdherenceRefreshHasNotRun() {
		CohortType type = new CohortType();
		type.setName("System List");
		type.setDescription("System List");
		Context.getService(CohortTypeService.class).saveCohortType(type);
		Flag flag = new Flag();
		flag.setName("RHD prophylaxis overdue");
		flag.setCriteria(OVERDUE);
		flag.setEvaluator(SQLFlagEvaluator.class.getName());
		flag.setMessage("overdue");
		flag.setEnabled(Boolean.TRUE);
		Context.getService(FlagService.class).saveFlag(flag);
		// Prescribed Q28 40 days ago and never injected: due 12 days ago.
		prescribe(encounter(7, 40), q28, 40, null);
		Context.flushSession();
		
		new PatientFlagRefreshTask().execute();
		
		assertEquals(1,
		    ((Number) Context.getAdministrationService()
		            .executeSQL("select count(*) from patientflags_patient_flag where voided = false and patient_id = 7"
		                    + " and flag_id = " + flag.getFlagId(),
		                true)
		            .get(0).get(0)).intValue());
	}
}
