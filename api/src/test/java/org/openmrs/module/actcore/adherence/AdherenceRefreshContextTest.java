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
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;
import org.openmrs.Encounter;
import org.openmrs.Obs;
import org.openmrs.api.context.Context;

public class AdherenceRefreshContextTest extends AdherenceContextTest {
	
	@Test
	public void refreshAll_shouldGiveNoRegimenToAConsultationThatPrescribesNone() {
		Encounter earlier = encounter(7, 120);
		prescribe(earlier, oralPenicillin, 120, null);
		// The latest consultation answers None, with a start date, as the form allows.
		prescribe(encounter(7, 20), none, 20, null);
		
		assertEquals(0, refresh.refreshAll(TODAY));
		
		ProphylaxisSummary summary = ProphylaxisSummary.of(Context.getPatientService().getPatient(7), TODAY);
		assertEquals("none", summary.getStatus());
		assertNull(summary.getType());
	}
	
	@Test
	public void refreshAll_shouldStoreEachInjectionTimedAsTheChartTimesIt() {
		prescribe(encounter(7, 100), q21, 100, null);
		// 20 days after the course starts, then 30 days after that (9 days late), then 20 days after that.
		given(encounter(7, 80), 80);
		given(encounter(7, 50), 50);
		given(encounter(7, 30), 30);
		
		refresh.refreshAll(TODAY);
		
		List<List<Object>> timings = Context.getAdministrationService().executeSQL("select injection_date, on_time from "
		        + AdherenceRefresh.TIMING_TABLE + " where patient_id = 7 order by injection_date",
		    true);
		assertEquals(3, timings.size());
		assertEquals(TODAY.minusDays(80), date(timings.get(0).get(0)));
		assertEquals(Boolean.TRUE, timings.get(0).get(1));
		assertEquals(TODAY.minusDays(50), date(timings.get(1).get(0)));
		assertEquals(Boolean.FALSE, timings.get(1).get(1));
		assertEquals(TODAY.minusDays(30), date(timings.get(2).get(0)));
		assertEquals(Boolean.TRUE, timings.get(2).get(1));
	}
	
	@Test
	public void refreshAll_shouldStoreNoTimingForAnInjectionGivenWithNoCourseInForce() {
		prescribe(encounter(7, 40), q28, 40, null);
		// Before the course starts, so nothing times it, as the chart leaves it untimed.
		given(encounter(7, 60), 60);
		given(encounter(7, 20), 20);
		
		refresh.refreshAll(TODAY);
		
		List<List<Object>> timings = Context.getAdministrationService()
		        .executeSQL("select injection_date from " + AdherenceRefresh.TIMING_TABLE + " where patient_id = 7", true);
		assertEquals(1, timings.size());
		assertEquals(TODAY.minusDays(20), date(timings.get(0).get(0)));
	}
	
	@Test
	public void refreshAll_shouldStoreTheAdherenceAndDueDateOfAnInjectionRegimen() {
		Encounter consultation = encounter(7, 27);
		prescribe(consultation, q21, 27, null);
		given(consultation, 27);
		
		assertEquals(1, refresh.refreshAll(TODAY));
		
		List<Object> row = row(7);
		assertEquals((int) q21.getConceptId(), number(row.get(1)));
		assertEquals(21, number(row.get(2)));
		// ACT 2.0's case: prescribed and injected 27 days ago, due again 21 days later, so 6 of 28 days late.
		assertEquals(1 - 6.0 / 28, ((Number) row.get(3)).doubleValue(), 1e-12);
		assertEquals(TODAY.minusDays(27), date(row.get(4)));
		assertEquals(TODAY.minusDays(6), date(row.get(5)));
	}
	
	@Test
	public void refreshAll_shouldTakeThePrescriptionsOfTheLatestEncounterThatRecordsAny() {
		prescribe(encounter(7, 200), q28, 200, null);
		Encounter latest = encounter(7, 27);
		prescribe(latest, q21, 27, null);
		given(latest, 27);
		
		refresh.refreshAll(TODAY);
		
		assertEquals((int) q21.getConceptId(), number(row(7).get(1)));
		assertEquals(1 - 6.0 / 28, ((Number) row(7).get(3)).doubleValue(), 1e-12);
	}
	
