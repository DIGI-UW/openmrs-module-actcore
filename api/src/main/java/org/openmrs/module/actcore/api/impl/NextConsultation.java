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
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.openmrs.Concept;
import org.openmrs.Encounter;
import org.openmrs.Obs;
import org.openmrs.Patient;
import org.openmrs.PatientProgram;
import org.openmrs.Person;
import org.openmrs.Program;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.ActCoreConstants;
import org.openmrs.util.PrivilegeConstants;

/**
 * Works out the date a patient's next cardiology review is due, as the registry report's
 * next_consultation_date and the RHD cardiology follow-up due flag work it out. The date is the
 * latest Next Consultation Time Amount, counted from its encounter's date in the Time Period
 * recorded on the same encounter.
 */
final class NextConsultation {
	
	private static final String DAYS = "1072AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
	
	private static final String WEEKS = "1073AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
	
	private static final String MONTHS = "1074AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
	
	private static final String YEARS = "1734AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
	
	private static final List<String> READS = Arrays.asList(PrivilegeConstants.GET_CONCEPTS, PrivilegeConstants.GET_PROGRAMS,
	    PrivilegeConstants.GET_PATIENT_PROGRAMS);
	
	private NextConsultation() {
	}
	
	/**
	 * Gets the date the patient's next cardiology review is due. The caller must hold Get Observations
	 * for a patient whose review date is read, while the configuration and the patient's enrolment are
	 * read with proxy privileges.
	 *
	 * @param patient the patient
	 * @return the due date. It is null when the amount and period concepts are not configured, when the
	 *         patient is not enrolled in the registry now, when the patient has died, when no review
	 *         date is recorded, and when the recorded period is not days, weeks, months or years.
	 * @throws org.openmrs.api.APIAuthenticationException when the caller lacks Get Observations, the
	 *             amount and period concepts are configured, and the patient is alive and enrolled in
	 *             the registry
	 */
	public static LocalDate of(Patient patient) {
		Concept amount;
		Concept period;
		boolean enrolled;
		proxyReads(true);
		try {
			amount = concept(ActCoreConstants.GP_CONSULTATION_NEXT_AMOUNT_CONCEPT);
			period = concept(ActCoreConstants.GP_CONSULTATION_NEXT_PERIOD_CONCEPT);
			enrolled = onRegistry(patient);
		}
		finally {
			proxyReads(false);
		}
		// The RHD cardiology follow-up due flag leaves out the dead too (p.dead = 0).
		if (amount == null || period == null || !enrolled || Boolean.TRUE.equals(patient.getDead())) {
			return null;
		}
		
		List<Obs> answers = Context.getObsService().getObservations(Collections.<Person> singletonList(patient), null,
		    Arrays.asList(amount, period), null, null, null, null, null, null, null, null, false);
		// Only an amount whose encounter also records a period counts, as the report's join requires.
		Map<Encounter, Concept> periods = new HashMap<Encounter, Concept>();
		for (Obs obs : answers) {
			if (obs.getConcept().equals(period) && obs.getEncounter() != null && obs.getValueCoded() != null) {
				periods.put(obs.getEncounter(), obs.getValueCoded());
			}
		}
		Obs latest = null;
		for (Obs obs : answers) {
			Encounter encounter = obs.getEncounter();
			if (obs.getConcept().equals(amount) && encounter != null && !encounter.getVoided()
			        && periods.containsKey(encounter) && (latest == null || later(obs, latest))) {
				latest = obs;
			}
		}
		if (latest == null || latest.getValueNumeric() == null) {
			return null;
		}
		return after(latest.getEncounter(), Math.round(latest.getValueNumeric()), periods.get(latest.getEncounter()));
	}
	
	private static LocalDate after(Encounter encounter, long amount, Concept period) {
		LocalDate seen = encounter.getEncounterDatetime().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
		String unit = period.getUuid();
		if (DAYS.equals(unit)) {
			return seen.plusDays(amount);
		}
		if (WEEKS.equals(unit)) {
			return seen.plusWeeks(amount);
		}
		if (MONTHS.equals(unit)) {
			return seen.plusMonths(amount);
		}
		if (YEARS.equals(unit)) {
			return seen.plusYears(amount);
		}
		return null;
	}
	
	/**
	 * Tells whether observation a comes after observation b, ordering them by their encounters' dates
	 * and then by which was saved last, as the report orders them.
	 */
	private static boolean later(Obs a, Obs b) {
		int byDate = a.getEncounter().getEncounterDatetime().compareTo(b.getEncounter().getEncounterDatetime());
		return byDate != 0 ? byDate > 0 : a.getObsId() > b.getObsId();
	}
	
	private static boolean onRegistry(Patient patient) {
		String uuid = Context.getAdministrationService().getGlobalProperty(ActCoreConstants.GP_REGISTRY_PROGRAM);
		Program program = uuid == null ? null : Context.getProgramWorkflowService().getProgramByUuid(uuid.trim());
		if (program == null) {
			return false;
		}
		for (PatientProgram enrolment : Context.getProgramWorkflowService().getPatientPrograms(patient, program, null, null,
		    null, null, false)) {
			if (enrolment.getDateCompleted() == null) {
				return true;
			}
		}
		return false;
	}
	
	private static Concept concept(String property) {
		String uuid = Context.getAdministrationService().getGlobalProperty(property);
		return uuid == null ? null : Context.getConceptService().getConceptByUuid(uuid.trim());
	}
	
	private static void proxyReads(boolean add) {
		for (String privilege : READS) {
			if (add) {
				Context.addProxyPrivilege(privilege);
			} else {
				Context.removeProxyPrivilege(privilege);
			}
		}
	}
}
