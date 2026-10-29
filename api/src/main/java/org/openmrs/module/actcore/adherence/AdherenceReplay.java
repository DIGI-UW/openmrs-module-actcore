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

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Replays how ACT 2.0 kept a patient's adherence: recomputed whenever a form was saved, and each
 * night only for a patient whose stored regimen was an injection, unless batch entry held them.
 */
final class AdherenceReplay {
	
	private AdherenceReplay() {
	}
	
	/**
	 * The prescriptions one encounter records: each start date's injection interval (0 if oral) and
	 * regimen.
	 */
	static final class Prescriptions {
		
		final TreeMap<LocalDate, Integer> intervals = new TreeMap<LocalDate, Integer>();
		
		final Map<LocalDate, Integer> regimens = new HashMap<LocalDate, Integer>();
		
		/** Every prescription it records, stopped ones too, agreeing with intervals on the rest. */
		final TreeMap<LocalDate, Course> courses = new TreeMap<LocalDate, Course>();
	}
	
	/** A prescription's injection interval (0 if oral) and the date it was stopped, if it was. */
	static final class Course {
		
		final int interval;
		
		final LocalDate stopped;
		
		Course(int interval, LocalDate stopped) {
			this.interval = interval;
			this.stopped = stopped;
		}
	}
	
	/** A patient's records, each taken to be saved on the date it records. */
	static final class History {
		
		/** By consultation date, else encounter date; the first consultation of a day wins. */
		final TreeMap<LocalDate, Prescriptions> prescriptions = new TreeMap<LocalDate, Prescriptions>();
		
		final TreeSet<LocalDate> injections = new TreeSet<LocalDate>();
		
		final TreeMap<LocalDate, AdherenceCalculation.OralEntry> oral = new TreeMap<LocalDate, AdherenceCalculation.OralEntry>();
		
		final TreeSet<LocalDate> consultations = new TreeSet<LocalDate>();
		
		/** Each consultation's days between batches, 0 for continuous entry or no answer. */
		final TreeMap<LocalDate, Integer> batchDays = new TreeMap<LocalDate, Integer>();
	}
	
	/** What ACT 2.0 would store for the patient today. */
	static final class Row {
		
		final Integer regimen;
		
		final Integer interval;
		
		final LocalDate lastGiven;
		
		final Double adherence;
		
		/**
		 * The calculation's, kept with no injection recorded: one interval after the regimen starts; and
		 * with no oral entry recorded, the first oral visit's day when one is set.
		 */
		final LocalDate nextDue;
		
		Row(Integer regimen, Integer interval, LocalDate lastGiven, Double adherence, LocalDate nextDue) {
			this.regimen = regimen;
			this.interval = interval;
			this.lastGiven = lastGiven;
			this.adherence = adherence;
			this.nextDue = nextDue;
		}
	}
	
	/**
	 * The regimen stored by a computation on a day: the one started last, and the latest record for it.
	 */
	private static final class Stored {
		
		final Prescriptions prescriptions;
		
		final LocalDate started;
		
		final Integer interval;
		
		final LocalDate lastGiven;
		
		Stored(History h, LocalDate day) {
			Map.Entry<LocalDate, Prescriptions> latest = h.prescriptions.floorEntry(day);
			prescriptions = latest == null ? null : latest.getValue();
			SortedMap<LocalDate, Integer> begun = prescriptions == null ? new TreeMap<LocalDate, Integer>()
			        : prescriptions.intervals.headMap(day.plusDays(1));
			started = begun.isEmpty() ? null : begun.lastKey();
			interval = started == null ? null : begun.get(started);
			if (interval != null && interval > 0) {
				SortedSet<LocalDate> given = h.injections.headSet(day.plusDays(1));
				lastGiven = given.isEmpty() ? null : given.last();
			} else if (interval != null) {
				SortedMap<LocalDate, AdherenceCalculation.OralEntry> estimated = h.oral.headMap(day.plusDays(1));
				lastGiven = estimated.isEmpty() ? null : estimated.lastKey();
			} else {
				lastGiven = null;
			}
		}
	}
	