	@Test
	public void refreshAll_shouldLeaveOutAPrescriptionThatWasStopped() {
		Encounter consultation = encounter(7, 27);
		prescribe(consultation, q21, 27, 10);
		
		assertEquals(0, refresh.refreshAll(TODAY));
		
		assertTrue(rows().isEmpty());
	}
	
	@Test
	public void refreshAll_shouldTakeTheLatestEstimateOnAnOralRegimen() {
		prescribe(encounter(7, 60), oralPenicillin, 60, null);
		estimate(encounter(7, 40), 55.0);
		estimate(encounter(7, 15), 70.0);
		
		refresh.refreshAll(TODAY);
		
		List<Object> row = row(7);
		assertEquals(0, number(row.get(2)));
		assertEquals(0.7, ((Number) row.get(3)).doubleValue(), 1e-12);
		assertEquals(TODAY.minusDays(15), date(row.get(4)));
		assertEquals(TODAY.minusDays(15).plusDays(90), date(row.get(5)));
	}
	
	@Test
	public void refreshAll_shouldIgnoreVoidedEncountersAndReplaceEarlierRows() {
		prescribe(encounter(7, 27), q21, 27, null);
		prescribe(encounter(2, 27), q28, 27, null);
		refresh.refreshAll(TODAY);
		assertEquals(2, rows().size());
		
		for (Encounter encounter : Context.getEncounterService().getEncountersByPatientId(2)) {
			Context.getEncounterService().voidEncounter(encounter, "test");
		}
		// The refresh reads with SQL, which sees only what the session has flushed.
		Context.flushSession();
		refresh.refreshAll(TODAY);
		
		assertEquals(1, rows().size());
		assertEquals(7, number(rows().get(0).get(0)));
	}
	
	@Test
	public void refreshAll_shouldGiveNoAdherenceToAnInjectionRegimenWithNoInjectionRecorded() {
		prescribe(encounter(7, 10), q28, 10, null);
		
		refresh.refreshAll(TODAY);
		
		// ACT 2.0 stopped before calculating, so no adherence; the first injection is due 28 days after the start.
		List<Object> row = row(7);
		assertEquals((int) q28.getConceptId(), number(row.get(1)));
		assertNull(row.get(3));
		assertEquals(TODAY.plusDays(18), date(row.get(5)));
	}
	
	@Test
	public void refreshAll_shouldStoreTheFirstDueDateTheChartShowsOfAnInjectionRegimenNeverInjected() {
		prescribe(encounter(7, 40), q28, 40, null);
		
		refresh.refreshAll(TODAY);
		
		// Overdue since 12 days ago, so the due list, which reads next_due, lists the patient.
		List<Object> row = row(7);
		assertNull(row.get(3));
		assertEquals(TODAY.minusDays(12), date(row.get(5)));
		assertEquals(date(row.get(5)), ProphylaxisSummary.of(Context.getPatientService().getPatient(7), TODAY).getNextDue());
	}
	
	@Test
	public void refreshAll_shouldGiveNoAdherenceToAnOralRegimenWithNoEstimateRecorded() {
		// Switched from BPG to oral on one consultation: the BPG window would otherwise count its late days.
		Encounter consultation = encounter(7, 20);
		prescribe(consultation, q28, 60, null);
		prescribe(consultation, oralPenicillin, 20, null);
		
		refresh.refreshAll(TODAY);
		
		assertEquals(0, number(row(7).get(2)));
		assertNull(row(7).get(3));
		assertNull(row(7).get(5));
	}
	
