/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.model;

import java.time.LocalDate;
import java.util.List;

/**
 * A patient's prophylaxis as the chart shows it, computed from the patient's saved forms at the
 * time it is asked for. It holds the regimen in force, the latest day recorded for it, when the
 * next dose is due, how many recent injections were on time, the adherence, the injections
 * recorded, and whether BPG is on hold.
 */
public final class ProphylaxisSummary {
	
	/** How many months back {@link #getOnTime()} counts the injections given on time. */
	public static final int ON_TIME_MONTHS = 6;
	
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
	
	/**
	 * Creates a summary.
	 *
	 * @param regimen the name of the regimen in force, or null
	 * @param type the regimen's type, BPG or Oral, or null
	 * @param intervalDays the days between doses, or null
	 * @param lastGiven the latest day recorded for the regimen, or null
	 * @param nextDue when the next dose is due, or null
	 * @param status the status of the next dose
	 * @param onTime how many recent injections were on time, or null
	 * @param adherence the adherence as a fraction, or null
	 * @param injections every injection recorded, newest first
	 * @param bpgOnHold whether BPG is on hold
	 */
	public ProphylaxisSummary(String regimen, String type, Integer intervalDays, LocalDate lastGiven, LocalDate nextDue,
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
	
	/**
	 * Gets the latest day recorded for the regimen in force, as of the day the row was last computed
	 * (see {@link #getAdherence()}). For an injection regimen it is the day of the latest injection,
	 * under whatever regimen it was given. For an oral regimen it is the day of the latest oral
	 * adherence entry, which records an estimate rather than a dose. It is null without a regimen in
	 * force, and while nothing of the regimen's kind is recorded.
	 */
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
	 * Gets the adherence as a fraction, as ACT 2.0's adherence calculation computes it over the 365
	 * days up to the day the row was last computed. That is the latest day on which ACT 2.0 would have
	 * recomputed the row: a day with a prescription, injection, oral adherence entry or consultation
	 * recorded for the patient, or, while the patient is on an injection regimen, a night on which
	 * batch entry does not hold the patient back. It can therefore be earlier than today.
	 * <p>
	 * It is null without a prescription, and, for an injection regimen, until any injection has been
	 * recorded, or, for an oral regimen, until any oral adherence entry has been recorded, under
	 * whatever regimen. It can also be null when the 365 days hold nothing that gives a value.
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
	
	/**
	 * One injection recorded for the patient. It gives the day the injection was given and whether it
	 * was given on time.
	 */
	public static final class Injection {
		
		private final LocalDate date;
		
		private final Boolean onTime;
		
		/**
		 * Creates an injection.
		 *
		 * @param date the day it was given
		 * @param onTime whether it was given on time, or null when it could not be timed
		 */
		public Injection(LocalDate date, Boolean onTime) {
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
		
		/**
		 * Creates a count.
		 *
		 * @param given how many injections were on time
		 * @param total how many injections could be timed
		 */
		public OnTime(int given, int total) {
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
