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

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;

import org.junit.Before;
import org.openmrs.Concept;
import org.openmrs.ConceptName;
import org.openmrs.ConceptNumeric;
import org.openmrs.Encounter;
import org.openmrs.Obs;
import org.openmrs.api.context.Context;
import org.openmrs.test.BaseModuleContextSensitiveTest;

public abstract class AdherenceContextTest extends BaseModuleContextSensitiveTest {
	
	protected static final LocalDate TODAY = LocalDate.of(2026, 9, 29);
	
	protected final AdherenceRefresh refresh = new AdherenceRefresh();
	
	protected Concept prescription;
	
	protected Concept q21;
	
	protected Concept q28;
	
	protected Concept oralPenicillin;
	
	protected Concept started;
	
	protected Concept stopped;
	
	protected Concept injectionDate;
	
	protected Concept estimate;
	
	protected Concept duration;
	
	protected Concept threeMonths;
	
	protected Concept consultationDate;
	
	protected Concept dataEntry;
	
	protected Concept everyThreeMonths;
	
	protected Concept continuous;
	
	@Before
	public void setUp() {
		// The module's liquibase changeset is not run by the test context, so the table is made here; H2
		// commits DDL at once, so it outlives the test and only its rows are cleared.
		Context.getAdministrationService().executeSQL("create table if not exists " + AdherenceRefresh.TABLE
		        + " (patient_id int primary key, regimen_concept_id int, injection_interval_days int, adherence double,"
		        + " last_given date, next_due date, date_computed datetime not null)",
		    false);
		Context.getAdministrationService().executeSQL("delete from " + AdherenceRefresh.TABLE, false);
		prescription = concept("Prophylaxis", "Coded");
		q21 = concept("Q21 day BPG", "N/A");
		q28 = concept("Q28 day BPG", "N/A");
		oralPenicillin = concept("Oral penicillin V", "N/A");
		started = concept("Date started", "Datetime");
		stopped = concept("Date stopped", "Datetime");
		injectionDate = concept("Date of injection", "Datetime");
		estimate = concept("Adherence estimate", "Numeric");
		duration = concept("Prescription duration", "Coded");
		threeMonths = concept("3 months", "N/A");
		property(AdherenceRefresh.GP_PRESCRIPTION, prescription.getUuid());
		property(AdherenceRefresh.GP_INJECTION_INTERVALS, q21.getUuid() + ":21, " + q28.getUuid() + ":28");
		property(AdherenceRefresh.GP_DATE_STARTED, started.getUuid());
		property(AdherenceRefresh.GP_DATE_STOPPED, stopped.getUuid());
		property(AdherenceRefresh.GP_INJECTION_DATE, injectionDate.getUuid());
		property(AdherenceRefresh.GP_ESTIMATE, estimate.getUuid());
		property(AdherenceRefresh.GP_DURATION, duration.getUuid());
		property(AdherenceRefresh.GP_DURATIONS, threeMonths.getUuid() + ":90");
		consultationDate = concept("Date of consultation", "Datetime");
		dataEntry = concept("Prophylaxis data entry", "Coded");
		everyThreeMonths = concept("Every 3 months", "N/A");
		continuous = concept("Continuous", "N/A");
		property(AdherenceRefresh.GP_CONSULTATION_DATE, consultationDate.getUuid());
		property(AdherenceRefresh.GP_DATA_ENTRY, dataEntry.getUuid());
		property(AdherenceRefresh.GP_DATA_ENTRY_INTERVALS, everyThreeMonths.getUuid() + ":90");
	}
	
	protected Concept concept(String name, String datatype) {
		// The obs validator reads a numeric concept's ranges, so it must be a ConceptNumeric.
		Concept concept = "Numeric".equals(datatype) ? new ConceptNumeric() : new Concept();
		concept.addName(new ConceptName(name + " " + System.nanoTime(), Context.getLocale()));
		concept.setDatatype(Context.getConceptService().getConceptDatatypeByName(datatype));
		concept.setConceptClass(Context.getConceptService().getConceptClass(1));
		return Context.getConceptService().saveConcept(concept);
	}
	
