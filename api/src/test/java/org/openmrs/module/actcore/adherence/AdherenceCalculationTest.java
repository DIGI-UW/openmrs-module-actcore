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
	public void calculate_shouldStartCountingAtTheFirstInjection() {
		prescriptions.put(date("2026-09-01"), 28);
		injections.add(date("2026-09-13"));
		injections.add(date("2026-10-14"));
		
		assertEquals(29.0 / 32, calculate(TODAY).getAdherence(), 1e-12);
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
	public void calculate_shouldCountAfreshFromTheFirstInjectionAfterAnOralPrescription() {
		// June: due 29 Jun, the oral course from 1 Jul leaves 2 late days. July and August are oral.
		// From the 20 Sep injection, 25 days on time.
		prescriptions.put(date("2026-06-01"), 28);
		prescriptions.put(date("2026-07-01"), 0);
		prescriptions.put(date("2026-09-01"), 28);
		injections.add(date("2026-06-01"));
		injections.add(date("2026-09-20"));
		
		assertEquals(53.0 / 55, calculate(TODAY).getAdherence(), 1e-12);
	}
	
	@Test
	public void calculate_shouldGiveNoAdherenceBeforeTheFirstInjection() {
		prescriptions.put(date("2026-09-01"), 28);
		
		AdherenceCalculation.Result result = calculate(TODAY);
		
		assertNull(result.getAdherence());
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
