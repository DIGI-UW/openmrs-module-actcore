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
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.aopalliance.intercept.MethodInterceptor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.ModuleTables;
import org.openmrs.module.actcore.api.RaisedFlagService;
import org.openmrs.module.patientflags.Flag;
import org.openmrs.module.patientflags.PatientFlag;
import org.openmrs.module.patientflags.api.FlagService;
import org.openmrs.module.patientflags.evaluator.SQLFlagEvaluator;
import org.openmrs.test.BaseModuleContextSensitiveTest;
import org.springframework.aop.framework.Advised;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * A patient's flags read outside any transaction, as a request reads them. The advice runs inside
 * the read-only transaction of the FlagService method it filters, and its read of the dismissed
 * flags joins that transaction rather than starting one of its own.
 */
public class HideDismissedFlagsCommitContextTest extends BaseModuleContextSensitiveTest {
	
	private static final int PATIENT = 7;
	
	private final HideDismissedFlags hide = new HideDismissedFlags();
	
	private Flag flag;
	
	@Before
	public void createModuleTables() {
		ModuleTables.create();
	}
	
	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public void getPatientFlags_shouldReadTheDismissedFlagsInTheTransactionOfTheMethodItFilters() throws Exception {
		FlagService flagService = Context.getService(FlagService.class);
		flag = new Flag();
		flag.setUuid(UUID.randomUUID().toString());
		flag.setName("dismissed outside a transaction");
		flag.setCriteria("select p.patient_id from patient p where p.patient_id = " + PATIENT);
		flag.setEvaluator(SQLFlagEvaluator.class.getName());
		flag.setMessage("dismissed");
		flag.setEnabled(Boolean.TRUE);
		flagService.saveFlag(flag);
		Patient patient = Context.getPatientService().getPatient(PATIENT);
		flagService.savePatientFlag(new PatientFlag(patient, flag, "dismissed"));
		Context.getAdministrationService()
		        .executeSQL("insert into " + RaisedFlags.TABLE
		                + " (patient_id, flag_id, raised_since, dismissed_by, date_dismissed) values (" + PATIENT + ", "
		                + flag.getFlagId() + ", '2026-01-05 10:00:00', 1, '2026-01-06 10:00:00')",
		            false);
		Context.addAdvice(FlagService.class, hide);
		List<String> transactions = new ArrayList<String>();
		MethodInterceptor probe = invocation -> {
			transactions.add(TransactionSynchronizationManager.getCurrentTransactionName());
			return invocation.proceed();
		};
		// Core's transactional proxy around the implementation, inside which the service's transaction runs.
		Advised service = (Advised) ((Advised) Context.getService(RaisedFlagService.class)).getTargetSource().getTarget();
		service.addAdvice(probe);
		int shown;
		try {
			shown = flagService.getPatientFlags(patient).size();
		}
		finally {
			service.removeAdvice(probe);
		}
		
		assertEquals(0, shown);
		assertEquals(1, transactions.size());
		assertTrue(transactions.get(0), transactions.get(0).endsWith(".getPatientFlags"));
	}
	
	// Nothing rolls this test back.
	@After
	public void removeAdviceAndCommittedRows() {
		Context.removeAdvice(FlagService.class, hide);
		Context.clearSession();
		if (flag != null) {
			Context.getAdministrationService()
			        .executeSQL("delete from patientflags_patient_flag where flag_id = " + flag.getFlagId(), false);
			Context.getAdministrationService()
			        .executeSQL("delete from " + RaisedFlags.TABLE + " where flag_id = " + flag.getFlagId(), false);
			Context.getService(FlagService.class).purgeFlag(flag.getFlagId());
		}
	}
}
