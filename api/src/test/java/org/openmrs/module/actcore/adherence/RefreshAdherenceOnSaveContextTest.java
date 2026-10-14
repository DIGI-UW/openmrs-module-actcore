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
import java.util.List;
import java.util.function.IntConsumer;

import org.junit.After;
import org.junit.Test;
import org.openmrs.Encounter;
import org.openmrs.Obs;
import org.openmrs.api.EncounterService;
import org.openmrs.api.context.Context;
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
	
	private final List<Integer> refreshed = new ArrayList<Integer>();
	
	private final RefreshAdherenceOnSave advice = new RefreshAdherenceOnSave();
	
	private final List<Encounter> committed = new ArrayList<Encounter>();
	
	/**
	 * The encounter service with the advice outside its transaction, refreshing through the refresher
	 * given.
	 */
	private EncounterService encounters(IntConsumer refresher) {
		ProxyFactory proxy = new ProxyFactory(Context.getEncounterService());
		proxy.addInterface(EncounterService.class);
		proxy.addAdvice(new RefreshAdherenceOnSave(refresher));
		return (EncounterService) proxy.getProxy();
	}
	
	private EncounterService encounters() {
		return encounters(new IntConsumer() {
			
			@Override
			public void accept(int patientId) {
				refreshed.add(patientId);
				Context.flushSession();
				refresh.refreshPatient(patientId, TODAY);
			}
		});
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
