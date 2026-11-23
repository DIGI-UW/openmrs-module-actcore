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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.SortedSet;

import org.openmrs.Concept;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.util.PrivilegeConstants;

/**
 * A patient's prophylaxis as the chart shows it, computed from the patient's saved forms at the
 * time it is asked for. It holds the regimen in force, when the last dose was given and the next is
 * due, how many recent injections were on time, the adherence, the injections recorded, and whether
 * BPG is on hold.
 */
public final class ProphylaxisSummary {
	
	private static final int ON_TIME_MONTHS = 6;
	
	private static final int DUE_SOON_DAYS = 7;
	
	private final String regimen;
	
	private final String type;
	
	private final Integer intervalDays;
	
	private final LocalDate lastGiven;
	
	private final LocalDate nextDue;
	
	private final String status;
	
	private final OnTime onTime;
	
	private final Double adherence;
	
	private final List<Injection> injections;
	
	private final boolean bpgOnHold;
	
	private ProphylaxisSummary(String regimen, String type, Integer intervalDays, LocalDate lastGiven, LocalDate nextDue,
	    String status, OnTime onTime, Double adherence, List<Injection> injections, boolean bpgOnHold) {
		this.regimen = regimen;
		this.type = type;
		this.intervalDays = intervalDays;
		this.lastGiven = lastGiven;
		this.nextDue = nextDue;
		this.status = status;
		this.onTime = onTime;
		this.adherence = adherence;
		this.injections = injections;
		this.bpgOnHold = bpgOnHold;
	}
	
	/**
	 * Computes the patient's prophylaxis summary by replaying their saved forms now, as the nightly
	 * refresh does, so that a dose saved today already counts.
	 *
	 * @param patient the patient
	 * @param today the day to compute the summary as of
	 * @return the summary; without a regimen in force its status is none, it is not on hold, and its
	 *         other values are null except the injections, which are still listed
	 */
	public static ProphylaxisSummary of(Patient patient, LocalDate today) {
		AdherenceReplay.History history = new AdherenceRefresh().historyOf(patient.getPatientId());
		AdherenceReplay.Row row = AdherenceReplay.replay(history, today, AdherenceRefresh.oralFirstDueDays());
		List<Injection> injections = timings(history.prescriptions, history.injections, today);
		if (row == null || row.interval == null) {
			return new ProphylaxisSummary(null, null, null, null, null, "none", null, null, injections, false);
		}
		boolean injected = row.interval > 0;
		return new ProphylaxisSummary(name(row.regimen), injected ? "BPG" : "Oral", row.interval, row.lastGiven, row.nextDue,
		        status(row.nextDue, today), injected ? onTime(injections, today) : null, row.adherence, injections,
		        injected && UnreviewedAnaphylaxis.of(patient));
	}
	
	private static String name(Integer conceptId) {
		if (conceptId == null) {
			return null;
		}
		try {
			Context.addProxyPrivilege(PrivilegeConstants.GET_CONCEPTS);
			Concept concept = Context.getConceptService().getConcept(conceptId);
			return concept == null ? null : concept.getName().getName();
		}
		finally {
			Context.removeProxyPrivilege(PrivilegeConstants.GET_CONCEPTS);
		}
	}
	
	/**
	 * Gives the status of the next dose: overdue, dueToday, dueSoon within seven days, or ok. It is
	 * null when there is no due date, as for an oral regimen whose supply end is unknown because
	 * nothing recorded says when it runs out and no first visit day is set in
	 * actcore.adherence.oralFirstDueDays.
	 */
	private static String status(LocalDate nextDue, LocalDate today) {
		if (nextDue == null) {
			return null;
		}
		long days = ChronoUnit.DAYS.between(today, nextDue);
		if (days < 0) {
			return "overdue";
		}
		if (days == 0) {
			return "dueToday";
		}
		// The registry's "Deadline approaching" threshold.
		return days <= DUE_SOON_DAYS ? "dueSoon" : "ok";
	}
	
	/**
	 * Lists every injection given up to today, newest first, each marked on time or late as ACT 2.0
	 * timed it. An injection is timed against the previous injection, even one that could not itself be
	 * timed, or against the start of its course when it is the course's first. It is untimed when no
	 * injection course was in force on its day.
	 */
	static List<Injection> timings(NavigableMap<LocalDate, AdherenceReplay.Prescriptions> prescriptions,
	        SortedSet<LocalDate> injections, LocalDate today) {
		List<Injection> timed = new ArrayList<Injection>();
		LocalDate start = null;
		LocalDate previous = null;
		for (LocalDate injection : injections.headSet(today.plusDays(1))) {
			Map.Entry<LocalDate, AdherenceReplay.Course> inForce = prescriptions.isEmpty() ? null
			        : inForce(prescriptions, injection);
			Boolean onTime = null;
			// A course re-recorded after a gap keeps its start, so a null between does not reset previous.
			if (inForce != null) {
				if (!inForce.getKey().equals(start)) {
					start = inForce.getKey();
					previous = start;
				}
				onTime = !injection.isAfter(previous.plusDays(inForce.getValue().interval));
			}
			timed.add(new Injection(injection, onTime));
			previous = injection;
		}
		Collections.reverse(timed);
		return timed;
	}
	
