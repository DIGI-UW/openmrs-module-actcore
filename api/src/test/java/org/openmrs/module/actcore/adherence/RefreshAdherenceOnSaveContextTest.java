/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.adherence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntConsumer;

import org.junit.After;
import org.junit.Test;
import org.openmrs.Encounter;
import org.openmrs.Obs;
import org.openmrs.api.EncounterService;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.task.PatientFlagRefreshInTest;
import org.openmrs.module.patientflags.Flag;
import org.openmrs.module.patientflags.api.FlagService;
import org.openmrs.module.patientflags.evaluator.SQLFlagEvaluator;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

public class RefreshAdherenceOnSaveContextTest extends AdherenceContextTest {
	
	// The distribution's overdue flag, as of the tests' today rather than the clock's.
	private static final String OVERDUE = "select a.patient_id from actcore_prophylaxis_adherence a"
	        + " where a.injection_interval_days > 0 and a.next_due < '" + TODAY + "'";
	
	private final List<Integer> refreshed = new ArrayList<Integer>();
	
	private final List<String> refreshes = new ArrayList<String>();
	
	private final RefreshAdherenceOnSave advice = new RefreshAdherenceOnSave();
	
	private final List<Encounter> committed = new ArrayList<Encounter>();
	
	/**
	 * The encounter service with the advice outside its transaction, refreshing through the refreshers
	 * given.
	 */
	private EncounterService encounters(IntConsumer refresher, IntConsumer flagRefresher) {
		ProxyFactory proxy = new ProxyFactory(Context.getEncounterService());
		proxy.addInterface(EncounterService.class);
		proxy.addAdvice(new RefreshAdherenceOnSave(refresher, flagRefresher));
		return (EncounterService) proxy.getProxy();
	}
	
	private EncounterService encounters(IntConsumer refresher) {
		return encounters(refresher, flags());
	}
	
	private EncounterService encounters() {
		return encounters(new IntConsumer() {
			
			@Override
			public void accept(int patientId) {
				refreshed.add(patientId);
				refreshes.add("adherence");
				Context.flushSession();
				refresh.refreshPatient(patientId, TODAY);
			}
		});
	}
	
	private IntConsumer flags() {
		return new IntConsumer() {
			
			@Override
			public void accept(int patientId) {
				refreshes.add("flags");
				Context.flushSession();
				PatientFlagRefreshInTest.refreshPatient(patientId);
			}
		};
	}
	
