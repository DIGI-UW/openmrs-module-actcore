/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.consultation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Date;

import org.junit.Before;
import org.junit.Test;
import org.openmrs.Concept;
import org.openmrs.ConceptName;
import org.openmrs.ConceptNumeric;
import org.openmrs.Encounter;
import org.openmrs.Obs;
import org.openmrs.Patient;
import org.openmrs.PatientProgram;
import org.openmrs.Program;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.registry.EnrolOnRegistration;
import org.openmrs.test.BaseModuleContextSensitiveTest;

public class NextConsultationContextTest extends BaseModuleContextSensitiveTest {
	
	private static final LocalDate TODAY = LocalDate.now();
	
	private Concept amount;
	
	private Concept period;
	
	private Concept days;
	
	private Concept weeks;
	
	private Concept months;
	
	private Concept years;
	
	private Program registry;
	
	@Before
	public void setUp() {
		amount = concept("Next consultation time amount", "Numeric", null);
		period = concept("Time period", "Coded", null);
		days = concept("Days", "N/A", "1072AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
		weeks = concept("Weeks", "N/A", "1073AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
		months = concept("Months", "N/A", "1074AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
		years = concept("Years", "N/A", "1734AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
		property(NextConsultation.GP_AMOUNT, amount.getUuid());
		property(NextConsultation.GP_PERIOD, period.getUuid());
		registry = Context.getProgramWorkflowService().getProgram(1);
		property(EnrolOnRegistration.PROGRAM_PROPERTY, registry.getUuid());
		enrol(7, null);
	}
	
	@Test
	public void of_shouldAddTheAmountInItsPeriodToTheConsultationsDate() {
		review(encounter(7, 10), 3.0, weeks);
		
		assertEquals(TODAY.plusDays(11), next(7));
	}
	
	@Test
	public void of_shouldReadEachPeriod() {
		Encounter consultation = encounter(7, 40);
		review(consultation, 30.0, days);
		assertEquals(TODAY.minusDays(10), next(7));
		
		review(encounter(7, 30), 2.0, months);
		assertEquals(TODAY.minusDays(30).plusMonths(2), next(7));
		
		review(encounter(7, 20), 1.0, years);
		assertEquals(TODAY.minusDays(20).plusYears(1), next(7));
	}
	
	@Test
	public void of_shouldTakeTheLatestConsultationThatRecordsAPeriod() {
		review(encounter(7, 30), 6.0, weeks);
		Encounter later = encounter(7, 5);
		Context.getObsService().saveObs(answer(later, amount, 1.0), null);
		
		assertEquals(TODAY.minusDays(30).plusWeeks(6), next(7));
	}
	
	@Test
	public void of_shouldBeNullWithoutAReviewDate() {
		assertNull(next(7));
	}
	
	@Test
	public void of_shouldBeNullForAPatientNotOnTheRegistryNow() {
		review(encounter(8, 10), 3.0, weeks);
		assertNull(next(8));
		
		enrol(8, 2);
		assertNull(next(8));
	}
	
	@Test
	public void of_shouldBeNullForAPatientWhoHasDied() {
		review(encounter(7, 400), 1.0, years);
		assertEquals(TODAY.minusDays(400).plusYears(1), next(7));
		
		Patient patient = Context.getPatientService().getPatient(7);
		patient.setDead(true);
		patient.setDeathDate(at(1));
		patient.setCauseOfDeath(Context.getConceptService().getConcept(3));
		Context.getPatientService().savePatient(patient);
		
		assertNull(next(7));
	}
	
	private LocalDate next(int patientId) {
		return NextConsultation.of(Context.getPatientService().getPatient(patientId));
	}
	
	private void review(Encounter encounter, double value, Concept unit) {
		Context.getObsService().saveObs(answer(encounter, amount, value), null);
		Obs chosen = answer(encounter, period, null);
		chosen.setValueCoded(unit);
		Context.getObsService().saveObs(chosen, null);
	}
	
	private static Obs answer(Encounter encounter, Concept question, Double value) {
		Obs obs = new Obs(encounter.getPatient(), question, encounter.getEncounterDatetime(), encounter.getLocation());
		obs.setEncounter(encounter);
		obs.setValueNumeric(value);
		return obs;
	}
	
	private void enrol(int patientId, Integer completedDaysAgo) {
		PatientProgram enrolment = new PatientProgram();
		enrolment.setPatient(Context.getPatientService().getPatient(patientId));
		enrolment.setProgram(registry);
		enrolment.setDateEnrolled(at(100));
		enrolment.setDateCompleted(completedDaysAgo == null ? null : at(completedDaysAgo));
		Context.getProgramWorkflowService().savePatientProgram(enrolment);
	}
	
	private static Encounter encounter(int patientId, int daysAgo) {
		Encounter encounter = new Encounter();
		Patient patient = Context.getPatientService().getPatient(patientId);
		encounter.setPatient(patient);
		encounter.setEncounterType(Context.getEncounterService().getEncounterType(1));
		encounter.setLocation(Context.getLocationService().getLocation(1));
		encounter.setEncounterDatetime(at(daysAgo));
		return Context.getEncounterService().saveEncounter(encounter);
	}
	
	private static Date at(int daysAgo) {
		return Timestamp.valueOf(TODAY.minusDays(daysAgo).atTime(10, 0));
	}
	
	private static Concept concept(String name, String datatype, String uuid) {
		// The obs validator reads a numeric concept's ranges, so it must be a ConceptNumeric.
		Concept concept = "Numeric".equals(datatype) ? new ConceptNumeric() : new Concept();
		if (uuid != null) {
			concept.setUuid(uuid);
		}
		concept.addName(new ConceptName(name + " " + System.nanoTime(), Context.getLocale()));
		concept.setDatatype(Context.getConceptService().getConceptDatatypeByName(datatype));
		concept.setConceptClass(Context.getConceptService().getConceptClass(1));
		return Context.getConceptService().saveConcept(concept);
	}
	
	private static void property(String name, String value) {
		Context.getAdministrationService().setGlobalProperty(name, value);
	}
}
