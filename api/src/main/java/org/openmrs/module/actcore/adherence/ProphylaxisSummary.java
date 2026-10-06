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
	
	private final List<Injection> injections;
	
	private ProphylaxisSummary(String regimen, String type, Integer intervalDays, LocalDate lastGiven, LocalDate nextDue,
	    String status, OnTime onTime, List<Injection> injections) {
		this.regimen = regimen;
		this.type = type;
		this.intervalDays = intervalDays;
		this.lastGiven = lastGiven;
		this.nextDue = nextDue;
		this.status = status;
		this.onTime = onTime;
		this.injections = injections;
	}
	
	/**
	 * Replays the patient's saved forms now, as the nightly refresh does, so a dose saved today counts.
	 */
	public static ProphylaxisSummary of(Patient patient, LocalDate today) {
		AdherenceReplay.History history = new AdherenceRefresh().historyOf(patient.getPatientId());
		AdherenceReplay.Row row = AdherenceReplay.replay(history, today);
		List<Injection> injections = timings(history.prescriptions, history.injections, today);
		if (row == null || row.interval == null) {
			return new ProphylaxisSummary(null, null, null, null, null, "none", null, injections);
		}
		boolean injected = row.interval > 0;
		LocalDate nextDue = row.nextDue();
		return new ProphylaxisSummary(name(row.regimen), injected ? "BPG" : "Oral", row.interval, row.lastGiven, nextDue,
		        status(nextDue, today), injected ? onTime(injections, today) : null, injections);
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
	 * Null for an oral regimen whose supply end is unknown, as nothing recorded says when it runs out.
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
	 * Every injection up to today, newest first, timed as AdherenceCalculation does: against the
	 * previous injection, even an untimed one, or its course's start.
	 */
	private static List<Injection> timings(NavigableMap<LocalDate, AdherenceReplay.Prescriptions> prescriptions,
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
	 * The latest course begun and not stopped by the day in the consultation replay holds that day, the
	 * first standing in before it; null, leaving the injection out, if that course is not an injection.
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
	
	/** BPG or Oral, or null without a regimen in force. */
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
	 * overdue, dueToday, dueSoon, ok, none without a regimen in force, or null for an oral one whose
	 * supply end is unknown.
	 */
	public String getStatus() {
		return status;
	}
	
	/** Null for an oral regimen, which records an adherence estimate rather than injections. */
	public OnTime getOnTime() {
		return onTime;
	}
	
	/** Every BPG injection recorded up to today, newest first, whatever the regimen in force now. */
	public List<Injection> getInjections() {
		return injections;
	}
	
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
		
		/** Null when no injection course was in force on the day. */
		public Boolean getOnTime() {
			return onTime;
		}
	}
	
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