	/** What the transaction manager does once the test's transaction commits. */
	private static void commit() {
		for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
			synchronization.afterCommit();
		}
	}
	
	@Test
	public void saveEncounter_shouldWriteThePatientsRowOnceTheSaveCommits() {
		prescribe(encounter(7, 60), oralPenicillin, 60, null);
		Encounter report = encounter(7, 0);
		estimate(report, 65.0);
		
		encounters().saveEncounter(report);
		assertTrue(rows().isEmpty());
		commit();
		
		assertEquals(0.65, ((Number) row(7).get(3)).doubleValue(), 1e-12);
	}
	
	@Test
	public void saveEncounter_shouldRefreshEachPatientOnceHoweverManyOfTheirEncountersAreSaved() {
		EncounterService service = encounters();
		service.saveEncounter(encounter(7, 10));
		service.saveEncounter(encounter(7, 5));
		service.saveEncounter(encounter(2, 5));
		
		commit();
		
		assertEquals(2, refreshed.size());
		assertTrue(refreshed.contains(7) && refreshed.contains(2));
	}
	
	@Test
	public void voidEncounter_shouldRemoveTheRowOfAPrescriptionItCarried() {
		Encounter consultation = encounter(7, 27);
		prescribe(consultation, q21, 27, null);
		refresh.refreshAll(TODAY);
		assertNotNull(row(7));
		
		encounters().voidEncounter(consultation, "entered in error");
		commit();
		
		assertTrue(rows().isEmpty());
	}
	
	@Test
	public void purgeEncounter_shouldRemoveTheRowOfAPrescriptionItCarried() {
		Encounter consultation = encounter(7, 27);
		prescribe(consultation, q21, 27, null);
		refresh.refreshAll(TODAY);
		assertNotNull(row(7));
		
		encounters().purgeEncounter(consultation, true);
		commit();
		
		assertTrue(rows().isEmpty());
	}
	
	@Test
	public void saveEncounter_shouldClearTheOverdueFlagOnceTheDoseCommits() {
		Flag overdue = overdueFlag();
		// Prescribed Q28 40 days ago and never injected: due 12 days ago.
		prescribe(encounter(7, 40), q28, 40, null);
		Context.flushSession();
		refresh.refreshPatient(7, TODAY);
		PatientFlagRefreshInTest.refreshPatient(7);
		assertEquals(1, flagged(overdue, 7));
		
		Encounter dose = unsaved(7, 0);
		Obs injection = obs(dose, injectionDate);
		injection.setValueDatetime(at(0));
		dose.addObs(injection);
		encounters().saveEncounter(dose);
		assertEquals(1, flagged(overdue, 7));
		commit();
		
		assertEquals(0, flagged(overdue, 7));
	}
	
	@Test
	public void saveEncounter_shouldRaiseTheOverdueFlagOnceAPrescriptionDueBeforeTodayCommits() {
		Flag overdue = overdueFlag();
		Encounter consultation = unsaved(7, 40);
		consultation.addObs(prescriptionGroup(consultation, q28, 40, null));
		
		encounters().saveEncounter(consultation);
		commit();
		
		assertEquals(1, flagged(overdue, 7));
	}
	
	@Test
	public void saveEncounter_shouldRefreshTheFlagsAfterTheRow() {
		encounters().saveEncounter(encounter(7, 0));
		
		commit();
		
		assertEquals(Arrays.asList("adherence", "flags"), refreshes);
	}
	
	@Test
	public void saveEncounter_shouldRefreshTheFlagsWhenTheRowFails() {
		encounters(new IntConsumer() {
			
			@Override
			public void accept(int patientId) {
				throw new IllegalStateException("refresh failed");
			}
		}).saveEncounter(encounter(7, 0));
		
		commit();
		
		assertEquals(Arrays.asList("flags"), refreshes);
	}
	
	@Test
	public void saveEncounter_shouldKeepTheSaveWhenTheFlagRefreshFails() {
		Encounter saved = encounters(new IntConsumer() {
			
			@Override
			public void accept(int patientId) {
			}
		}, new IntConsumer() {
			
			@Override
			public void accept(int patientId) {
				throw new IllegalStateException("flags failed");
			}
		}).saveEncounter(encounter(7, 0));
		
		commit();
		
		assertNotNull(Context.getEncounterService().getEncounterByUuid(saved.getUuid()));
	}
	
	@Test
	public void saveEncounter_shouldKeepTheSaveWhenTheRefreshFails() {
		Encounter saved = encounters(new IntConsumer() {
			
			@Override
			public void accept(int patientId) {
				throw new IllegalStateException("refresh failed");
			}
		}).saveEncounter(encounter(7, 0));
		
		commit();
		
		assertNotNull(Context.getEncounterService().getEncounterByUuid(saved.getUuid()));
	}
	
	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public void saveEncounter_shouldWriteThePatientsRowAfterARealCommitThroughTheAdvisedService() {
		Context.addAdvice(EncounterService.class, advice);
		prescribe(committed(encounter(7, 60)), oralPenicillin, 60, null);
		Encounter report = unsaved(7, 0);
		Obs value = obs(report, estimate);
		value.setValueNumeric(65.0);
		report.addObs(value);
		Obs lasts = obs(report, duration);
		lasts.setValueCoded(threeMonths);
		report.addObs(lasts);
		assertTrue(rows().isEmpty());
		
		committed(Context.getEncounterService().saveEncounter(report));
		
		assertEquals(0.65, ((Number) row(7).get(3)).doubleValue(), 1e-12);
	}
	
	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public void saveEncounter_shouldRefreshOnTheCommitOfATransactionNestedInOneThatRollsBack() {
		Context.addAdvice(EncounterService.class, advice);
		final TransactionTemplate inner = transaction(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		transaction(TransactionDefinition.PROPAGATION_REQUIRED).execute(new TransactionCallbackWithoutResult() {
			
			@Override
			protected void doInTransactionWithoutResult(TransactionStatus status) {
				Context.getEncounterService().saveEncounter(unsaved(2, 0));
				inner.execute(new TransactionCallbackWithoutResult() {
					
					@Override
					protected void doInTransactionWithoutResult(TransactionStatus status) {
						Encounter consultation = unsaved(7, 27);
						consultation.addObs(prescriptionGroup(consultation, q21, 27, null));
						committed(Context.getEncounterService().saveEncounter(consultation));
					}
				});
				status.setRollbackOnly();
			}
		});
		
		assertNotNull(row(7));
	}
	
	private static Flag overdueFlag() {
		Flag flag = new Flag();
		flag.setName("RHD prophylaxis overdue");
		flag.setCriteria(OVERDUE);
		flag.setEvaluator(SQLFlagEvaluator.class.getName());
		flag.setMessage("overdue");
		flag.setEnabled(Boolean.TRUE);
		Context.getService(FlagService.class).saveFlag(flag);
		return flag;
	}
	
	private static int flagged(Flag flag, int patientId) {
		Context.flushSession();
		return number(Context.getAdministrationService()
		        .executeSQL("select count(*) from patientflags_patient_flag where voided = false and patient_id = "
		                + patientId + " and flag_id = " + flag.getFlagId(),
		            true)
		        .get(0).get(0));
	}
	
	private static TransactionTemplate transaction(int propagation) {
		TransactionTemplate template = new TransactionTemplate(
		        Context.getRegisteredComponent("transactionManager", PlatformTransactionManager.class));
		template.setPropagationBehavior(propagation);
		return template;
	}
	
	private Encounter committed(Encounter encounter) {
		committed.add(encounter);
		return encounter;
	}
	
	// The NOT_SUPPORTED tests commit, so no rollback undoes their encounters and rows.
	@After
	public void removeAdviceAndCommittedRows() {
		Context.removeAdvice(EncounterService.class, advice);
		for (Encounter encounter : committed) {
			Context.getEncounterService().purgeEncounter(encounter, true);
		}
		Context.getAdministrationService().executeSQL("delete from " + AdherenceRefresh.TABLE, false);
		Context.getAdministrationService().executeSQL("delete from " + AdherenceRefresh.TIMING_TABLE, false);
	}
}
