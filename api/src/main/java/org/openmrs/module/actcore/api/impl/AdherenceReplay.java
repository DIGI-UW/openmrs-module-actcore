/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.api.impl;

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
 * Replays, from a patient's records, the adherence row that ACT 2.0 would have stored for them on a
 * given day. ACT 2.0 recomputed the row whenever a form was saved, and each night as well, but only
 * for a patient whose stored regimen was an injection and whom batch entry was not holding back.
 */
final class AdherenceReplay {
	
	private AdherenceReplay() {
	}
	
	/**
	 * The prescriptions that one consultation records. For each start date it holds the regimen's
	 * injection interval in days, which is 0 for an oral regimen, and the regimen's concept id.
	 */
	static final class Prescriptions {
		
		final TreeMap<LocalDate, Integer> intervals = new TreeMap<LocalDate, Integer>();
		
		final Map<LocalDate, Integer> regimens = new HashMap<LocalDate, Integer>();
		
		/**
		 * Every prescription the consultation records, including the stopped ones, by start date. For a
		 * prescription that is not stopped, the course's interval is the one in {@code intervals}.
		 */
		final TreeMap<LocalDate, Course> courses = new TreeMap<LocalDate, Course>();
	}
	
	/**
	 * One prescribed course: its injection interval in days, which is 0 for an oral regimen, and the
	 * date it was stopped, or null when it was not stopped.
	 */
	static final class Course {
		
		final int interval;
		
		final LocalDate stopped;
		
		Course(int interval, LocalDate stopped) {
			this.interval = interval;
			this.stopped = stopped;
		}
	}
	
	/**
	 * A patient's records that the replay reads. Each record is taken to have been saved on the date it
	 * records rather than on the date it was entered.
	 */
	static final class History {
		
		/**
		 * The prescriptions of each consultation, keyed by the consultation's date, or by its encounter's
		 * date when it records none. When two consultations share a date, the first one is kept.
		 */
		final TreeMap<LocalDate, Prescriptions> prescriptions = new TreeMap<LocalDate, Prescriptions>();
		
		final TreeSet<LocalDate> injections = new TreeSet<LocalDate>();
		
		final TreeMap<LocalDate, AdherenceCalculation.OralEntry> oral = new TreeMap<LocalDate, AdherenceCalculation.OralEntry>();
		
		final TreeSet<LocalDate> consultations = new TreeSet<LocalDate>();
		
		/**
		 * The days between batches that each consultation records for how injections are entered, keyed by
		 * the consultation's date. It is 0 for continuous entry or when the question is not answered.
		 */
		final TreeMap<LocalDate, Integer> batchDays = new TreeMap<LocalDate, Integer>();
	}
	
	/**
	 * The adherence row that ACT 2.0 would store for a patient on a day: the regimen in force, its
	 * injection interval, the last injection or oral entry, the adherence and the next due date.
	 */
	static final class Row {
		
		final Integer regimen;
		
		final Integer interval;
		
		final LocalDate lastGiven;
		
		final Double adherence;
		
		/**
		 * The next due date as the calculation gives it. It is kept even when no injection is recorded, in
		 * which case it is the current interval counted from the start of the unbroken run of injection
		 * prescriptions, so a change of injection regimen does not move a dose already due. It is kept too
		 * when no oral entry is recorded at all, in which case it is the first oral visit's day if one is
		 * set, or else null.
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
	 * The regimen that a computation on a given day would store: the prescription started last by that
	 * day, with the latest injection or oral entry recorded for it.
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
	 * Replays the row ACT 2.0 would hold for the patient today, without a first oral visit day.
	 *
	 * @param h the patient's records
	 * @param today the day to replay the row for
	 * @return the row, or null when the patient has no prescription to hold one for
	 */
	static Row replay(History h, LocalDate today) {
		return replay(h, today, null);
	}
	
	/**
	 * Replays the row ACT 2.0 would hold for the patient today, giving an oral regimen with no oral
	 * entry recorded the first oral visit's day as its due date when one is set.
	 *
	 * @param h the patient's records
	 * @param today the day to replay the row for
	 * @param oralFirstDueDays days after an oral regimen starts that its first oral visit is due while
	 *            none is recorded; null gives it no first-visit due date, as ACT 2.0 did, and it then
	 *            takes its due date from the latest estimate recorded under an earlier oral course in
	 *            the past year, or has none
	 * @return the row, or null when the patient has no prescription to hold one for
	 */
	static Row replay(History h, LocalDate today, Integer oralFirstDueDays) {
		TreeSet<LocalDate> day = new TreeSet<LocalDate>();
		day.add(today);
		return replayOn(h, day, oralFirstDueDays).get(today);
	}
	
	/**
	 * Replays the row ACT 2.0 would have held on each of the days given, walking the history once up to
	 * the last of them. The map holds every day given, with a null row for a day on which ACT 2.0 held
	 * none.
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
		
		// A save day's state needs nothing earlier, so the walk starts at the last save at or before next.
		LocalDate computed = null;
		Stored stored = null;
		for (LocalDate day = saves.floor(next); next != null && !day.isAfter(last); day = day.plusDays(1)) {
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
	 * Tells whether ACT 2.0's nightly run would have skipped the patient on the day. It skipped a
	 * batch-entered patient until the batch interval had passed since the later of their latest
	 * consultation and the last injection it had stored.
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
