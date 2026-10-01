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
		// Prescribed Q28 200 days ago, first injected 120 days ago, then every 28 days.
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
	public void of_shouldCountOnlyTheLastSixMonthsInjectionsAndMeasureTheFirstFromTheOneBefore() {
		// On time every 28 days from 200 days ago; six months back from 29 Sep is 29 Mar, 184 days ago.
		onTimeUntil(4);
		given(encounter(7, 190), 190);
		
		ProphylaxisSummary.OnTime onTime = summary(7).getOnTime();
		
		assertEquals(7, onTime.getTotal());
		assertEquals(7, onTime.getGiven());
	}
	
	@Test
	public void of_shouldMeasureEachInjectionAgainstThePrescriptionInForce() {
		// Q21 from 100 days ago, then Q28 from 50 days ago: 100 to 75 is 25 days, late against the Q21 in force.
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
}
