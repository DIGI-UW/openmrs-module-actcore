/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.openmrs.Concept;
import org.openmrs.ConceptName;
import org.openmrs.ConceptNumeric;
import org.openmrs.Encounter;
import org.openmrs.Form;
import org.openmrs.Obs;
import org.openmrs.Patient;
import org.openmrs.PatientIdentifier;
import org.openmrs.PatientIdentifierType;
import org.openmrs.PersonAttribute;
import org.openmrs.PersonAttributeType;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.studies.StudyEnrolmentRules;
import org.openmrs.test.BaseModuleContextSensitiveTest;

public class FormDownloadContextTest extends BaseModuleContextSensitiveTest {
	
	private static final LocalDate DAY = LocalDate.of(2026, 3, 14);
	
	private Form form;
	
	private Concept echoDate;
	
	private Concept finding;
	
	private Concept ejection;
	
	private Concept severe;
	
	private Concept yes;
	
	private PersonAttributeType phone;
	
	private PersonAttributeType optIn;
	
	private PatientIdentifierType nationalId;
	
	@Before
	public void setUp() {
		form = new Form();
		form.setName("RHD Echocardiogram");
		form.setVersion("1");
		form.setEncounterType(Context.getEncounterService().getEncounterType(1));
		form = Context.getFormService().saveForm(form);
		echoDate = concept("Date of echocardiogram", "Date");
		finding = concept("Mitral regurgitation", "Coded");
		ejection = concept("Ejection fraction", "Numeric");
		severe = concept("Severe", "N/A");
		yes = concept("Yes", "N/A");
		
		phone = attributeType("Primary Phone", "java.lang.String");
		optIn = attributeType("Research Opt-In", "org.openmrs.Concept");
		nationalId = new PatientIdentifierType();
		nationalId.setName("National ID");
		Context.getPatientService().savePatientIdentifierType(nationalId);
		property(FormDownload.ATTRIBUTE_TYPES_PROPERTY, phone.getUuid());
		property(FormDownload.IDENTIFIER_TYPES_PROPERTY, nationalId.getUuid());
		property(StudyEnrolmentRules.OPT_IN_PROPERTY, optIn.getUuid());
		property(StudyEnrolmentRules.YES_PROPERTY, yes.getUuid());
		
		Patient patient = Context.getPatientService().getPatient(7);
		patient.addAttribute(new PersonAttribute(phone, "+256 700 000001"));
		PatientIdentifier national = new PatientIdentifier("CM123", nationalId, Context.getLocationService().getLocation(1));
		national.setPreferred(true);
		patient.getPatientIdentifier().setPreferred(false);
		patient.addIdentifier(national);
		Context.getPatientService().savePatient(patient);
	}
	
	@Test
	public void build_shouldGiveARowPerFormWithTheChosenAnswersWithoutIdentifiers() {
		Encounter echo = encounter(7, 0);
		answer(echo, echoDate, null, Timestamp.valueOf(DAY.minusDays(1).atStartOfDay()), null);
		answer(echo, finding, severe, null, null);
		answer(echo, ejection, null, null, 55.0);
		
		FormDownload.Table table = build(false, false, null, null);
		
		assertEquals(Arrays.asList("ACT ID", "Sex", "Age", "Form date", "Location", "Echo date", "MR", "EF"), table.headers);
		assertEquals(1, table.rows.size());
		List<String> row = table.rows.get(0);
		assertEquals("6TS-4", row.get(0));
		assertEquals("F", row.get(1));
		assertEquals(DAY.toString(), row.get(3));
		assertEquals(DAY.minusDays(1).toString(), row.get(5));
		assertTrue(row.get(6).startsWith("Severe"));
		assertEquals("55", row.get(7));
		assertFalse(String.join(",", row).contains("CM123"));
		assertFalse(String.join(",", row).contains("+256"));
		assertFalse(String.join(",", row).contains(Context.getPatientService().getPatient(7).getGivenName()));
	}
	
	@Test
	public void build_shouldAddTheNamesBirthDateIdNumbersAndPhonesWhenIdentified() {
		encounter(7, 0);
		Patient patient = Context.getPatientService().getPatient(7);
		
		FormDownload.Table table = build(true, false, null, null);
		
		assertEquals(Arrays.asList("ACT ID", "Given name", "Family name", "Birth date", "National ID", "Primary Phone",
		    "Sex", "Age", "Form date", "Location", "Echo date", "MR", "EF"), table.headers);
		List<String> row = table.rows.get(0);
		assertEquals(Arrays.asList("6TS-4", patient.getGivenName(), patient.getFamilyName(), "1976-08-25", "CM123",
		    "+256 700 000001"), row.subList(0, 6));
	}
	
	@Test
	public void build_shouldKeepTheFormsInTheDateRange() {
		encounter(7, 10);
		encounter(7, 5);
		encounter(7, 0);
		
		FormDownload.Table table = build(false, false, DAY.minusDays(5), DAY);
		
		assertEquals(2, table.rows.size());
		assertEquals(DAY.minusDays(5).toString(), table.rows.get(0).get(3));
		assertEquals(DAY.toString(), table.rows.get(1).get(3));
	}
	
