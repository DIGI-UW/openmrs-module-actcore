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
import static org.junit.Assert.fail;

import java.util.UUID;

import org.hibernate.SessionFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openmrs.GlobalProperty;
import org.openmrs.Patient;
import org.openmrs.api.AdministrationService;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.ModuleTables;
import org.openmrs.module.actcore.api.RaisedFlagService;
import org.openmrs.module.patientflags.Flag;
import org.openmrs.module.patientflags.api.FlagService;
import org.openmrs.module.patientflags.evaluator.SQLFlagEvaluator;
import org.openmrs.test.BaseModuleContextSensitiveTest;
import org.springframework.aop.MethodBeforeAdvice;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Flags dismissed outside any transaction, as a request dismisses them. The dismissal commits
 * before the list is updated, so a failing update leaves it recorded, and a read during the
 * dismissal that goes through the request's session does not fail it.
 */
public class DismissFlagCommitContextTest extends BaseModuleContextSensitiveTest {
	
	private static final int PATIENT = 7;
	
	// Throws where the list update reads the flag by its id.
	private final MethodBeforeAdvice failListUpdate = (method, args, target) -> {
		if (method.getName().equals("getFlag") && args.length == 1 && args[0] instanceof Integer) {
			throw new IllegalStateException("list update failed");
		}
	};
	
	// Reads as core 2.8's getGlobalProperty does on a cache miss: through the session, in SUPPORTS.
	private final MethodBeforeAdvice readThroughTheSession = (method, args, target) -> {
		if (method.getName().equals("getGlobalProperty")) {
			TransactionTemplate template = new TransactionTemplate(
			        Context.getRegisteredComponent("transactionManager", PlatformTransactionManager.class));
			template.setPropagationBehavior(TransactionDefinition.PROPAGATION_SUPPORTS);
			template.setReadOnly(true);
			template.execute(status -> Context.getRegisteredComponent("sessionFactory", SessionFactory.class)
			        .getCurrentSession().get(GlobalProperty.class, (String) args[0]));
		}
	};
	
	private Flag flag;
	
	@Before
	public void createModuleTables() {
		ModuleTables.create();
	}
	
	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public void dismiss_shouldKeepTheDismissalWhenTheListUpdateFails() {
		FlagService flagService = Context.getService(FlagService.class);
		flag = new Flag();
		flag.setUuid(UUID.randomUUID().toString());
		flag.setName("dismissed while its list fails");
		flag.setCriteria("select p.patient_id from patient p where p.patient_id = " + PATIENT);
		flag.setEvaluator(SQLFlagEvaluator.class.getName());
		flag.setMessage("dismissed");
		flag.setEnabled(Boolean.TRUE);
		flagService.saveFlag(flag);
		Patient patient = Context.getPatientService().getPatient(PATIENT);
		RaisedFlagService raisedFlags = Context.getService(RaisedFlagService.class);
		
		Context.addAdvice(FlagService.class, failListUpdate);
		try {
			raisedFlags.dismiss(patient, flag);
			fail("Expected the list update to fail");
		}
		catch (IllegalStateException e) {
			assertEquals("list update failed", e.getMessage());
		}
		finally {
			Context.removeAdvice(FlagService.class, failListUpdate);
		}
		
		Context.clearSession();
		assertTrue(raisedFlags.isDismissed(flag.getFlagId(), PATIENT));
	}
	
	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public void dismiss_shouldDismissWhenItsGlobalPropertyIsReadThroughTheSession() {
		FlagService flagService = Context.getService(FlagService.class);
		flag = new Flag();
		flag.setUuid(UUID.randomUUID().toString());
		flag.setName("dismissed on a global property cache miss");
		flag.setCriteria("select p.patient_id from patient p where p.patient_id = " + PATIENT);
		flag.setEvaluator(SQLFlagEvaluator.class.getName());
		flag.setMessage("dismissed");
		flag.setEnabled(Boolean.TRUE);
		flagService.saveFlag(flag);
		Patient patient = Context.getPatientService().getPatient(PATIENT);
		
		Context.addAdvice(AdministrationService.class, readThroughTheSession);
		RaisedFlags.Dismissal dismissal;
		try {
			dismissal = Context.getService(RaisedFlagService.class).dismiss(patient, flag);
		}
		finally {
			Context.removeAdvice(AdministrationService.class, readThroughTheSession);
		}
		
		assertEquals(RaisedFlags.Dismissal.DISMISSED, dismissal);
	}
	
	// Nothing rolls this test back.
	@After
	public void removeAdviceAndCommittedRows() {
		Context.removeAdvice(FlagService.class, failListUpdate);
		Context.removeAdvice(AdministrationService.class, readThroughTheSession);
		Context.clearSession();
		if (flag != null) {
			Context.getAdministrationService().executeSQL(
			    "delete from " + RaisedFlags.DISMISSAL_TABLE + " where flag_id = " + flag.getFlagId(), false);
			Context.getAdministrationService()
			        .executeSQL("delete from " + RaisedFlags.TABLE + " where flag_id = " + flag.getFlagId(), false);
			Context.getService(FlagService.class).purgeFlag(flag.getFlagId());
		}
	}
}
