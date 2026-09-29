/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.rhdflags.adherence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.openmrs.Concept;
import org.openmrs.ConceptName;
import org.openmrs.ConceptNumeric;
import org.openmrs.Encounter;
import org.openmrs.Obs;
import org.openmrs.api.context.Context;
import org.openmrs.test.BaseModuleContextSensitiveTest;

public class AdherenceRefreshContextTest extends BaseModuleContextSensitiveTest {
	
	private static final LocalDate TODAY = LocalDate.of(2026, 9, 29);
	
	private final AdherenceRefresh refresh = new AdherenceRefresh();
	
	private Concept prescription;
	
	private Concept q21;
	
	private Concept q28;
	
	private Concept oralPenicillin;
	
	private Concept started;
	
	private Concept stopped;
	
	private Concept injectionDate;
	
	private Concept estimate;
	
	private Concept duration;
	
	private Concept threeMonths;
	
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
	}
	
	@Test
	public void refreshAll_shouldStoreTheAdherenceAndDueDateOfAnInjectionRegimen() {
		Encounter consultation = encounter(7, 27);
		prescribe(consultation, q21, 27, null);
		given(consultation, 27);
		
		assertEquals(1, refresh.refreshAll(TODAY));
		
		List<Object> row = row(7);
		assertEquals((int) q21.getConceptId(), number(row.get(1)));
		assertEquals(21, number(row.get(2)));
		// ACT 2.0's case: prescribed and injected 27 days ago, due again 21 days later, so 6 of 28 days late.
		assertEquals(1 - 6.0 / 28, ((Number) row.get(3)).doubleValue(), 1e-12);
		assertEquals(TODAY.minusDays(27), date(row.get(4)));
		assertEquals(TODAY.minusDays(6), date(row.get(5)));
	}
	
	@Test
	public void refreshAll_shouldTakeThePrescriptionsOfTheLatestEncounterThatRecordsAny() {
		prescribe(encounter(7, 200), q28, 200, null);
		prescribe(encounter(7, 27), q21, 27, null);
		
		refresh.refreshAll(TODAY);
		
		assertEquals((int) q21.getConceptId(), number(row(7).get(1)));
		assertEquals(1 - 6.0 / 28, ((Number) row(7).get(3)).doubleValue(), 1e-12);
	}
	
	@Test
	public void refreshAll_shouldLeaveOutAPrescriptionThatWasStopped() {
		Encounter consultation = encounter(7, 27);
		prescribe(consultation, q21, 27, 10);
		
		assertEquals(0, refresh.refreshAll(TODAY));
		
		assertTrue(rows().isEmpty());
	}
	
	@Test
	public void refreshAll_shouldTakeTheLatestEstimateOnAnOralRegimen() {
		prescribe(encounter(7, 60), oralPenicillin, 60, null);
		estimate(encounter(7, 40), 55.0);
		estimate(encounter(7, 15), 70.0);
		
		refresh.refreshAll(TODAY);
		
		List<Object> row = row(7);
		assertEquals(0, number(row.get(2)));
		assertEquals(0.7, ((Number) row.get(3)).doubleValue(), 1e-12);
		assertEquals(TODAY.minusDays(15), date(row.get(4)));
		assertEquals(TODAY.minusDays(15).plusDays(90), date(row.get(5)));
	}
	
	@Test
	public void refreshAll_shouldIgnoreVoidedEncountersAndReplaceEarlierRows() {
		prescribe(encounter(7, 27), q21, 27, null);
		prescribe(encounter(2, 27), q28, 27, null);
		refresh.refreshAll(TODAY);
		assertEquals(2, rows().size());
		
		for (Encounter encounter : Context.getEncounterService().getEncountersByPatientId(2)) {
			Context.getEncounterService().voidEncounter(encounter, "test");
		}
		// The refresh reads with SQL, which sees only what the session has flushed.
		Context.flushSession();
		refresh.refreshAll(TODAY);
		
		assertEquals(1, rows().size());
		assertEquals(7, number(rows().get(0).get(0)));
	}
	
	private Concept concept(String name, String datatype) {
		// The obs validator reads a numeric concept's ranges, so it must be a ConceptNumeric.
		Concept concept = "Numeric".equals(datatype) ? new ConceptNumeric() : new Concept();
		concept.addName(new ConceptName(name + " " + System.nanoTime(), Context.getLocale()));
		concept.setDatatype(Context.getConceptService().getConceptDatatypeByName(datatype));
		concept.setConceptClass(Context.getConceptService().getConceptClass(1));
		return Context.getConceptService().saveConcept(concept);
	}
	
	private void property(String name, String value) {
		Context.getAdministrationService().setGlobalProperty(name, value);
	}
	
	private Encounter encounter(int patientId, int daysAgo) {
		Encounter encounter = new Encounter();
		encounter.setPatient(Context.getPatientService().getPatient(patientId));
		encounter.setEncounterType(Context.getEncounterService().getEncounterType(1));
		encounter.setLocation(Context.getLocationService().getLocation(1));
		encounter.setEncounterDatetime(at(daysAgo));
		return Context.getEncounterService().saveEncounter(encounter);
	}
	
	private void prescribe(Encounter encounter, Concept regimen, int startedDaysAgo, Integer stoppedDaysAgo) {
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
	
	private void given(Encounter encounter, int daysAgo) {
		Obs injection = obs(encounter, injectionDate);
		injection.setValueDatetime(at(daysAgo));
		Context.getObsService().saveObs(injection, null);
	}
	
	private void estimate(Encounter encounter, double percent) {
		Obs value = obs(encounter, estimate);
		value.setValueNumeric(percent);
		Context.getObsService().saveObs(value, null);
		Obs lasts = obs(encounter, duration);
		lasts.setValueCoded(threeMonths);
		Context.getObsService().saveObs(lasts, null);
	}
	
	private Obs obs(Encounter encounter, Concept concept) {
		Obs obs = new Obs(encounter.getPatient(), concept, encounter.getEncounterDatetime(), encounter.getLocation());
		obs.setEncounter(encounter);
		return obs;
	}
	
	private List<List<Object>> rows() {
		return Context.getAdministrationService().executeSQL(
		    "select patient_id, regimen_concept_id, injection_interval_days, adherence, last_given, next_due from "
		            + AdherenceRefresh.TABLE + " order by patient_id",
		    true);
	}
	
	private List<Object> row(int patientId) {
		for (List<Object> row : rows()) {
			if (number(row.get(0)) == patientId) {
				return row;
			}
		}
		throw new AssertionError("no row for patient " + patientId);
	}
	
	private static Date at(int daysAgo) {
		return Timestamp.valueOf(TODAY.minusDays(daysAgo).atTime(10, 0));
	}
	
	private static int number(Object value) {
		return ((Number) value).intValue();
	}
	
	private static LocalDate date(Object value) {
		return new Timestamp(((Date) value).getTime()).toLocalDateTime().toLocalDate();
	}
}