	private static OnTime onTime(List<Injection> injections, LocalDate today) {
		LocalDate from = today.minusMonths(ON_TIME_MONTHS);
		int given = 0;
		int total = 0;
		for (Injection injection : injections) {
			if (injection.onTime != null && !injection.date.isBefore(from)) {
				total++;
				if (injection.onTime) {
					given++;
				}
			}
		}
		return new OnTime(given, total);
	}
	
	/**
	 * Finds the injection course in force on the day. It is the latest course begun and not stopped by
	 * that day among those of the consultation in force on it, or of the first consultation when the
	 * day comes before every consultation. It is null, leaving the injection untimed, when that course
	 * is not an injection.
	 */
	private static Map.Entry<LocalDate, AdherenceReplay.Course> inForce(
	        NavigableMap<LocalDate, AdherenceReplay.Prescriptions> prescriptions, LocalDate day) {
		Map.Entry<LocalDate, AdherenceReplay.Prescriptions> consultation = prescriptions.floorEntry(day);
		if (consultation == null) {
			consultation = prescriptions.firstEntry();
		}
		for (Map.Entry<LocalDate, AdherenceReplay.Course> course : consultation.getValue().courses.headMap(day, true)
		        .descendingMap().entrySet()) {
			if (course.getValue().stopped == null || !day.isAfter(course.getValue().stopped)) {
				return course.getValue().interval > 0 ? course : null;
			}
		}
		return null;
	}
	
	public String getRegimen() {
		return regimen;
	}
	
	/** Gets the regimen's type, BPG or Oral, or null without a regimen in force. */
	public String getType() {
		return type;
	}
	
	public Integer getIntervalDays() {
		return intervalDays;
	}
	
	public LocalDate getLastGiven() {
		return lastGiven;
	}
	
	public LocalDate getNextDue() {
		return nextDue;
	}
	
	/**
	 * Gets the status of the next dose: overdue, dueToday, dueSoon when it is due within seven days, or
	 * ok. It is none without a regimen in force, and null for an oral regimen whose supply end is
	 * unknown.
	 */
	public String getStatus() {
		return status;
	}
	
	/**
	 * Gets how many injections in the past six months were on time. It is null for an oral regimen,
	 * which records an adherence estimate rather than injections, and without a regimen in force.
	 */
	public OnTime getOnTime() {
		return onTime;
	}
	
	/**
	 * Gets the adherence as a fraction, as {@link AdherenceCalculation} computes it over the past 365
	 * days for an injection regimen. It is null without a prescription, and before the first injection
	 * or oral estimate under the regimen is recorded.
	 */
	public Double getAdherence() {
		return adherence;
	}
	
	/**
	 * Tells whether BPG is on hold for the patient. It is on hold when the patient is on BPG and a BPG
	 * Delivery form recorded anaphylaxis that no RHD Consultation Visit has followed since, so that no
	 * dose is given until a clinician reviews it.
	 */
	public boolean isBpgOnHold() {
		return bpgOnHold;
	}
	
	/**
	 * Gets every injection recorded up to today, newest first, whatever the regimen in force now. The
	 * list is empty when none is recorded.
	 */
	public List<Injection> getInjections() {
		return injections;
	}
	
	/** One injection given: the day it was given, and whether it was on time. */
	public static final class Injection {
		
		private final LocalDate date;
		
		private final Boolean onTime;
		
		Injection(LocalDate date, Boolean onTime) {
			this.date = date;
			this.onTime = onTime;
		}
		
		public LocalDate getDate() {
			return date;
		}
		
		/**
		 * Tells whether the injection was on time. It is null when no injection course was in force on the
		 * day, so the injection could not be timed.
		 */
		public Boolean getOnTime() {
			return onTime;
		}
	}
	
	/**
	 * How many of the injections given in the past six months were on time, out of those that could be
	 * timed in that period.
	 */
	public static final class OnTime {
		
		private final int given;
		
		private final int total;
		
		OnTime(int given, int total) {
			this.given = given;
			this.total = total;
		}
		
		public int getGiven() {
			return given;
		}
		
		public int getTotal() {
			return total;
		}
		
		public int getMonths() {
			return ON_TIME_MONTHS;
		}
	}
}
