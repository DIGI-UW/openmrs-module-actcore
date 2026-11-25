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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.openmrs.module.actcore.utils.TransactionsContextTest.await;
import static org.openmrs.module.actcore.utils.TransactionsContextTest.inThread;
import static org.openmrs.module.actcore.utils.TransactionsContextTest.join;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import org.aopalliance.intercept.MethodInvocation;
import org.hibernate.StaleStateException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openmrs.Encounter;
import org.openmrs.api.EncounterService;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.ActCoreConstants;
import org.openmrs.module.actcore.ModuleTables;
import org.openmrs.module.actcore.aop.RefreshAdherenceOnSave;
import org.openmrs.module.actcore.api.RaisedFlagService;
import org.openmrs.module.actcore.utils.Transactions;
import org.openmrs.module.patientflags.Flag;
import org.openmrs.module.patientflags.PatientFlag;
import org.openmrs.module.patientflags.api.FlagService;
import org.openmrs.module.patientflags.evaluator.SQLFlagEvaluator;
import org.openmrs.test.BaseModuleContextSensitiveTest;
import org.springframework.orm.hibernate5.HibernateOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The refreshes that run after a save and nightly, run as they run there: outside any transaction,
 * with a second thread standing in for a concurrent save.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class PatientRefreshLockContextTest extends BaseModuleContextSensitiveTest {
	
	private static final int PATIENT = 7;
	
	private static final int OTHER_PATIENT = 2;
	
	private static final long WAIT_MILLIS = 500;
	
	// Well under H2's lock timeout, which a refresh waiting on the patient's row would reach first.
	private static final long LEFT_MILLIS = 5000;
	
	private final List<Throwable> failed = Collections.synchronizedList(new ArrayList<Throwable>());
	
	private Flag flag;
	
	@Before
	public void createModuleTables() {
		ModuleTables.create();
	}
	
	@Test
	public void raisesAFlagOnceWhenTwoRefreshesOfThePatientRaiseItAtOnce() {
		flag = saveFlag("select p.patient_id from patient p where p.patient_id = " + PATIENT);
		Thread[] second = new Thread[1];
		
		Transactions.inPatientTransaction(PATIENT, () -> {
			raisedFlags().raise(flag.getFlagId(), PATIENT);
			second[0] = inThread(failed,
			    () -> Transactions.inPatientTransaction(PATIENT, () -> raisedFlags().raise(flag.getFlagId(), PATIENT)));
			// The second refresh is left time to read the patient as not yet raised, as it did unlocked.
			await(new CountDownLatch(1), WAIT_MILLIS);
		});
		
		join(second[0]);
		assertEquals(Collections.emptyList(), failed);
		assertEquals(1L, count("select count(*) from " + ActCoreConstants.RAISED_FLAG_TABLE + " where patient_id = "
		        + PATIENT + " and flag_id = " + flag.getFlagId()));
	}
	
	@Test
	public void refreshesAPatientsFlagsOnlyOnceNoOtherUnitHoldsThePatient() {
		assertWaitsForThePatient(() -> PatientFlagRefresh.refreshPatient(PATIENT));
	}
	
	@Test
	public void refreshesAPatientsAdherenceOnSaveOnlyOnceNoOtherUnitHoldsThePatient() throws Exception {
		Encounter encounter = new Encounter();
		encounter.setPatient(Context.getPatientService().getPatient(PATIENT));
		MethodInvocation save = mock(MethodInvocation.class);
		when(save.getMethod()).thenReturn(EncounterService.class.getMethod("saveEncounter", Encounter.class));
		when(save.getArguments()).thenReturn(new Object[] { encounter });
		
		assertWaitsForThePatient(() -> {
			try {
				new RefreshAdherenceOnSave().invoke(save);
			}
			catch (Throwable e) {
				throw new IllegalStateException(e);
			}
		});
	}
	
	@Test
	public void leavesASaveToTheFullAdherenceRefreshAtOnceWhileItRewritesTheRows() throws Exception {
		Encounter encounter = new Encounter();
		encounter.setPatient(Context.getPatientService().getPatient(PATIENT));
		MethodInvocation save = mock(MethodInvocation.class);
		when(save.getMethod()).thenReturn(EncounterService.class.getMethod("saveEncounter", Encounter.class));
		when(save.getArguments()).thenReturn(new Object[] { encounter });
		
		assertLeftToTheFullAdherenceRefresh(() -> {
			try {
				new RefreshAdherenceOnSave().invoke(save);
			}
			catch (Throwable e) {
				throw new IllegalStateException(e);
			}
		});
	}
	
	@Test
	public void leavesAPatientsFlagsToTheFullAdherenceRefreshAtOnceWhileItRewritesTheRows() {
		assertLeftToTheFullAdherenceRefresh(() -> PatientFlagRefresh.refreshPatient(PATIENT));
	}
	
	@Test
	public void clearsEveryRowOfAFlagNightlyWhenPatientflagsDeletedOneFirst() {
		flag = saveFlag("select p.patient_id from patient p where p.patient_id = -1");
		FlagService flagService = Context.getService(FlagService.class);
		flagService.savePatientFlag(new PatientFlag(Context.getPatientService().getPatient(OTHER_PATIENT), flag, "old"));
		flagService.savePatientFlag(new PatientFlag(Context.getPatientService().getPatient(PATIENT), flag, "old"));
		PatientFlagRefresh refresh = new PatientFlagRefresh();
		
		refresh.reconcile(deletingTheRowFirstOnce(flagService), flag);
		
		assertEquals(2, refresh.cleared);
		assertEquals(0L,
		    count("select count(*) from patientflags_patient_flag where voided = false and flag_id = " + flag.getFlagId()));
	}
	
	/**
	 * Runs the refresh on another thread while a unit holds the patient, and checks that it waits for
	 * that unit to end.
	 */
	private void assertWaitsForThePatient(Runnable refresh) {
		CountDownLatch done = new CountDownLatch(1);
		Thread[] second = new Thread[1];
		
		Transactions.inPatientTransaction(PATIENT, () -> {
			second[0] = inThread(failed, () -> {
				refresh.run();
				done.countDown();
			});
			assertFalse("the refresh ran while another unit held the patient", await(done, WAIT_MILLIS));
		});
		
		join(second[0]);
		assertEquals(0, done.getCount());
		assertEquals(Collections.emptyList(), failed);
	}
	
	/**
	 * Runs the refresh on another thread while a full adherence refresh, run on its own as the
	 * scheduled adherence task runs it, rewrites the rows, and checks that the refresh returns at once
	 * and leaves the patient to that run. On MySQL, the rewrite's inserts hold the patient's row
	 * through their foreign key until it commits; H2 does not, so a unit holds the row here instead.
	 */
	private void assertLeftToTheFullAdherenceRefresh(Runnable refresh) {
		CountDownLatch done = new CountDownLatch(1);
		Thread[] second = new Thread[1];
		
		List<Integer> readAfter = AdherenceRefreshInTest.whileRewritingEveryPatient(
		    () -> Transactions.inPatientTransaction(PATIENT, () -> {
			    second[0] = inThread(failed, () -> {
				    refresh.run();
				    done.countDown();
			    });
			    assertTrue("the refresh waited for the full adherence refresh", await(done, LEFT_MILLIS));
		    }));
		
		join(second[0]);
		assertEquals(Collections.emptyList(), failed);
		assertEquals(Collections.singletonList(PATIENT), readAfter);
	}
	
	/**
	 * The flag service, except that the first row it is asked to delete is deleted by another
	 * transaction first, as the patientflags module's own thread does, so the delete fails as it then
	 * does.
	 */
	private static FlagService deletingTheRowFirstOnce(FlagService flagService) {
		AtomicBoolean deletedFirst = new AtomicBoolean();
		return (FlagService) Proxy.newProxyInstance(FlagService.class.getClassLoader(), new Class<?>[] { FlagService.class },
		    (proxy, method, args) -> {
			    if ("deletePatientFlagForPatient".equals(method.getName()) && deletedFirst.compareAndSet(false, true)) {
				    List<Throwable> failed = new ArrayList<Throwable>();
				    join(inThread(failed,
				        () -> Transactions.inNewTransaction(() -> Context.getAdministrationService()
				                .executeSQL("delete from patientflags_patient_flag where patient_id = "
				                        + ((org.openmrs.Patient) args[0]).getPatientId() + " and flag_id = "
				                        + ((Flag) args[1]).getFlagId(),
				                    false))));
				    assertEquals(Collections.emptyList(), failed);
				    throw new HibernateOptimisticLockingFailureException(
				            new StaleStateException("Batch update returned unexpected row count from update [0];"
				                    + " actual row count: 0; expected: 1"));
			    }
			    try {
				    return method.invoke(flagService, args);
			    }
			    catch (InvocationTargetException e) {
				    throw e.getCause();
			    }
		    });
	}
	
	private static RaisedFlagService raisedFlags() {
		return Context.getService(RaisedFlagService.class);
	}
	
	private static long count(String sql) {
		return ((Number) Context.getAdministrationService().executeSQL(sql, true).get(0).get(0)).longValue();
	}
	
	private static Flag saveFlag(String criteria) {
		Flag flag = new Flag();
		flag.setUuid(UUID.randomUUID().toString());
		flag.setName("concurrent");
		flag.setCriteria(criteria);
		flag.setEvaluator(SQLFlagEvaluator.class.getName());
		flag.setMessage("concurrent");
		flag.setEnabled(Boolean.TRUE);
		Context.getService(FlagService.class).saveFlag(flag);
		return flag;
	}
	
	// Nothing rolls this test back.
	@After
	public void removeCommittedFlag() {
		if (flag != null) {
			Context.getAdministrationService()
			        .executeSQL("delete from patientflags_patient_flag where flag_id = " + flag.getFlagId(), false);
			Context.getAdministrationService().executeSQL(
			    "delete from " + ActCoreConstants.RAISED_FLAG_TABLE + " where flag_id = " + flag.getFlagId(), false);
			Context.getService(FlagService.class).purgeFlag(flag.getFlagId());
		}
	}
}
