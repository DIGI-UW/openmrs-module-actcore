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
import static org.junit.Assert.assertNull;

import java.time.LocalDate;

import org.junit.Test;

/**
 * Cases where a regimen changes after the consultation that records it, against ACT 2.0's
 * save-then-nightly runs.
 */
public class AdherenceReplayTest {
	
	private static final LocalDate TODAY = LocalDate.of(2026, 9, 29);
	
	private static final int Q28 = 28, Q21 = 21, ORAL = 0;
	
	private static final int Q28_ID = 1, Q21_ID = 2, ORAL_ID = 3;
	
	private final AdherenceReplay.History h = new AdherenceReplay.History();
	
	private static LocalDate ago(int days) {
		return TODAY.minusDays(days);
	}
	
	private void prescribed(int onDaysAgo, int startedDaysAgo, int interval, int regimen) {
		AdherenceReplay.Prescriptions p = h.prescriptions.get(ago(onDaysAgo));
		if (p == null) {
			p = new AdherenceReplay.Prescriptions();
			h.prescriptions.put(ago(onDaysAgo), p);
		}
		p.intervals.put(ago(startedDaysAgo), interval);
		p.regimens.put(ago(startedDaysAgo), regimen);
	}
	
	private void injectedEvery28Until(int fromDaysAgo, int lastDaysAgo) {
		for (int daysAgo = fromDaysAgo; daysAgo >= lastDaysAgo; daysAgo -= 28) {
			h.injections.add(ago(daysAgo));
		}
	}
	
	private void estimated(int daysAgo, double percent) {
		h.oral.put(ago(daysAgo), new AdherenceCalculation.OralEntry(percent, 90));
	}
	
	private AdherenceCalculation.Result asOf(int daysAgo) {
		return AdherenceCalculation.calculate(h.prescriptions.lastEntry().getValue().intervals, h.injections, h.oral,
		    ago(daysAgo));
	}
	
	@Test
	public void replay_shouldRecomputeAContinuouslyEnteredInjectionPatientEveryNight() {
		prescribed(200, 200, Q28, Q28_ID);
		injectedEvery28Until(200, 88);
		h.consultations.add(ago(200));
		
		AdherenceReplay.Row row = AdherenceReplay.replay(h, TODAY);
		
		assertEquals(Integer.valueOf(Q28_ID), row.regimen);
		assertEquals(asOf(0).getAdherence(), row.adherence);
	}
	
	@Test
	public void replay_shouldHoldABatchEnteredPatientUntilTheBatchIsDue() {
		prescribed(200, 200, Q28, Q28_ID);
		injectedEvery28Until(200, 88);
		h.consultations.add(ago(84));
		h.batchDays.put(ago(84), 90);
		
		assertEquals(asOf(84).getAdherence(), AdherenceReplay.replay(h, TODAY).adherence);
		// Once the 90 days have passed, ACT 2.0's nightly run takes them up again.
		assertEquals(AdherenceCalculation
		        .calculate(h.prescriptions.lastEntry().getValue().intervals, h.injections, h.oral, TODAY.plusDays(10))
		        .getAdherence(),
		    AdherenceReplay.replay(h, TODAY.plusDays(10)).adherence);
	}
	
	@Test
	public void replay_shouldFreezeAPatientSwitchedToOralOnTheOralStartDate() {
		// The reviewer's ZZA: Q28 and a later oral start recorded together, entered continuously.
		prescribed(10, 700, Q28, Q28_ID);
		prescribed(10, 3, ORAL, ORAL_ID);
		injectedEvery28Until(700, 56);
		estimated(500, 60.0);
		h.consultations.add(ago(10));
		
		AdherenceReplay.Row row = AdherenceReplay.replay(h, TODAY);
		
		// The nightly run stored oral on the start date and never ran again.
		assertEquals(Integer.valueOf(ORAL_ID), row.regimen);
		assertEquals(asOf(3).getAdherence(), row.adherence);
		assertEquals(0.931507, row.adherence, 1e-6);
	}
	
	@Test
	public void replay_shouldKeepAnOralPatientWhoseInjectionStartsAfterTheConsultation() {
		// The reviewer's ZZB: oral, then Q28 starting after the consultation that records both.
		prescribed(30, 300, ORAL, ORAL_ID);
		prescribed(30, 10, Q28, Q28_ID);
		injectedEvery28Until(400, 320);
		estimated(30, 80.0);
		h.consultations.add(ago(30));
		
		AdherenceReplay.Row row = AdherenceReplay.replay(h, TODAY);
		
		assertEquals(Integer.valueOf(ORAL_ID), row.regimen);
		assertEquals(0.8, row.adherence, 1e-12);
		assertEquals(TODAY.plusDays(60), row.nextDue);
	}
	
	@Test
	public void replay_shouldStoreNoRegimenForAFirstPrescriptionThatStartsAfterItsConsultation() {
		// The reviewer's ZZC: Q21 starting 5 days ago, recorded 20 days ago, nothing saved since.
		prescribed(20, 5, Q21, Q21_ID);
		h.consultations.add(ago(20));
		
		AdherenceReplay.Row row = AdherenceReplay.replay(h, TODAY);
		
		assertNull(row.regimen);
		assertNull(row.adherence);
		assertNull(row.nextDue);
	}
	
	@Test
	public void replay_shouldRecomputeAnOralPatientWhenAnInjectionIsSavedAfterTheConsultation() {
		// The reviewer's (e): ACT 2.0 recomputed on the BPG delivery's save, after the consultation.
		prescribed(60, 60, ORAL, ORAL_ID);
		estimated(60, 70.0);
		h.consultations.add(ago(60));
		h.injections.add(ago(20));
		prescribed(20, 20, Q28, Q28_ID);
		
		AdherenceReplay.Row row = AdherenceReplay.replay(h, TODAY);
		
		assertEquals(Integer.valueOf(Q28_ID), row.regimen);
		assertEquals(TODAY.minusDays(20).plusDays(28), row.nextDue);
	}
	
	@Test
	public void replay_shouldHoldNoRowForAPatientWhoseRecordsAreAllInTheFuture() {
		prescribed(-5, -5, Q28, Q28_ID);
		h.injections.add(ago(-5));
		
		assertNull(AdherenceReplay.replay(h, TODAY));
	}
	
	@Test
	public void replay_shouldHoldNoRowForAPatientWhoseLatestPrescriptionsWereAllStopped() {
		h.prescriptions.put(ago(30), new AdherenceReplay.Prescriptions());
		h.consultations.add(ago(30));
		
		assertNull(AdherenceReplay.replay(h, TODAY));
	}
}
