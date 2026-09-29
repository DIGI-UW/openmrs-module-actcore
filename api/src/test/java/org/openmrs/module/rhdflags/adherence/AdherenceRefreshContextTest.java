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
import static org.junit.Assert.assertNull;
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
	
	private Concept consultationDate;
	
	private Concept dataEntry;
	
	private Concept everyThreeMonths;
	
	private Concept continuous;
	
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
		Encounter latest = encounter(7, 27);
		prescribe(latest, q21, 27, null);
		given(latest, 27);
		
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
	
	@Test
	public void refreshAll_shouldGiveNoAdherenceToAnInjectionRegimenWithNoInjectionRecorded() {
		prescribe(encounter(7, 10), q28, 10, null);
		
		refresh.refreshAll(TODAY);
		
		// ACT 2.0 stopped before calculating; the calculation alone would say 100% and covered.
		List<Object> row = row(7);
		assertEquals((int) q28.getConceptId(), number(row.get(1)));
		assertNull(row.get(3));
		assertNull(row.get(5));
	}
	
	@Test
	public void refreshAll_shouldGiveNoAdherenceToAnOralRegimenWithNoEstimateRecorded() {
		// Switched from BPG to oral on one consultation: the BPG window would otherwise count its late days.
		Encounter consultation = encounter(7, 20);
		prescribe(consultation, q28, 60, null);
		prescribe(consultation, oralPenicillin, 20, null);
		
		refresh.refreshAll(TODAY);
		
		assertEquals(0, number(row(7).get(2)));
		assertNull(row(7).get(3));
		assertNull(row(7).get(5));
	}
	
	@Test
	public void refreshAll_shouldHoldABatchEnteredPatientAtTheirLastBatchUntilTheNextIsDue() {
		// Every injection on time until the batch was entered at a consultation 84 days ago.
		Encounter consultation = encounter(7, 200);
		prescribe(consultation, q28, 200, null);
		for (int daysAgo = 200; daysAgo >= 88; daysAgo -= 28) {
			given(encounter(7, daysAgo), daysAgo);
		}
		consulted(encounter(7, 84), 84, everyThreeMonths);
		
		refresh.refreshAll(TODAY);
		
		// As of today, 56 of 200 days late; ACT 2.0 kept the batch's 100% until 90 days had passed.
		assertEquals(1.0, ((Number) row(7).get(3)).doubleValue(), 1e-12);
	}
	
	@Test
	public void refreshAll_shouldRecomputeABatchEnteredPatientOnceTheNextBatchIsDue() {
		// Injected on time until 116 days ago, last seen at a consultation 95 days ago: the batch is due.
		prescribe(encounter(7, 200), q28, 200, null);
		for (int daysAgo = 200; daysAgo >= 116; daysAgo -= 28) {
			given(encounter(7, daysAgo), daysAgo);
		}
		consulted(encounter(7, 95), 95, everyThreeMonths);
		
		refresh.refreshAll(TODAY);
		
		assertEquals(1 - 88.0 / 201, ((Number) row(7).get(3)).doubleValue(), 1e-12);
	}
	
	@Test
	public void refreshAll_shouldComputeAContinuouslyEnteredPatientAsOfToday() {
		prescribe(encounter(7, 200), q28, 200, null);
		for (int daysAgo = 200; daysAgo >= 88; daysAgo -= 28) {
			given(encounter(7, daysAgo), daysAgo);
		}
		consulted(encounter(7, 84), 84, continuous);
		
		refresh.refreshAll(TODAY);
		
		assertEquals(1 - 60.0 / 201, ((Number) row(7).get(3)).doubleValue(), 1e-12);
	}
	
	@Test
	public void refreshAll_shouldNotHoldABatchEnteredPatientAtAConsultationDatedInTheFuture() {
		onTimeUntil(116);
		consulted(encounter(7, 1), -60, everyThreeMonths);
		
		refresh.refreshAll(TODAY);
		
		// The future date is ignored, the last injection 116 days ago is past the batch interval: as of today.
		assertEquals(1 - 88.0 / 201, ((Number) row(7).get(3)).doubleValue(), 1e-12);
	}
	
	@Test
	public void refreshAll_shouldTakeTheLatestConsultationDate() {
		onTimeUntil(116);
		consulted(encounter(7, 150), 150, everyThreeMonths);
		Obs date = obs(encounter(7, 84), consultationDate);
		date.setValueDatetime(at(84));
		Context.getObsService().saveObs(date, null);
		
		refresh.refreshAll(TODAY);
		
		// Held as of the consultation 84 days ago: the injection due 88 days ago is 4 of 117 days late.
		assertEquals(1 - 4.0 / 117, ((Number) row(7).get(3)).doubleValue(), 1e-12);
	}
	
	@Test
	public void refreshAll_shouldTakeTheLatestDataEntryAnswer() {
		prescribe(encounter(7, 200), q28, 200, null);
		for (int daysAgo = 200; daysAgo >= 88; daysAgo -= 28) {
			given(encounter(7, daysAgo), daysAgo);
		}
		consulted(encounter(7, 150), 150, everyThreeMonths);
		consulted(encounter(7, 84), 84, continuous);
		
		refresh.refreshAll(TODAY);
		
		assertEquals(1 - 60.0 / 201, ((Number) row(7).get(3)).doubleValue(), 1e-12);
	}
	
	@Test
	public void refreshAll_shouldStoreTheRegimenAHeldPatientWasComputedFor() {
		Encounter consultation = encounter(7, 10);
		prescribe(consultation, q28, 200, null);
		prescribe(consultation, q21, 3, null);
		given(encounter(7, 32), 32);
		consulted(consultation, 10, everyThreeMonths);
		
		refresh.refreshAll(TODAY);
		
		// Held as of 10 days ago, before the Q21 started: the Q28 row, due 28 days after the last injection.
		List<Object> row = row(7);
		assertEquals((int) q28.getConceptId(), number(row.get(1)));
		assertEquals(28, number(row.get(2)));
		assertEquals(TODAY.minusDays(4), date(row.get(5)));
	}
	
	@Test
	public void refreshAll_shouldKeepTheFirstPrescriptionOfAStartDate() {
		Encounter consultation = encounter(7, 27);
		prescribe(consultation, q21, 27, null);
		prescribe(consultation, oralPenicillin, 27, null);
		
		refresh.refreshAll(TODAY);
		
		assertEquals((int) q21.getConceptId(), number(row(7).get(1)));
		assertEquals(21, number(row(7).get(2)));
	}
	
	@Test
	public void refreshAll_shouldWriteEveryRowWhenTheyTakeMoreThanOneInsert() {
		prescribe(encounter(7, 27), q21, 27, null);
		prescribe(encounter(2, 27), q28, 27, null);
		
		assertEquals(2, new AdherenceRefresh(1).refreshAll(TODAY));
		
		assertEquals(2, rows().size());
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
	
	/** Q28 prescribed 200 days ago, every injection on time until the one ``lastDaysAgo`` ago. */
	private void onTimeUntil(int lastDaysAgo) {
		prescribe(encounter(7, 200), q28, 200, null);
		for (int daysAgo = 200; daysAgo >= lastDaysAgo; daysAgo -= 28) {
			given(encounter(7, daysAgo), daysAgo);
		}
	}
	
	private void consulted(Encounter encounter, int daysAgo, Concept entry) {
		Obs date = obs(encounter, consultationDate);
		date.setValueDatetime(at(daysAgo));
		Context.getObsService().saveObs(date, null);
		Obs how = obs(encounter, dataEntry);
		how.setValueCoded(entry);
		Context.getObsService().saveObs(how, null);
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
