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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.SortedMap;
import java.util.SortedSet;

/**
 * ACT 2.0's adherence calculation (is4r-rhd-cdk
 * adherence_utils.calculate_adherence_and_injection_date), with injection adherence as days covered
 * over days prescribed: an injection covers its interval's days, the prescription's start covers
 * them as the first injection would, a change of injection prescription carries the last
 * injection's cover on, and a dose due today is not yet late. The next due date is ACT 2.0's but
 * counted from the latest injection however old; AdherenceCalculationParityTest holds both.
 */
public final class AdherenceCalculation {
	
	private AdherenceCalculation() {
	}
	
	/**
	 * An oral adherence entry: the estimate in percent, and the days the prescription it records lasts.
	 */
	public static final class OralEntry {
		
		final Double estimate;
		
		private final Integer durationDays;
		
		public OralEntry(Double estimate, Integer durationDays) {
			this.estimate = estimate;
			this.durationDays = durationDays;
		}
	}
	
	/** Adherence as a fraction, and the next date prophylaxis is due; either may be null. */
	public static final class Result {
		
		private final Double adherence;
		
		private final LocalDate nextDue;
		
		public Result(Double adherence, LocalDate nextDue) {
			this.adherence = adherence;
			this.nextDue = nextDue;
		}
		
		public Double getAdherence() {
			return adherence;
		}
		
		public LocalDate getNextDue() {
			return nextDue;
		}
	}
	
	/**
	 * ACT 2.0's calculation as it was: an oral regimen with no oral adherence entry since it started
	 * gets no due date.
	 *
	 * @param prescriptions each prescription's start date and its regimen's interval in days, 0 for a
	 *            regimen that is not an injection
	 * @param injections the dates injections were given
	 * @param oral the oral adherence entries by date
	 */
	public static Result calculate(SortedMap<LocalDate, Integer> prescriptions, NavigableSet<LocalDate> injections,
	        SortedMap<LocalDate, OralEntry> oral, LocalDate today) {
		return calculate(prescriptions, injections, oral, today, null);
	}
	
	/**
	 * @param prescriptions each prescription's start date and its regimen's interval in days, 0 for a
	 *            regimen that is not an injection
	 * @param injections the dates injections were given
	 * @param oral the oral adherence entries by date
	 * @param oralFirstDueDays days after an oral regimen starts that its first oral visit is due, while
	 *            no oral adherence entry has been recorded since the start; null gives such a regimen
	 *            no due date, as ACT 2.0 did
	 */
	public static Result calculate(SortedMap<LocalDate, Integer> prescriptions, NavigableSet<LocalDate> injections,
	        SortedMap<LocalDate, OralEntry> oral, LocalDate today, Integer oralFirstDueDays) {
		// Future prescriptions and oral entries do not count.
		SortedMap<LocalDate, Integer> started = prescriptions.headMap(today.plusDays(1));
		SortedMap<LocalDate, OralEntry> estimated = oral.headMap(today.plusDays(1));
		if (started.isEmpty()) {
			return new Result(null, null);
		}
		List<LocalDate> dates = new ArrayList<LocalDate>(started.keySet());
		LocalDate yearAgo = today.minusDays(365);
		
		long missedDays = 0;
		long totalDays = 0;
		Double estimate = null;
		LocalDate latestEstimateDate = null;
		
		for (int i = 0; i < dates.size(); i++) {
			boolean last = i == dates.size() - 1;
			LocalDate windowStart = dates.get(i);
			// Only the last prescription started a year or more ago counts, from exactly a year ago.
			if (!windowStart.isAfter(yearAgo)) {
				if (last || dates.get(i + 1).isAfter(yearAgo)) {
					windowStart = yearAgo;
				} else {
					continue;
				}
			}
			LocalDate windowEnd = last ? today.plusDays(1) : dates.get(i + 1);
			LocalDate finalDue = last ? today : windowEnd;
			int intervalDays = started.get(dates.get(i));
			estimate = null;
			
			if (intervalDays == 0) {
				for (Map.Entry<LocalDate, OralEntry> entry : estimated.entrySet()) {
					LocalDate day = entry.getKey();
					if (!day.isBefore(windowStart) && day.isBefore(windowEnd) && entry.getValue().estimate != null) {
						estimate = entry.getValue().estimate / 100;
						latestEstimateDate = day;
					}
				}
				continue;
			}
			
			// The days from the window's start are covered by the injection before it, or by the run's start.
			LocalDate runStart = injectionRunStart(started, dates, i);
			LocalDate before = injections.floor(windowStart);
			LocalDate previous = before != null && !before.isBefore(runStart) ? before : runStart;
			List<LocalDate> given = new ArrayList<LocalDate>(injections.subSet(windowStart, false, windowEnd, false));
			given.add(finalDue);
			for (LocalDate injection : given) {
				LocalDate due = previous.plusDays(intervalDays);
				LocalDate lateFrom = due.isAfter(windowStart) ? due : windowStart;
				if (injection.isAfter(lateFrom)) {
					missedDays += ChronoUnit.DAYS.between(lateFrom, injection);
				}
				previous = injection;
			}
			totalDays += ChronoUnit.DAYS.between(windowStart, windowEnd);
		}
		
		// Unlike ACT 2.0, which takes it from the past year only, the latest injection however old.
		SortedSet<LocalDate> givenByToday = injections.headSet(today.plusDays(1));
		LocalDate latestInjection = givenByToday.isEmpty() ? null : givenByToday.last();
		return new Result(adherence(estimate, missedDays, totalDays),
		        nextDue(started, latestInjection, latestEstimateDate, estimated, oralFirstDueDays));
	}
	
	/**
	 * The start of the injection prescriptions that run unbroken by an oral one up to prescription i,
	 * since an injection given before them does not cover days after it.
	 */
	private static LocalDate injectionRunStart(SortedMap<LocalDate, Integer> started, List<LocalDate> dates, int i) {
		int j = i;
		while (j > 0 && started.get(dates.get(j - 1)) != 0) {
			j--;
		}
		return dates.get(j);
	}
	
	private static Double adherence(Double estimate, long missedDays, long totalDays) {
		if (estimate != null) {
			return estimate;
		}
		return totalDays == 0 ? null : 1 - ((double) missedDays / totalDays);
	}
	
	private static LocalDate nextDue(SortedMap<LocalDate, Integer> started, LocalDate latestInjection,
	        LocalDate latestEstimateDate, SortedMap<LocalDate, OralEntry> estimated, Integer oralFirstDueDays) {
		LocalDate latestPrescription = started.lastKey();
		int intervalDays = started.get(latestPrescription);
		if (intervalDays != 0) {
			LocalDate from = latestInjection != null && latestInjection.isAfter(latestPrescription) ? latestInjection
			        : latestPrescription;
			return from.plusDays(intervalDays);
		}
		if (latestEstimateDate != null) {
			Integer durationDays = estimated.get(latestEstimateDate).durationDays;
			return durationDays == null ? null : latestEstimateDate.plusDays(durationDays);
		}
		// Nothing recorded since the oral regimen started: its first oral visit is due, if a day is set.
		if (oralFirstDueDays != null && estimated.tailMap(latestPrescription).isEmpty()) {
			return latestPrescription.plusDays(oralFirstDueDays);
		}
		return null;
	}
}