	/**
	 * The row ACT 2.0 would hold today, or null when the patient has no prescription to hold one for.
	 */
	static Row replay(History h, LocalDate today) {
		return replay(h, today, null);
	}
	
	/**
	 * @param oralFirstDueDays days after an oral regimen starts that its first oral visit is due while
	 *            none is recorded; null gives it no due date, as ACT 2.0 did
	 * @see #replay(History, LocalDate)
	 */
	static Row replay(History h, LocalDate today, Integer oralFirstDueDays) {
		TreeSet<LocalDate> day = new TreeSet<LocalDate>();
		day.add(today);
		return replayOn(h, day, oralFirstDueDays).get(today);
	}
	
	/**
	 * The row ACT 2.0 would have held on each of the days, from one walk of the history to the last of
	 * them; null for a day it held none.
	 */
	static Map<LocalDate, Row> replayOn(History h, SortedSet<LocalDate> days, Integer oralFirstDueDays) {
		Map<LocalDate, Row> rows = new TreeMap<LocalDate, Row>();
		if (days.isEmpty()) {
			return rows;
		}
		LocalDate last = days.last();
		TreeSet<LocalDate> saves = new TreeSet<LocalDate>();
		saves.addAll(h.prescriptions.keySet());
		saves.addAll(h.injections);
		saves.addAll(h.oral.keySet());
		saves.addAll(h.consultations);
		saves = new TreeSet<LocalDate>(saves.headSet(last.plusDays(1)));
		Iterator<LocalDate> wanted = days.iterator();
		LocalDate next = wanted.next();
		if (saves.isEmpty()) {
			for (LocalDate day : days) {
				rows.put(day, null);
			}
			return rows;
		}
		while (next != null && next.isBefore(saves.first())) {
			rows.put(next, null);
			next = wanted.hasNext() ? wanted.next() : null;
		}
		
		LocalDate computed = null;
		Stored stored = null;
		for (LocalDate day = saves.first(); next != null && !day.isAfter(last); day = day.plusDays(1)) {
			boolean nightly = stored != null && stored.interval != null && stored.interval > 0 && !held(h, day, stored);
			if (saves.contains(day) || nightly) {
				computed = day;
				stored = new Stored(h, day);
			}
			if (day.equals(next)) {
				rows.put(day, rowOf(h, stored, computed, oralFirstDueDays));
				next = wanted.hasNext() ? wanted.next() : null;
			}
		}
		return rows;
	}
	
	private static Row rowOf(History h, Stored stored, LocalDate computed, Integer oralFirstDueDays) {
		if (stored.prescriptions == null || stored.prescriptions.intervals.isEmpty()) {
			return null;
		}
		Integer regimen = stored.started == null ? null : stored.prescriptions.regimens.get(stored.started);
		boolean noRecord = stored.interval != null
		        && (stored.interval > 0 ? h.injections.headSet(computed.plusDays(1)).isEmpty()
		                : h.oral.headMap(computed.plusDays(1)).isEmpty());
		AdherenceCalculation.Result result = AdherenceCalculation.calculate(stored.prescriptions.intervals, h.injections,
		    h.oral, computed, oralFirstDueDays);
		// ACT 2.0 computed no adherence for a regimen with no injection, or no estimate, recorded.
		return new Row(regimen, stored.interval, stored.lastGiven, noRecord ? null : result.getAdherence(),
		        result.getNextDue());
	}
	
	/**
	 * ACT 2.0's nightly run skipped a batch-entered patient until the batch interval had passed since
	 * the later of their latest consultation and the last injection it had stored.
	 */
	private static boolean held(History h, LocalDate day, Stored stored) {
		Map.Entry<LocalDate, Integer> entry = h.batchDays.floorEntry(day);
		int batch = entry == null ? 0 : entry.getValue();
		LocalDate consulted = h.consultations.floor(day);
		LocalDate seen = consulted == null || stored.lastGiven != null && stored.lastGiven.isAfter(consulted)
		        ? stored.lastGiven
		        : consulted;
		return batch > 0 && seen != null && ChronoUnit.DAYS.between(seen, day) < batch;
	}
}