	@Test
	public void build_shouldJoinRepeatedAnswersInTheOrderSaved() {
		Encounter echo = encounter(7, 0);
		answer(echo, ejection, null, null, 50.0);
		answer(echo, ejection, null, null, 52.5);
		
		assertEquals("50; 52.5", build(false, false, null, null).rows.get(0).get(7));
	}
	
	@Test
	public void build_shouldGiveAResearchDownloadOnlyThePatientsWhoOptedIn() {
		encounter(7, 0);
		encounter(2, 0);
		Patient patient = Context.getPatientService().getPatient(2);
		patient.addAttribute(new PersonAttribute(optIn, yes.getConceptId().toString()));
		Context.getPatientService().savePatient(patient);
		
		FormDownload.Table table = build(false, true, null, null);
		
		assertEquals(1, table.rows.size());
		assertEquals("101-6", table.rows.get(0).get(0));
	}
	
	@Test
	public void build_shouldGiveAResearchDownloadNoPatientsWithoutTheOptInConfigured() {
		encounter(2, 0);
		Patient patient = Context.getPatientService().getPatient(2);
		patient.addAttribute(new PersonAttribute(optIn, yes.getConceptId().toString()));
		Context.getPatientService().savePatient(patient);
		property(StudyEnrolmentRules.OPT_IN_PROPERTY, "");
		
		assertTrue(build(false, true, null, null).rows.isEmpty());
	}
	
	@Test
	public void build_shouldLeaveOutAnIdentifyingQuestionUnlessIdentified() {
		Concept address = concept("Home Address", "Text");
		property(FormDownload.CONCEPTS_PROPERTY, "some-other-uuid, " + address.getUuid());
		Encounter encounter = encounter(2, 0);
		Patient patient = encounter.getPatient();
		patient.addAttribute(new PersonAttribute(optIn, yes.getConceptId().toString()));
		Context.getPatientService().savePatient(patient);
		Obs obs = new Obs(patient, address, encounter.getEncounterDatetime(), encounter.getLocation());
		obs.setEncounter(encounter);
		obs.setValueText("Plot 12, Kira Road, Kampala");
		Context.getObsService().saveObs(obs, null);
		List<Concept> asked = Arrays.asList(ejection, address);
		List<String> labels = Arrays.asList("EF", "Home Address");
		
		for (boolean researchOnly : new boolean[] { false, true }) {
			FormDownload.Table table = new FormDownload().build(form, asked, labels, null, null, false, researchOnly);
			assertEquals("EF", table.headers.get(table.headers.size() - 1));
			assertEquals(Arrays.asList("Home Address"), table.withheld);
			assertEquals(1, table.rows.size());
			assertFalse(String.join(",", table.rows.get(0)).contains("Kira Road"));
		}
		FormDownload.Table identified = new FormDownload().build(form, asked, labels, null, null, true, false);
		assertEquals("Home Address", identified.headers.get(identified.headers.size() - 1));
		assertTrue(identified.withheld.isEmpty());
		assertTrue(identified.rows.get(0).contains("Plot 12, Kira Road, Kampala"));
	}
	
	private FormDownload.Table build(boolean identified, boolean researchOnly, LocalDate from, LocalDate to) {
		return new FormDownload().build(form, Arrays.asList(echoDate, finding, ejection),
		    Arrays.asList("Echo date", "MR", "EF"), from, to, identified, researchOnly);
	}
	
	private Encounter encounter(int patientId, int daysAgo) {
		Encounter encounter = new Encounter();
		encounter.setPatient(Context.getPatientService().getPatient(patientId));
		encounter.setEncounterType(Context.getEncounterService().getEncounterType(1));
		encounter.setForm(form);
		encounter.setLocation(Context.getLocationService().getLocation(1));
		encounter.setEncounterDatetime(Timestamp.valueOf(DAY.minusDays(daysAgo).atTime(10, 0)));
		return Context.getEncounterService().saveEncounter(encounter);
	}
	
	private static void answer(Encounter encounter, Concept question, Concept coded, Date date, Double numeric) {
		Obs obs = new Obs(encounter.getPatient(), question, encounter.getEncounterDatetime(), encounter.getLocation());
		obs.setEncounter(encounter);
		obs.setValueCoded(coded);
		obs.setValueDatetime(date);
		obs.setValueNumeric(numeric);
		Context.getObsService().saveObs(obs, null);
	}
	
	private static PersonAttributeType attributeType(String name, String format) {
		PersonAttributeType type = new PersonAttributeType();
		type.setName(name);
		type.setFormat(format);
		return Context.getPersonService().savePersonAttributeType(type);
	}
	
	private static Concept concept(String name, String datatype) {
		Concept concept = "Numeric".equals(datatype) ? new ConceptNumeric() : new Concept();
		concept.addName(new ConceptName(name + " " + System.nanoTime(), Context.getLocale()));
		concept.setDatatype(Context.getConceptService().getConceptDatatypeByName(datatype));
		concept.setConceptClass(Context.getConceptService().getConceptClass(1));
		if ("Numeric".equals(datatype)) {
			((ConceptNumeric) concept).setAllowDecimal(true);
		}
		return Context.getConceptService().saveConcept(concept);
	}
	
	private static void property(String name, String value) {
		Context.getAdministrationService().setGlobalProperty(name, value);
	}
}
