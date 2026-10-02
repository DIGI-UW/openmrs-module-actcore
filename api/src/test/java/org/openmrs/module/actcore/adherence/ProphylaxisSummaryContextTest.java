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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.Test;
import org.openmrs.Encounter;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;

public class ProphylaxisSummaryContextTest extends AdherenceContextTest {
	
	private ProphylaxisSummary summary(int patientId) {
		Patient patient = Context.getPatientService().getPatient(patientId);
		return ProphylaxisSummary.of(patient, TODAY);
	}
	
	@Test
	public void of_shouldDescribeAnOverdueInjectionRegimen() {
		Encounter consultation = encounter(7, 27);
		prescribe(consultation, q21, 27, null);
		given(consultation, 27);
		
		ProphylaxisSummary s = summary(7);
		
		assertEquals(q21.getName().getName(), s.getRegimen());
		assertEquals("BPG", s.getType());
		assertEquals(Integer.valueOf(21), s.getIntervalDays());
		assertEquals(TODAY.minusDays(27), s.getLastGiven());
		assertEquals(TODAY.minusDays(6), s.getNextDue());
		assertEquals("overdue", s.getStatus());
	}
	
	@Test
	public void of_shouldSayADoseDueTodayIsDueToday() {
		prescribe(encounter(7, 28), q28, 28, null);
		given(encounter(7, 28), 28);
		
		assertEquals(TODAY, summary(7).getNextDue());
		assertEquals("dueToday", summary(7).getStatus());
	}
	
	@Test
	public void of_shouldSayADoseDueWithinSevenDaysIsDueSoon() {
		prescribe(encounter(7, 21), q28, 21, null);
		given(encounter(7, 21), 21);
		prescribe(encounter(8, 20), q28, 20, null);
		given(encounter(8, 20), 20);
		
		assertEquals(TODAY.plusDays(7), summary(7).getNextDue());
		assertEquals("dueSoon", summary(7).getStatus());
		assertEquals(TODAY.plusDays(8), summary(8).getNextDue());
		assertEquals("ok", summary(8).getStatus());
	}
	
	@Test
	public void of_shouldSayNoneWithoutAPrescription() {
		ProphylaxisSummary s = summary(7);
		
		assertEquals("none", s.getStatus());
		assertNull(s.getRegimen());
		assertNull(s.getType());
		assertNull(s.getNextDue());
		assertNull(s.getOnTime());
	}
	
	@Test
	public void of_shouldSayNoneForAPrescriptionThatStartsAfterToday() {
		prescribe(encounter(7, 0), q28, -5, null);
		
		assertEquals("none", summary(7).getStatus());
		assertNull(summary(7).getType());
	}
	
	@Test
	public void of_shouldDueAFirstDoseOneIntervalAfterThePrescriptionStarts() {
		prescribe(encounter(7, 10), q28, 10, null);
		prescribe(encounter(8, 40), q28, 40, null);
		
		assertEquals(TODAY.plusDays(18), summary(7).getNextDue());
		assertEquals("ok", summary(7).getStatus());
		assertNull(summary(7).getLastGiven());
		assertEquals(TODAY.minusDays(12), summary(8).getNextDue());
		assertEquals("overdue", summary(8).getStatus());
	}
	
	@Test
	public void of_shouldDescribeAnOralRegimenByItsSupply() {
		prescribe(encounter(7, 60), oralPenicillin, 60, null);
		estimate(encounter(7, 15), 70.0);
		
		ProphylaxisSummary s = summary(7);
		
		assertEquals("Oral", s.getType());
		assertEquals(Integer.valueOf(0), s.getIntervalDays());
		assertEquals(TODAY.minusDays(15), s.getLastGiven());
		assertEquals(TODAY.plusDays(75), s.getNextDue());
		assertEquals("ok", s.getStatus());
		assertNull(s.getOnTime());
	}
	
	@Test
	public void of_shouldLeaveTheStatusOfAnOralRegimenWithNoEntryUnknown() {
		prescribe(encounter(7, 60), oralPenicillin, 60, null);
		
		assertEquals("Oral", summary(7).getType());
		assertNull(summary(7).getNextDue());
		assertNull(summary(7).getStatus());
	}
	
	@Test
	public void of_shouldMeasureAFirstInjectionFromThePrescriptionsStart() {
		prescribe(encounter(7, 200), q28, 200, null);
		for (int daysAgo = 120; daysAgo >= 8; daysAgo -= 28) {
			given(encounter(7, daysAgo), daysAgo);
		}
		
		ProphylaxisSummary.OnTime onTime = summary(7).getOnTime();
		
		assertEquals(5, onTime.getTotal());
		assertEquals(4, onTime.getGiven());
		assertEquals(6, onTime.getMonths());
	}
	