	@Test
	public void refreshAll_shouldHoldABatchEnteredPatientAtTheirLastBatchUntilTheNextIsDue() {
		// Every injection on time until the batch was entered at a consultation 84 days ago.
		Encounter consultation = encounter(7, 200);
		prescribe(consultation, q28, 200, null);
		for (int daysAgo = 200; daysAgo >= 88; daysAgo -= 28) {
			given(encounter(7, daysAgo), daysAgo);
		}
		consulted(encounter(7, 84), 84, everyThreeMonths);
		
		refresh.refreshAll(TODAY);
		
		// As of today, 56 of 200 days late; ACT 2.0 kept the batch's 100% until 90 days had passed.
		assertEquals(1.0, ((Number) row(7).get(3)).doubleValue(), 1e-12);
	}
	
	@Test
	public void refreshAll_shouldRecomputeABatchEnteredPatientOnceTheNextBatchIsDue() {
		// Injected on time until 116 days ago, last seen at a consultation 95 days ago: the batch is due.
		prescribe(encounter(7, 200), q28, 200, null);
		for (int daysAgo = 200; daysAgo >= 116; daysAgo -= 28) {
			given(encounter(7, daysAgo), daysAgo);
		}
		consulted(encounter(7, 95), 95, everyThreeMonths);
		
		refresh.refreshAll(TODAY);
		
		assertEquals(1 - 88.0 / 201, ((Number) row(7).get(3)).doubleValue(), 1e-12);
	}
	
	@Test
	public void refreshAll_shouldComputeAContinuouslyEnteredPatientAsOfToday() {
		prescribe(encounter(7, 200), q28, 200, null);
		for (int daysAgo = 200; daysAgo >= 88; daysAgo -= 28) {
			given(encounter(7, daysAgo), daysAgo);
		}
		consulted(encounter(7, 84), 84, continuous);
		
		refresh.refreshAll(TODAY);
		
		assertEquals(1 - 60.0 / 201, ((Number) row(7).get(3)).doubleValue(), 1e-12);
	}
	
	@Test
	public void refreshAll_shouldNotHoldABatchEnteredPatientAtAConsultationDatedInTheFuture() {
		onTimeUntil(116);
		consulted(encounter(7, 1), -60, everyThreeMonths);
		
		refresh.refreshAll(TODAY);
		
		// The future date is ignored, the last injection 116 days ago is past the batch interval: as of today.
		assertEquals(1 - 88.0 / 201, ((Number) row(7).get(3)).doubleValue(), 1e-12);
	}
	
	@Test
	public void refreshAll_shouldTakeABlankAnswerOnTheLatestConsultationAsContinuousEntry() {
		// Every 12 months at a consultation 400 days ago; the latest, 200 days ago, leaves it blank.
		prescribe(encounter(7, 500), q28, 500, null);
		for (int daysAgo = 500; daysAgo >= 192; daysAgo -= 28) {
			given(encounter(7, daysAgo), daysAgo);
		}
		property(AdherenceRefresh.GP_DATA_ENTRY_INTERVALS, everyThreeMonths.getUuid() + ":365");
		consulted(encounter(7, 400), 400, everyThreeMonths);
		Obs date = obs(encounter(7, 200), consultationDate);
		date.setValueDatetime(at(200));
		Context.getObsService().saveObs(date, null);
		
		refresh.refreshAll(TODAY);
		
		// ACT 2.0 read the latest consultation alone, so the nightly run recomputed them: 164 of 366 days late.
		assertEquals(1 - 164.0 / 366, ((Number) row(7).get(3)).doubleValue(), 1e-12);
	}
	
	@Test
	public void refreshAll_shouldHoldNoRowWhenTheLatestPrescriptionsWereAllStopped() {
		Encounter earlier = encounter(7, 60);
		prescribe(earlier, q28, 60, null);
		given(earlier, 60);
		prescribe(encounter(7, 20), q28, 60, 20);
		
		refresh.refreshAll(TODAY);
		
		assertTrue(rows().isEmpty());
	}
	
