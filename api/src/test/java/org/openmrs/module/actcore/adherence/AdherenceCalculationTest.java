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

import java.time.LocalDate;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.Test;

/** Injection adherence as days covered over days prescribed, worked by hand. */
public class AdherenceCalculationTest {
	
	private static final LocalDate TODAY = LocalDate.of(2026, 10, 14);
	
	private final TreeMap<LocalDate, Integer> prescriptions = new TreeMap<LocalDate, Integer>();
	
	private final TreeSet<LocalDate> injections = new TreeSet<LocalDate>();
	
	private final TreeMap<LocalDate, AdherenceCalculation.OralEntry> oral = new TreeMap<LocalDate, AdherenceCalculation.OralEntry>();
	
	private AdherenceCalculation.Result calculate(LocalDate today) {
		return AdherenceCalculation.calculate(prescriptions, injections, oral, today);
	}
	
	private static LocalDate date(String iso) {
		return LocalDate.parse(iso);
	}
	
	@Test
	public void calculate_shouldCountDaysAfterTheIntervalBeforeTheNextInjectionAsLate() {
		// Day 0 covers days 0-27; a dose on day 31 leaves days 28, 29 and 30 uncovered.
		prescriptions.put(date("2026-09-13"), 28);
		injections.add(date("2026-09-13"));
		injections.add(date("2026-10-14"));
		
		assertEquals(29.0 / 32, calculate(TODAY).getAdherence(), 1e-12);
	}
	
	@Test
	public void calculate_shouldJudgeTheFirstInjectionFromThePrescriptionsStart() {
		// Due 29 Aug, given 13 Sep: 15 days late. Then due 11 Oct and given on time.
		prescriptions.put(date("2026-08-01"), 28);
		injections.add(date("2026-09-13"));
		injections.add(date("2026-10-11"));
		
		assertEquals(60.0 / 75, calculate(TODAY).getAdherence(), 1e-12);
	}
	
	@Test
	public void calculate_shouldNotCountADoseDueTodayAsLate() {
		prescriptions.put(date("2026-09-16"), 28);
		injections.add(date("2026-09-16"));
		
		assertEquals(1.0, calculate(TODAY).getAdherence(), 1e-12);
	}
	
	@Test
	public void calculate_shouldCountOverdueDaysBeforeToday() {
		// Due 12 Oct: 12 and 13 Oct are late, today is not.
		prescriptions.put(date("2026-09-14"), 28);
		injections.add(date("2026-09-14"));
		
		assertEquals(29.0 / 31, calculate(TODAY).getAdherence(), 1e-12);
	}
	
	@Test
	public void calculate_shouldLetAnInjectionBeforeTheYearCoverTheDaysAfterIt() {
		// The year runs from 10 Nov 2025; the 20 Oct 2025 dose covers to 16 Nov, so 29 Nov is 12 days late.
		LocalDate today = date("2026-11-10");
		prescriptions.put(date("2024-01-01"), 28);
		injections.add(date("2025-10-20"));
		for (int k = 0; k <= 12; k++) {
			injections.add(date("2025-11-29").plusDays(28 * k));
		}
		injections.add(today);
		
		assertEquals(354.0 / 366, calculate(today).getAdherence(), 1e-12);
	}
	
	@Test
	public void calculate_shouldCarryTheLastInjectionOverAChangeOfInjectionPrescription() {
		// 29 Aug under Q28, then Q21 from 1 Sep: due 19 Sep, given 25 Sep, 6 days late.
		prescriptions.put(date("2026-08-01"), 28);
		prescriptions.put(date("2026-09-01"), 21);
		injections.add(date("2026-08-01"));
		injections.add(date("2026-08-29"));
		injections.add(date("2026-09-25"));
		
		assertEquals(69.0 / 75, calculate(TODAY).getAdherence(), 1e-12);
	}
	
	@Test
	public void calculate_shouldCountAfreshFromTheInjectionPrescriptionAfterAnOralOne() {
		// June: due 29 Jun, the oral course from 1 Jul leaves 2 late days. July and August are oral.
		// From 1 Sep the injection is due 29 Sep, so the 20 Sep one leaves 44 days on time.
		prescriptions.put(date("2026-06-01"), 28);
		prescriptions.put(date("2026-07-01"), 0);
		prescriptions.put(date("2026-09-01"), 28);
		injections.add(date("2026-06-01"));
		injections.add(date("2026-09-20"));
		
		assertEquals(72.0 / 74, calculate(TODAY).getAdherence(), 1e-12);
	}
	