	@Test
	public void of_shouldMeasureTheFirstInjectionInTheWindowFromTheOneBeforeIt() {
		// 168 days ago is 22 days after the injection 190 days ago, but 32 after the prescription's start.
		prescribe(encounter(7, 200), q28, 200, null);
		for (int daysAgo : new int[] { 200, 190, 168 }) {
			given(encounter(7, daysAgo), daysAgo);
		}
		
		ProphylaxisSummary.OnTime onTime = summary(7).getOnTime();
		
		assertEquals(1, onTime.getTotal());
		assertEquals(1, onTime.getGiven());
	}
	
	@Test
	public void of_shouldCountTheInjectionsOfTheLastSixMonths() {
		// Six months back from 29 Sep is 29 Mar, 184 days ago.
		prescribe(encounter(7, 300), q28, 300, null);
		given(encounter(7, 185), 185);
		given(encounter(7, 184), 184);
		
		assertEquals(1, summary(7).getOnTime().getTotal());
	}
	
	@Test
	public void of_shouldMeasureEachInjectionAgainstThePrescriptionInForce() {
		// Q21 from 100 days ago, then Q28 from 50: 100 to 75 is 25 days, late against the Q21 in force.
		Encounter consultation = encounter(7, 27);
		prescribe(consultation, q21, 100, null);
		prescribe(consultation, q28, 50, null);
		for (int daysAgo : new int[] { 100, 75, 50, 22 }) {
			given(encounter(7, daysAgo), daysAgo);
		}
		
		ProphylaxisSummary.OnTime onTime = summary(7).getOnTime();
		
		assertEquals(4, onTime.getTotal());
		assertEquals(3, onTime.getGiven());
	}
	
	@Test
	public void of_shouldMeasureInjectionsUnderAStoppedRegimen() {
		// 150 days ago is 50 days after the Q28 starts, so six of the seven are on time, not seven.
		Encounter consultation = encounter(7, 50);
		prescribe(consultation, q28, 200, 50);
		prescribe(consultation, q21, 50, null);
		givenOnSwitch(7);
		
		ProphylaxisSummary.OnTime onTime = summary(7).getOnTime();
		
		assertEquals(7, onTime.getTotal());
		assertEquals(6, onTime.getGiven());
	}
	
	@Test
	public void of_shouldMeasureInjectionsUnderARegimenAnEarlierConsultationPrescribed() {
		prescribe(encounter(7, 200), q28, 200, null);
		prescribe(encounter(7, 50), q21, 50, null);
		givenOnSwitch(7);
		
		ProphylaxisSummary.OnTime onTime = summary(7).getOnTime();
		
		assertEquals(7, onTime.getTotal());
		assertEquals(6, onTime.getGiven());
	}
	
	@Test
	public void of_shouldLeaveOutAnInjectionGivenWithNoRegimenInForce() {
		Encounter consultation = encounter(7, 50);
		prescribe(consultation, q28, 200, 100);
		prescribe(consultation, q21, 50, null);
		for (int daysAgo : new int[] { 172, 144, 116, 80, 50, 29, 8 }) {
			given(encounter(7, daysAgo), daysAgo);
		}
		
		ProphylaxisSummary.OnTime onTime = summary(7).getOnTime();
		
		assertEquals(6, onTime.getTotal());
		assertEquals(6, onTime.getGiven());
	}
	
	@Test
	public void of_shouldMeasureInjectionsAgainstTheFirstConsultationOfTheirDay() {
		prescribe(encounter(7, 150), q28, 150, null);
		prescribe(encounter(7, 150), q21, 150, null);
		for (int daysAgo : new int[] { 122, 94, 66, 38, 10 }) {
			given(encounter(7, daysAgo), daysAgo);
		}
		
		ProphylaxisSummary s = summary(7);
		
		assertEquals(q28.getName().getName(), s.getRegimen());
		assertEquals(Integer.valueOf(28), s.getIntervalDays());
		assertEquals(5, s.getOnTime().getTotal());
		assertEquals(5, s.getOnTime().getGiven());
	}
	
	@Test
	public void of_shouldEndAnInjectionRegimenALaterConsultationLeavesOut() {
		Encounter consultation = encounter(7, 100);
		prescribe(consultation, q28, 200, null);
		prescribe(consultation, q21, 100, null);
		prescribe(encounter(7, 90), q28, 200, null);
		givenEvery28Days(7);
		
		ProphylaxisSummary s = summary(7);
		
		assertEquals(q28.getName().getName(), s.getRegimen());
		assertEquals(Integer.valueOf(28), s.getIntervalDays());
		assertEquals(7, s.getOnTime().getTotal());
		assertEquals(7, s.getOnTime().getGiven());
	}
	