	@Test
	public void refreshAll_shouldTakeTheFirstEstimateOfADay() {
		prescribe(encounter(7, 60), oralPenicillin, 60, null);
		estimate(encounter(7, 15), 70.0);
		estimate(encounter(7, 15), 40.0);
		
		refresh.refreshAll(TODAY);
		
		assertEquals(0.7, ((Number) row(7).get(3)).doubleValue(), 1e-12);
	}
	
	@Test
	public void refreshAll_shouldTakeTheLatestDataEntryAnswer() {
		prescribe(encounter(7, 200), q28, 200, null);
		for (int daysAgo = 200; daysAgo >= 88; daysAgo -= 28) {
			given(encounter(7, daysAgo), daysAgo);
		}
		consulted(encounter(7, 150), 150, everyThreeMonths);
		consulted(encounter(7, 84), 84, continuous);
		
		refresh.refreshAll(TODAY);
		
		assertEquals(1 - 60.0 / 201, ((Number) row(7).get(3)).doubleValue(), 1e-12);
	}
	
	@Test
	public void refreshAll_shouldStoreTheRegimenAHeldPatientWasComputedFor() {
		Encounter consultation = encounter(7, 10);
		prescribe(consultation, q28, 200, null);
		prescribe(consultation, q21, 3, null);
		given(encounter(7, 32), 32);
		consulted(consultation, 10, everyThreeMonths);
		
		refresh.refreshAll(TODAY);
		
		// Held as of 10 days ago, before the Q21 started: the Q28 row, due 28 days after the last injection.
		List<Object> row = row(7);
		assertEquals((int) q28.getConceptId(), number(row.get(1)));
		assertEquals(28, number(row.get(2)));
		assertEquals(TODAY.minusDays(4), date(row.get(5)));
	}
	
	@Test
	public void refreshAll_shouldApplyTheNoRecordRuleToTheRegimenAHeldRowDescribes() {
		// Held as of the consultation 10 days ago, when the oral regimen, with no estimate, was the latest.
		Encounter consultation = encounter(7, 10);
		prescribe(consultation, q28, 200, null);
		prescribe(consultation, oralPenicillin, 60, null);
		prescribe(consultation, q21, 3, null);
		given(encounter(7, 150), 150);
		consulted(consultation, 10, everyThreeMonths);
		
		refresh.refreshAll(TODAY);
		
		List<Object> row = row(7);
		assertEquals((int) oralPenicillin.getConceptId(), number(row.get(1)));
		assertNull(row.get(3));
		assertNull(row.get(5));
	}
	
	@Test
	public void refreshAll_shouldKeepAnOralPatientsEstimateFromTheirLastSavedForm() {
		prescribe(encounter(7, 500), oralPenicillin, 500, null);
		estimate(encounter(7, 400), 70.0);
		
		refresh.refreshAll(TODAY);
		
		// ACT 2.0 recomputed oral patients only on a save, so the estimate a year old still stands.
		assertEquals(0.7, ((Number) row(7).get(3)).doubleValue(), 1e-12);
		assertEquals(TODAY.minusDays(400).plusDays(90), date(row(7).get(5)));
	}
	
	@Test
	public void refreshAll_shouldNotFreezeAPatientSwitchedToOralAfterTheirLastConsultation() {
		// Q28 still open and oral started 3 days ago, after the consultation 10 days ago; entered continuously.
		Encounter consultation = encounter(7, 10);
		prescribe(consultation, q28, 200, null);
		prescribe(consultation, oralPenicillin, 3, null);
		for (int daysAgo = 200; daysAgo >= 32; daysAgo -= 28) {
			given(encounter(7, daysAgo), daysAgo);
		}
		consulted(consultation, 10, continuous);
		
		refresh.refreshAll(TODAY);
		
		List<Object> row = row(7);
		assertEquals((int) oralPenicillin.getConceptId(), number(row.get(1)));
		assertEquals(0, number(row.get(2)));
		assertNull(row.get(3));
	}
	