	@Test
	public void calculate_shouldCountTheDaysAfterAFirstInjectionNeverGivenWasDueAsLate() {
		// Due 29 Sep: 29 Sep to 13 Oct are late.
		prescriptions.put(date("2026-09-01"), 28);
		
		AdherenceCalculation.Result result = calculate(TODAY);
		
		assertEquals(29.0 / 44, result.getAdherence(), 1e-12);
		assertEquals(date("2026-09-29"), result.getNextDue());
	}
	
	@Test
	public void calculate_shouldGiveAnOralPrescriptionWithNoEntryNoDueDateByDefault() {
		prescriptions.put(date("2026-09-01"), 0);
		
		assertEquals(null, calculate(TODAY).getNextDue());
	}
	
	@Test
	public void calculate_shouldMakeTheFirstOralVisitDueTheSetDaysAfterTheStart() {
		prescriptions.put(date("2026-09-01"), 0);
		
		AdherenceCalculation.Result result = AdherenceCalculation.calculate(prescriptions, injections, oral, TODAY, 7);
		
		assertEquals(null, result.getAdherence());
		assertEquals(date("2026-09-08"), result.getNextDue());
	}
	
	@Test
	public void calculate_shouldLetAnOralEntrySinceTheStartSetTheDueDateInsteadOfTheFirstVisit() {
		prescriptions.put(date("2026-09-01"), 0);
		oral.put(date("2026-09-10"), new AdherenceCalculation.OralEntry(80.0, 30));
		
		AdherenceCalculation.Result result = AdherenceCalculation.calculate(prescriptions, injections, oral, TODAY, 0);
		
		assertEquals(date("2026-10-10"), result.getNextDue());
	}
	
	@Test
	public void calculate_shouldNotLetAnOralEntryBeforeTheStartStandInForTheFirstVisit() {
		oral.put(date("2026-05-01"), new AdherenceCalculation.OralEntry(80.0, 90));
		prescriptions.put(date("2026-09-01"), 0);
		
		AdherenceCalculation.Result result = AdherenceCalculation.calculate(prescriptions, injections, oral, TODAY, 0);
		
		assertEquals(date("2026-09-01"), result.getNextDue());
	}
	
	@Test
	public void calculate_shouldMakeTheFirstVisitOfAReprescribedOralRegimenDueThoughAnEarlierOralCourseHadOne() {
		// Oral course A with a visit, then a new oral prescription on the same consultation with nothing since.
		prescriptions.put(date("2026-06-01"), 0);
		oral.put(date("2026-07-01"), new AdherenceCalculation.OralEntry(80.0, 90));
		prescriptions.put(date("2026-09-01"), 0);
		
		assertEquals(date("2026-10-01"),
		    AdherenceCalculation.calculate(prescriptions, injections, oral, TODAY, 30).getNextDue());
		// Blank keeps ACT 2.0's date, the earlier visit plus its duration.
		assertEquals(date("2026-09-29"), calculate(TODAY).getNextDue());
	}
	
	@Test
	public void calculate_shouldNotApplyTheFirstOralVisitToAnInjectionRegimen() {
		prescriptions.put(date("2026-09-01"), 28);
		
		AdherenceCalculation.Result result = AdherenceCalculation.calculate(prescriptions, injections, oral, TODAY, 0);
		
		assertEquals(date("2026-09-29"), result.getNextDue());
	}
	
	@Test
	public void calculate_shouldTakeAnOralPrescriptionsEstimate() {
		prescriptions.put(date("2026-06-01"), 28);
		injections.add(date("2026-06-01"));
		prescriptions.put(date("2026-09-01"), 0);
		oral.put(date("2026-09-01"), new AdherenceCalculation.OralEntry(80.0, 90));
		
		AdherenceCalculation.Result result = calculate(TODAY);
		
		assertEquals(0.8, result.getAdherence(), 1e-12);
		assertEquals(date("2026-11-30"), result.getNextDue());
	}
}
