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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.After;
import org.junit.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.patientflags.Flag;
import org.openmrs.module.patientflags.api.FlagService;
import org.openmrs.module.patientflags.evaluator.SQLFlagEvaluator;
import org.openmrs.test.BaseModuleContextSensitiveTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * One patient's flag refresh committed for real: a criterion that fails marks the transaction it
 * ran in rollback-only, which a test's own transaction would hide.
 */
public class PatientFlagRefreshPatientCommitContextTest extends BaseModuleContextSensitiveTest {
	
	private static final int PATIENT = 7;
	
	private final List<Flag> saved = new ArrayList<Flag>();
	
	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public void raisesTheFlagsBehindOneWhoseCriteriaFails() {
		saveFlag("broken", "select x.patient_id from no_such_table x");
		Flag flag = saveFlag("overdue", "select p.patient_id from patient p where p.patient_id = " + PATIENT);
		
		PatientFlagRefreshTask.refreshPatient(PATIENT);
		
		assertEquals(1,
		    ((Number) Context.getAdministrationService()
		            .executeSQL("select count(*) from patientflags_patient_flag where voided = false and flag_id = "
		                    + flag.getFlagId() + " and patient_id = " + PATIENT,
		                true)
		            .get(0).get(0)).intValue());
	}
	
	private Flag saveFlag(String name, String criteria) {
		Flag flag = new Flag();
		flag.setUuid(UUID.randomUUID().toString());
		flag.setName(name);
		flag.setCriteria(criteria);
		flag.setEvaluator(SQLFlagEvaluator.class.getName());
		flag.setMessage(name);
		flag.setEnabled(Boolean.TRUE);
		Context.getService(FlagService.class).saveFlag(flag);
		saved.add(flag);
		return flag;
	}
	
	// Nothing rolls this test back.
	@After
	public void removeCommittedFlags() {
		for (Flag flag : saved) {
			Context.getAdministrationService()
			        .executeSQL("delete from patientflags_patient_flag where flag_id = " + flag.getFlagId(), false);
			Context.getService(FlagService.class).purgeFlag(flag.getFlagId());
		}
	}
}