	@Test
	public void refreshAll_shouldCountAPrescriptionAndInjectionOnTheDayAHeldPatientIsComputedAs() {
		Encounter consultation = encounter(7, 10);
		prescribe(consultation, q28, 200, null);
		prescribe(consultation, q21, 10, null);
		given(consultation, 10);
		consulted(consultation, 10, everyThreeMonths);
		
		refresh.refreshAll(TODAY);
		
		List<Object> row = row(7);
		assertEquals((int) q21.getConceptId(), number(row.get(1)));
		assertEquals(TODAY.minusDays(10), date(row.get(4)));
	}
	
	@Test
	public void refreshAll_shouldDatePrescriptionsByTheirConsultationNotWhenTheyWereEntered() {
		// A paper consultation from 120 days ago, entered 5 days ago, after the one from 50 days ago.
		Encounter recent = encounter(7, 50);
		prescribe(recent, q28, 50, null);
		given(recent, 50);
		given(encounter(7, 22), 22);
		dated(recent, 50);
		Encounter backlog = encounter(7, 5);
		prescribe(backlog, oralPenicillin, 300, null);
		dated(backlog, 120);
		
		refresh.refreshAll(TODAY);
		
		// ACT 2.0 took the consultation dated latest.
		List<Object> row = row(7);
		assertEquals((int) q28.getConceptId(), number(row.get(1)));
		assertEquals(1.0, ((Number) row.get(3)).doubleValue(), 1e-12);
		assertEquals(TODAY.minusDays(22), date(row.get(4)));
		assertEquals(TODAY.plusDays(6), date(row.get(5)));
	}
	
	@Test
	public void refreshAll_shouldTakeTheFirstOfTwoConsultationsOnADay() {
		Encounter first = encounter(7, 30);
		prescribe(first, q28, 30, null);
		given(first, 30);
		consulted(first, 30, everyThreeMonths);
		Encounter second = encounter(7, 30);
		prescribe(second, oralPenicillin, 30, null);
		consulted(second, 30, continuous);
		
		refresh.refreshAll(TODAY);
		
		// ACT 2.0 kept the first loaded of a day for both the regimen and the batch hold: held at day 30.
		List<Object> row = row(7);
		assertEquals((int) q28.getConceptId(), number(row.get(1)));
		assertEquals(TODAY.minusDays(2), date(row.get(5)));
		assertEquals(1.0, ((Number) row.get(3)).doubleValue(), 1e-12);
	}
	
	@Test
	public void refreshAll_shouldCountAnOralFormSavedWithoutAnEstimate() {
		prescribe(encounter(7, 60), oralPenicillin, 60, null);
		estimate(encounter(7, 40), 70.0);
		Obs lasts = obs(encounter(7, 15), duration);
		lasts.setValueCoded(threeMonths);
		Context.getObsService().saveObs(lasts, null);
		
		refresh.refreshAll(TODAY);
		
		// The estimate still stands, but the last oral form is the one without it, as ACT 2.0 stored it.
		List<Object> row = row(7);
		assertEquals(0.7, ((Number) row.get(3)).doubleValue(), 1e-12);
		assertEquals(TODAY.minusDays(15), date(row.get(4)));
	}
	
	@Test
	public void refreshAll_shouldKeepTheFirstPrescriptionOfAStartDate() {
		Encounter consultation = encounter(7, 27);
		prescribe(consultation, q21, 27, null);
		prescribe(consultation, oralPenicillin, 27, null);
		
		refresh.refreshAll(TODAY);
		
		assertEquals((int) q21.getConceptId(), number(row(7).get(1)));
		assertEquals(21, number(row(7).get(2)));
	}
	
	@Test
	public void refreshAll_shouldWriteEveryRowWhenTheyTakeMoreThanOneInsert() {
		prescribe(encounter(7, 27), q21, 27, null);
		prescribe(encounter(2, 27), q28, 27, null);
		
		assertEquals(2, new AdherenceRefresh(1).refreshAll(TODAY));
		
		assertEquals(2, rows().size());
	}
}
