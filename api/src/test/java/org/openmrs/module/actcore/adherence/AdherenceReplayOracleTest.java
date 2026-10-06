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
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Random;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;

import org.junit.Test;

/**
 * Holds the replay to ACT 2.0's record-keeping written out as ACT 2.0 ran it: a stored record each
 * save overwrites, and a nightly run its stored regimen gates.
 */
public class AdherenceReplayOracleTest {
	
	private static final LocalDate TODAY = LocalDate.of(2026, 9, 29);
	
	/** The stored fields of ACT 2.0's patient record that adherence touches. */
	private static final class Act2Patient {
		
		Integer regimen;
		
		Integer interval;
		
		Double adherence;
		
		LocalDate due;
		
		LocalDate last;
	}
	
	/** update_patient_adherence_and_injection_date on a save or nightly run on the given day. */
	private static void update(AdherenceReplay.History h, Act2Patient patient, LocalDate day) {
		Map.Entry<LocalDate, AdherenceReplay.Prescriptions> entry = h.prescriptions.floorEntry(day);
		if (entry == null) {
			return;
		}
		AdherenceReplay.Prescriptions p = entry.getValue();
		SortedMap<LocalDate, Integer> started = p.intervals.headMap(day.plusDays(1));
		Integer interval = started.isEmpty() ? null : started.get(started.lastKey());
		Integer regimen = started.isEmpty() ? null : p.regimens.get(started.lastKey());
		SortedSet<LocalDate> injected = h.injections.headSet(day.plusDays(1));
		SortedMap<LocalDate, AdherenceCalculation.OralEntry> estimated = h.oral.headMap(day.plusDays(1));
		patient.regimen = regimen;
		patient.interval = interval;
		if (interval != null && (interval > 0 ? injected.isEmpty() : estimated.isEmpty())) {
			// ACT 2.0 left both as they were; the port stores no adherence and the first due date.
			patient.last = null;
			patient.adherence = null;
			patient.due = interval > 0 ? started.lastKey().plusDays(interval) : null;
			return;
		}
		AdherenceCalculation.Result result = AdherenceCalculation.calculate(p.intervals, h.injections, h.oral, day);
		patient.adherence = result.getAdherence();
		patient.due = result.getNextDue();
		if (interval == null) {
			patient.last = null;
		} else {
			patient.last = interval > 0 ? injected.last() : estimated.lastKey();
		}
	}
	
	private static Act2Patient act2(AdherenceReplay.History h) {
		Act2Patient patient = new Act2Patient();
		LocalDate first = null;
		for (LocalDate d : new LocalDate[] { h.prescriptions.isEmpty() ? null : h.prescriptions.firstKey(),
		        h.injections.isEmpty() ? null : h.injections.first(), h.oral.isEmpty() ? null : h.oral.firstKey(),
		        h.consultations.isEmpty() ? null : h.consultations.first() }) {
			if (d != null && (first == null || d.isBefore(first))) {
				first = d;
			}
		}
		for (LocalDate day = first; !day.isAfter(TODAY); day = day.plusDays(1)) {
			boolean saved = h.prescriptions.containsKey(day) || h.injections.contains(day) || h.oral.containsKey(day)
			        || h.consultations.contains(day);
			if (saved) {
				update(h, patient, day);
				continue;
			}
			if (patient.interval == null || patient.interval <= 0) {
				continue;
			}
			LocalDate visit = h.consultations.floor(day);
			if (visit == null || patient.last != null && visit.isBefore(patient.last)) {
				visit = patient.last;
			}
			Map.Entry<LocalDate, Integer> method = h.batchDays.floorEntry(day);
			int batch = method == null ? 0 : method.getValue();
			if (visit != null && batch > 0 && ChronoUnit.DAYS.between(visit, day) < batch) {
				continue;
			}
			update(h, patient, day);
		}
		return patient;
	}
	
	private static AdherenceReplay.History random(Random rng) {
		AdherenceReplay.History h = new AdherenceReplay.History();
		int[] intervals = { 0, 14, 21, 28 };
		for (int e = rng.nextInt(3) + 1; e > 0; e--) {
			// A few encounters are dated after today, and some prescribe nothing (all stopped or undated).
			int on = rng.nextInt(520) - 20;
			AdherenceReplay.Prescriptions p = new AdherenceReplay.Prescriptions();
			for (int n = rng.nextInt(4) - 1; n >= 0; n--) {
				// Starts before and after the consultation that records them.
				LocalDate start = TODAY.minusDays(on + rng.nextInt(400) - 60);
				int interval = intervals[rng.nextInt(intervals.length)];
				if (!p.intervals.containsKey(start)) {
					p.intervals.put(start, interval);
					p.regimens.put(start, interval);
				}
			}
			h.prescriptions.put(TODAY.minusDays(on), p);
			if (rng.nextBoolean()) {
				consulted(rng, h, TODAY.minusDays(on));
			}
		}
		for (int n = rng.nextInt(12); n > 0; n--) {
			h.injections.add(TODAY.minusDays(rng.nextInt(620) - 20));
		}
		for (int n = rng.nextInt(3); n > 0; n--) {
			h.oral.put(TODAY.minusDays(rng.nextInt(620) - 20),
			    new AdherenceCalculation.OralEntry(rng.nextBoolean() ? null : (double) rng.nextInt(101), 90));
		}
		for (int n = rng.nextInt(3); n > 0; n--) {
			consulted(rng, h, TODAY.minusDays(rng.nextInt(520) - 20));
		}
		return h;
	}
	
	/** A consultation, which records how injections are entered or leaves it blank (continuous). */
	private static void consulted(Random rng, AdherenceReplay.History h, LocalDate day) {
		if (h.consultations.add(day)) {
			h.batchDays.put(day, new int[] { 0, 0, 90, 180, 365 }[rng.nextInt(5)]);
		}
	}
	
	@Test
	public void replay_shouldStoreWhatACT2WouldForRandomHistories() {
		Random rng = new Random(26);
		for (int i = 0; i < 3000; i++) {
			AdherenceReplay.History h = random(rng);
			Act2Patient expected = act2(h);
			AdherenceReplay.Row row = AdherenceReplay.replay(h, TODAY);
			String label = "history " + i;
			if (row == null) {
				// No row: the prescriptions ACT 2.0 last read had all been stopped, so it stored no regimen.
				assertEquals(label, null, expected.regimen);
				assertEquals(label, null, expected.adherence);
				continue;
			}
			assertEquals(label, expected.regimen, row.regimen);
			assertEquals(label, expected.interval, row.interval);
			assertEquals(label, expected.adherence, row.adherence);
			assertEquals(label, expected.due, row.nextDue);
			assertEquals(label, expected.last, row.lastGiven);
		}
	}
}