	@Test
	public void of_shouldEndAnOralRegimenALaterConsultationLeavesOut() {
		Encounter consultation = encounter(7, 100);
		prescribe(consultation, q28, 200, null);
		prescribe(consultation, oralPenicillin, 100, null);
		prescribe(encounter(7, 90), q28, 200, null);
		givenEvery28Days(7);
		
		ProphylaxisSummary.OnTime onTime = summary(7).getOnTime();
		
		assertEquals(7, onTime.getTotal());
		assertEquals(7, onTime.getGiven());
	}
	
	@Test
	public void of_shouldMeasureAnInjectionAfterAnOralGapFromTheOneBeforeIt() {
		prescribe(encounter(7, 200), q28, 200, null);
		prescribe(encounter(7, 100), oralPenicillin, 100, null);
		prescribe(encounter(7, 70), q28, 200, null);
		givenEvery28Days(7);
		
		ProphylaxisSummary.OnTime onTime = summary(7).getOnTime();
		
		assertEquals(6, onTime.getTotal());
		assertEquals(6, onTime.getGiven());
	}
	
	private void givenEvery28Days(int patientId) {
		for (int daysAgo = 172; daysAgo >= 4; daysAgo -= 28) {
			given(encounter(patientId, daysAgo), daysAgo);
		}
	}
	
	private void givenOnSwitch(int patientId) {
		for (int daysAgo : new int[] { 150, 122, 94, 66, 50, 29, 8 }) {
			given(encounter(patientId, daysAgo), daysAgo);
		}
	}
	
	@Test
	public void of_shouldReadTheLatestInjectionRatherThanLastNightsTable() {
		prescribe(encounter(7, 27), q21, 27, null);
		given(encounter(7, 27), 27);
		refresh.refreshAll(TODAY);
		
		given(encounter(7, 0), 0);
		
		assertEquals(TODAY, summary(7).getLastGiven());
		assertEquals(TODAY.plusDays(21), summary(7).getNextDue());
		assertEquals("ok", summary(7).getStatus());
		assertEquals(TODAY.minusDays(6), date(row(7).get(5)));
	}
	
	@Test
	public void of_shouldTimeEachInjectionAgainstTheOneBeforeItNewestFirst() {
		prescribe(encounter(7, 100), q28, 100, null);
		for (int daysAgo : new int[] { 100, 72, 43 }) {
			given(encounter(7, daysAgo), daysAgo);
		}
		
		List<ProphylaxisSummary.Injection> injections = summary(7).getInjections();
		
		assertEquals(Arrays.asList(TODAY.minusDays(43), TODAY.minusDays(72), TODAY.minusDays(100)),
		    injections.stream().map(ProphylaxisSummary.Injection::getDate).collect(Collectors.toList()));
		assertEquals(Arrays.asList(false, true, true),
		    injections.stream().map(ProphylaxisSummary.Injection::getOnTime).collect(Collectors.toList()));
	}
	
	@Test
	public void of_shouldTimeThePastInjectionsOfAPatientNowOnOral() {
		prescribe(encounter(7, 100), q28, 100, null);
		given(encounter(7, 100), 100);
		given(encounter(7, 72), 72);
		prescribe(encounter(7, 30), oralPenicillin, 30, null);
		
		ProphylaxisSummary s = summary(7);
		
		assertEquals("Oral", s.getType());
		assertEquals(Arrays.asList(true, true),
		    s.getInjections().stream().map(ProphylaxisSummary.Injection::getOnTime).collect(Collectors.toList()));
	}
	
	@Test
	public void of_shouldListTheInjectionsOfAPatientWithNoPrescriptionUntimed() {
		given(encounter(7, 20), 20);
		
		List<ProphylaxisSummary.Injection> injections = summary(7).getInjections();
		
		assertEquals(Collections.singletonList(TODAY.minusDays(20)),
		    injections.stream().map(ProphylaxisSummary.Injection::getDate).collect(Collectors.toList()));
		assertNull(injections.get(0).getOnTime());
	}
	
	@Test
	public void of_shouldLeaveAnInjectionWithNoCourseInForceUntimed() {
		given(encounter(7, 50), 50);
		prescribe(encounter(7, 30), q28, 30, null);
		given(encounter(7, 2), 2);
		
		List<ProphylaxisSummary.Injection> injections = summary(7).getInjections();
		
		// 2 days ago is 28 after the course started; 50 days ago no course was in force.
		assertEquals(2, injections.size());
		assertEquals(Boolean.TRUE, injections.get(0).getOnTime());
		assertNull(injections.get(1).getOnTime());
	}
}