	protected void property(String name, String value) {
		Context.getAdministrationService().setGlobalProperty(name, value);
	}
	
	protected Encounter encounter(int patientId, int daysAgo) {
		Encounter encounter = new Encounter();
		encounter.setPatient(Context.getPatientService().getPatient(patientId));
		encounter.setEncounterType(Context.getEncounterService().getEncounterType(1));
		encounter.setLocation(Context.getLocationService().getLocation(1));
		encounter.setEncounterDatetime(at(daysAgo));
		return Context.getEncounterService().saveEncounter(encounter);
	}
	
	protected void prescribe(Encounter encounter, Concept regimen, int startedDaysAgo, Integer stoppedDaysAgo) {
		Obs group = obs(encounter, prescription);
		Obs chosen = obs(encounter, prescription);
		chosen.setValueCoded(regimen);
		group.addGroupMember(chosen);
		Obs start = obs(encounter, started);
		start.setValueDatetime(at(startedDaysAgo));
		group.addGroupMember(start);
		if (stoppedDaysAgo != null) {
			Obs stop = obs(encounter, stopped);
			stop.setValueDatetime(at(stoppedDaysAgo));
			group.addGroupMember(stop);
		}
		Context.getObsService().saveObs(group, null);
	}
	
	protected void given(Encounter encounter, int daysAgo) {
		Obs injection = obs(encounter, injectionDate);
		injection.setValueDatetime(at(daysAgo));
		Context.getObsService().saveObs(injection, null);
	}
	
	protected void estimate(Encounter encounter, double percent) {
		Obs value = obs(encounter, estimate);
		value.setValueNumeric(percent);
		Context.getObsService().saveObs(value, null);
		Obs lasts = obs(encounter, duration);
		lasts.setValueCoded(threeMonths);
		Context.getObsService().saveObs(lasts, null);
	}
	
	/** Q28 prescribed 200 days ago, every injection on time until the one ``lastDaysAgo`` ago. */
	protected void onTimeUntil(int lastDaysAgo) {
		prescribe(encounter(7, 200), q28, 200, null);
		for (int daysAgo = 200; daysAgo >= lastDaysAgo; daysAgo -= 28) {
			given(encounter(7, daysAgo), daysAgo);
		}
	}
	
	protected void dated(Encounter encounter, int daysAgo) {
		Obs date = obs(encounter, consultationDate);
		date.setValueDatetime(at(daysAgo));
		Context.getObsService().saveObs(date, null);
	}
	
	protected void consulted(Encounter encounter, int daysAgo, Concept entry) {
		Obs date = obs(encounter, consultationDate);
		date.setValueDatetime(at(daysAgo));
		Context.getObsService().saveObs(date, null);
		Obs how = obs(encounter, dataEntry);
		how.setValueCoded(entry);
		Context.getObsService().saveObs(how, null);
	}
	
	protected Obs obs(Encounter encounter, Concept concept) {
		Obs obs = new Obs(encounter.getPatient(), concept, encounter.getEncounterDatetime(), encounter.getLocation());
		obs.setEncounter(encounter);
		return obs;
	}
	
	protected List<List<Object>> rows() {
		return Context.getAdministrationService().executeSQL(
		    "select patient_id, regimen_concept_id, injection_interval_days, adherence, last_given, next_due from "
		            + AdherenceRefresh.TABLE + " order by patient_id",
		    true);
	}
	
	protected List<Object> row(int patientId) {
		for (List<Object> row : rows()) {
			if (number(row.get(0)) == patientId) {
				return row;
			}
		}
		throw new AssertionError("no row for patient " + patientId);
	}
	
	protected static Date at(int daysAgo) {
		return Timestamp.valueOf(TODAY.minusDays(daysAgo).atTime(10, 0));
	}
	
	protected static int number(Object value) {
		return ((Number) value).intValue();
	}
	
	protected static LocalDate date(Object value) {
		return new Timestamp(((Date) value).getTime()).toLocalDateTime().toLocalDate();
	}
}
