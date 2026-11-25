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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import org.aopalliance.intercept.MethodInvocation;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
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
import org.openmrs.module.actcore.model.DismissalOutcome;
import org.openmrs.module.actcore.utils.Transactions;
import org.openmrs.module.patientflags.Flag;
import org.openmrs.module.patientflags.PatientFlag;
import org.openmrs.module.patientflags.api.FlagService;
import org.openmrs.module.patientflags.evaluator.SQLFlagEvaluator;
import org.openmrs.test.BaseModuleContextSensitiveTest;
import org.springframework.aop.MethodBeforeAdvice;
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
	
	private final List<Thread> elsewhere = new ArrayList<Thread>();
	
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
	public void dismissesAFlagOnlyOnceNoOtherUnitHoldsThePatient() {
		flag = saveFlag("select p.patient_id from patient p where p.patient_id = " + PATIENT);
		List<DismissalOutcome> outcome = new ArrayList<DismissalOutcome>();
		
		assertWaitsForThePatient(() -> outcome.add(raisedFlags().dismiss(Context.getPatientService().getPatient(PATIENT),
		    Context.getService(FlagService.class).getFlag(flag.getFlagId()))));
		
		assertEquals(Collections.singletonList(DismissalOutcome.DISMISSED), outcome);
		assertEquals(1L, count("select count(*) from " + ActCoreConstants.RAISED_FLAG_TABLE + " where patient_id = "
		        + PATIENT + " and flag_id = " + flag.getFlagId() + " and date_dismissed is not null"));
	}
	
	@Test
	public void dismissesAFlagInACallersTransactionOnlyOnceNoOtherUnitHoldsThePatient() {
		flag = saveFlag("select p.patient_id from patient p where p.patient_id = " + PATIENT);
		List<DismissalOutcome> outcome = new ArrayList<DismissalOutcome>();
		
		assertWaitsForThePatient(() -> outcome.add(
		    Transactions.supplyInTransaction(() -> raisedFlags().dismiss(Context.getPatientService().getPatient(PATIENT),
		        Context.getService(FlagService.class).getFlag(flag.getFlagId())))));
		
		assertEquals(Collections.singletonList(DismissalOutcome.DISMISSED), outcome);
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
	public void clearsAFlagsRowsNightlyWithoutAnErrorWhenPatientflagsDeletesThemFirst() {
		flag = saveFlag("select p.patient_id from patient p where p.patient_id = -1");
		FlagService flagService = Context.getService(FlagService.class);
		flagService.savePatientFlag(new PatientFlag(Context.getPatientService().getPatient(OTHER_PATIENT), flag, "old"));
		flagService.savePatientFlag(new PatientFlag(Context.getPatientService().getPatient(PATIENT), flag, "old"));
		List<Integer> attempts = Collections.synchronizedList(new ArrayList<Integer>());
		PatientFlagRefresh refresh = new PatientFlagRefresh() {
			
			@Override
			void inPatientTransaction(int patientId, Runnable work) {
				Transactions.inPatientTransaction(patientId, () -> {
					attempts.add(patientId);
					work.run();
					deleteAsPatientflagsDoes(patientId);
				});
			}
		};
		
		List<LogEvent> errors = errors(() -> refresh.reconcile(flagService, flag));
		
		assertEquals(Collections.emptyList(), errors);
		assertEquals(2, attempts.size());
		assertEquals(new HashSet<Integer>(Arrays.asList(PATIENT, OTHER_PATIENT)), new HashSet<Integer>(attempts));
		assertEquals(2, refresh.cleared);
		assertEquals(0L, flagRows());
	}
	
	@Test
	public void clearsAPatientsRowWithoutAnErrorWhenPatientflagsDeletesItFirst() {
		flag = saveFlag("select p.patient_id from patient p where p.patient_id = -1");
		Context.getService(FlagService.class)
		        .savePatientFlag(new PatientFlag(Context.getPatientService().getPatient(PATIENT), flag, "old"));
		Map<Runnable, Integer> attempts = Collections.synchronizedMap(new IdentityHashMap<Runnable, Integer>());
		// Lowering the raised flag follows the row's delete in the same unit.
		MethodBeforeAdvice deleteBeforeLowering = (method, args, target) -> {
			if ("lower".equals(method.getName()) && flag.getFlagId().equals(args[0])) {
				deleteAsPatientflagsDoes(PATIENT);
			}
		};
		
		Context.addAdvice(RaisedFlagService.class, deleteBeforeLowering);
		List<LogEvent> errors;
		try {
			errors = errors(
			    () -> PatientFlagRefresh.refreshPatient(PATIENT, work -> Transactions.inPatientTransaction(PATIENT, () -> {
				    attempts.merge(work, 1, Integer::sum);
				    work.run();
			    })));
		}
		finally {
			Context.removeAdvice(RaisedFlagService.class, deleteBeforeLowering);
		}
		
		assertEquals(Collections.emptyList(), errors);
		assertEquals(Collections.singleton(1), new HashSet<Integer>(attempts.values()));
		assertEquals(0L, flagRows());
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
	
	@Test
	public void rewritesARowNightlyWithoutAnErrorWhenPatientflagsDeletesItFirst() {
		flag = saveFlag("select p.patient_id from patient p where p.patient_id = " + PATIENT);
		FlagService flagService = Context.getService(FlagService.class);
		flagService.savePatientFlag(new PatientFlag(Context.getPatientService().getPatient(PATIENT), flag, "old"));
		List<Integer> attempts = Collections.synchronizedList(new ArrayList<Integer>());
		PatientFlagRefresh refresh = new PatientFlagRefresh() {
			
			@Override
			void inPatientTransaction(int patientId, Runnable work) {
				Transactions.inPatientTransaction(patientId, () -> {
					attempts.add(patientId);
					work.run();
					deleteAsPatientflagsDoes(patientId);
				});
			}
		};
		
		List<LogEvent> errors = errors(() -> refresh.reconcile(flagService, flag));
		
		assertEquals(Collections.emptyList(), errors);
		assertEquals(Collections.singletonList(PATIENT), attempts);
	}
	
	@Test
	public void rewritesAPatientsRowWithoutAnErrorWhenPatientflagsDeletesItFirst() {
		flag = saveFlag("select p.patient_id from patient p where p.patient_id = " + PATIENT);
		Context.getService(FlagService.class)
		        .savePatientFlag(new PatientFlag(Context.getPatientService().getPatient(PATIENT), flag, "old"));
		Map<Runnable, Integer> attempts = Collections.synchronizedMap(new IdentityHashMap<Runnable, Integer>());
		// The rewritten row is saved after the old one is deleted, in the same unit.
		MethodBeforeAdvice deleteBeforeSaving = (method, args, target) -> {
			if ("savePatientFlag".equals(method.getName())
			        && flag.getFlagId().equals(((PatientFlag) args[0]).getFlag().getFlagId())) {
				deleteAsPatientflagsDoes(PATIENT);
			}
		};
		
		Context.addAdvice(FlagService.class, deleteBeforeSaving);
		List<LogEvent> errors;
		try {
			errors = errors(
			    () -> PatientFlagRefresh.refreshPatient(PATIENT, work -> Transactions.inPatientTransaction(PATIENT, () -> {
				    attempts.merge(work, 1, Integer::sum);
				    work.run();
			    })));
		}
		finally {
			Context.removeAdvice(FlagService.class, deleteBeforeSaving);
		}
		
		assertEquals(Collections.emptyList(), errors);
		assertEquals(Collections.singleton(1), new HashSet<Integer>(attempts.values()));
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
		
		List<Integer> readAfter = AdherenceRefreshInTest
		        .whileRewritingEveryPatient(() -> Transactions.inPatientTransaction(PATIENT, () -> {
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
	 * Deletes the flag's rows for the patient in a transaction on another thread, as the patientflags
	 * module's own thread deletes them, while the caller's unit has deleted them and not yet committed.
	 * A delete that holds the rows makes it wait for the unit to end; one that does not lets it finish
	 * first.
	 */
	private void deleteAsPatientflagsDoes(int patientId) {
		CountDownLatch deleted = new CountDownLatch(1);
		elsewhere.add(inThread(failed, () -> {
			Transactions.inNewTransaction(() -> Context.getAdministrationService()
			        .executeSQL("delete from patientflags_patient_flag where patient_id = " + patientId + " and flag_id = "
			                + flag.getFlagId(),
			            false));
			deleted.countDown();
		}));
		await(deleted, WAIT_MILLIS);
	}
	
	/** What Hibernate and ACT Core log at ERROR while the work runs. */
	private static List<LogEvent> errors(Runnable work) {
		List<LogEvent> logged = Collections.synchronizedList(new ArrayList<LogEvent>());
		AbstractAppender appender = new AbstractAppender("capture", null, null, true, Property.EMPTY_ARRAY) {
			
			@Override
			public void append(LogEvent event) {
				logged.add(event.toImmutable());
			}
		};
		appender.start();
		List<Logger> loggers = Arrays.asList((Logger) LogManager.getLogger("org.hibernate"),
		    (Logger) LogManager.getLogger("org.openmrs.module.actcore"));
		List<Level> levels = new ArrayList<Level>();
		for (Logger logger : loggers) {
			levels.add(logger.getLevel());
			logger.addAppender(appender);
			// The test log4j2.xml turns every logger off; this sets its child loggers' level too.
			Configurator.setLevel(logger.getName(), Level.ERROR);
		}
		try {
			work.run();
		}
		finally {
			for (int i = 0; i < loggers.size(); i++) {
				loggers.get(i).removeAppender(appender);
				Configurator.setLevel(loggers.get(i).getName(), levels.get(i));
			}
		}
		return logged;
	}
	
	private long flagRows() {
		return count(
		    "select count(*) from patientflags_patient_flag where voided = false and flag_id = " + flag.getFlagId());
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
		for (Thread thread : elsewhere) {
			join(thread);
		}
		assertEquals(Collections.emptyList(), failed);
		if (flag != null) {
			Context.getAdministrationService()
			        .executeSQL("delete from patientflags_patient_flag where flag_id = " + flag.getFlagId(), false);
			Context.getAdministrationService().executeSQL(
			    "delete from " + ActCoreConstants.FLAG_DISMISSAL_TABLE + " where flag_id = " + flag.getFlagId(), false);
			Context.getAdministrationService().executeSQL(
			    "delete from " + ActCoreConstants.RAISED_FLAG_TABLE + " where flag_id = " + flag.getFlagId(), false);
			Context.getService(FlagService.class).purgeFlag(flag.getFlagId());
		}
	}
}
