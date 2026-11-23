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
import java.util.Collections;
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
import org.openmrs.PatientProgram;
import org.openmrs.Program;
import org.openmrs.Location;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.logs.ActivityLog;
import org.openmrs.module.actcore.registry.EnrolOnRegistration;
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
	public void build_shouldGiveOnlyThePatientFieldsChosen() {
		encounter(7, 0);
		
		FormDownload.Table table = new FormDownload().build(form, Arrays.asList(ejection), Arrays.asList("EF"), null, null,
		    true, false, filters(null, null, null), Arrays.asList("sex"));
		
		assertEquals(Arrays.asList("ACT ID", "Sex", "Form date", "Location", "EF"), table.headers);
		assertEquals(Arrays.asList("6TS-4", "F", DAY.toString()), table.rows.get(0).subList(0, 3));
		assertEquals(Arrays.asList("ACT ID", "Form date", "Location", "EF"),
		    new FormDownload().build(form, Arrays.asList(ejection), Arrays.asList("EF"), null, null, true, false,
		        filters(null, null, null), Collections.<String> emptyList()).headers);
	}
	
	@Test
	public void buildPerPatient_shouldGiveTheIdentifyingPatientFieldsChosenOnlyWhenIdentified() {
		encounter(form, 7, 0);
		List<String> chosen = Arrays.asList("phones", "givenName", "identifiers", "age");
		Patient patient = Context.getPatientService().getPatient(7);
		
		FormDownload.Table identified = new FormDownload().buildPerPatient(
		    Collections.singletonList(choice(form, "Echo", ejection, "EF")), null, null, true, false,
		    filters(null, null, null), chosen);
		FormDownload.Table not = new FormDownload().buildPerPatient(
		    Collections.singletonList(choice(form, "Echo", ejection, "EF")), null, null, false, false,
		    filters(null, null, null), chosen);
		
		assertEquals(Arrays.asList("ACT ID", "Given name", "National ID", "Primary Phone", "Age"),
		    identified.headers.subList(0, 5));
		assertEquals(Arrays.asList("6TS-4", patient.getGivenName(), "CM123", "+256 700 000001", patient.getAge().toString()),
		    identified.rows.get(0).subList(0, 5));
		assertTrue(identified.withheldPatient.isEmpty());
		assertEquals(Arrays.asList("ACT ID", "Age", "Echo 1: Form date"), not.headers.subList(0, 3));
		assertEquals(Arrays.asList("givenName", "identifiers", "phones"), not.withheldPatient);
		assertEquals(Arrays.asList("age"), not.patient);
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
	
	@Test(expected = UnsupportedOperationException.class)
	public void patientFields_shouldNotBeChangeable() {
		FormDownload.PATIENT_FIELDS.put("address", "Address");
	}
	
	@Test
	public void buildPerPatient_shouldGiveARowPerPatientWithEachFormNumberedOldestFirst() {
		Form consult = otherForm("RHD Consultation Visit");
		Encounter first = encounter(form, 7, 5);
		answer(first, ejection, null, null, 50.0);
		Encounter second = encounter(form, 7, 0);
		answer(second, ejection, null, null, 60.0);
		Encounter visit = encounter(consult, 2, 1);
		answer(visit, finding, severe, null, null);
		
		FormDownload.Table table = new FormDownload().buildPerPatient(
		    Arrays.asList(choice(form, "Echo", ejection, "EF"), choice(consult, "Consultation", finding, "MR")), null, null,
		    false, false, filters(null, null, null));
		
		assertEquals(Arrays.asList("ACT ID", "Sex", "Age", "Echo 1: Form date", "Echo 1: Location", "Echo 1: EF",
		    "Echo 2: Form date", "Echo 2: Location", "Echo 2: EF", "Consultation 1: Form date", "Consultation 1: Location",
		    "Consultation 1: MR"), table.headers);
		assertEquals(2, table.rows.size());
		List<String> seven = row(table, "6TS-4");
		assertEquals(Arrays.asList(DAY.minusDays(5).toString(), "50", DAY.toString(), "60", "", ""),
		    Arrays.asList(seven.get(3), seven.get(5), seven.get(6), seven.get(8), seven.get(9), seven.get(11)));
		List<String> two = row(table, "101-6");
		assertEquals(Arrays.asList("", "", "", DAY.minusDays(1).toString()),
		    Arrays.asList(two.get(3), two.get(5), two.get(6), two.get(9)));
		assertTrue(two.get(11).startsWith("Severe"));
	}
	
	@Test
	public void buildPerPatient_shouldKeepThePatientsWithAChosenFormInTheRangeAndOnlyThoseForms() {
		Form consult = otherForm("RHD Consultation Visit");
		encounter(form, 7, 20);
		encounter(form, 7, 1);
		encounter(consult, 2, 20);
		
		FormDownload.Table table = new FormDownload().buildPerPatient(
		    Arrays.asList(choice(form, "Echo", ejection, "EF"), choice(consult, "Consultation", finding, "MR")),
		    DAY.minusDays(5), DAY, false, false, filters(null, null, null));
		
		assertEquals(1, table.rows.size());
		assertEquals("6TS-4", table.rows.get(0).get(0));
		assertFalse(table.headers.contains("Echo 2: Form date"));
		assertEquals(DAY.minusDays(1).toString(), table.rows.get(0).get(3));
	}
	
	@Test
	public void buildPerPatient_shouldLeaveOutAnIdentifyingQuestionNamingItsForm() {
		Concept address = concept("Home Address", "Text");
		property(FormDownload.CONCEPTS_PROPERTY, address.getUuid());
		encounter(form, 7, 0);
		FormDownload.Choice echo = new FormDownload.Choice(form, "Echo", Arrays.asList(ejection, address),
		        Arrays.asList("EF", "Home Address"));
		
		FormDownload.Table table = new FormDownload().buildPerPatient(Collections.singletonList(echo), null, null, false,
		    false, filters(null, null, null));
		
		assertEquals(Arrays.asList("Echo: Home Address"), table.withheld);
		assertFalse(table.headers.contains("Echo 1: Home Address"));
	}
	
	@Test
	public void buildPerPatient_shouldHeadEachFormsColumnsWhenNoPatientSavedIt() {
		Form consult = otherForm("RHD Consultation Visit");
		encounter(form, 7, 0);
		List<FormDownload.Choice> choices = Arrays.asList(choice(form, "Echo", ejection, "EF"),
		    choice(consult, "Consultation", finding, "MR"));
		List<String> headers = Arrays.asList("ACT ID", "Sex", "Age", "Echo 1: Form date", "Echo 1: Location", "Echo 1: EF",
		    "Consultation 1: Form date", "Consultation 1: Location", "Consultation 1: MR");
		
		FormDownload.Table one = new FormDownload().buildPerPatient(choices, null, null, false, false,
		    filters(null, null, null));
		assertEquals(headers, one.headers);
		assertEquals(Arrays.asList("", "", ""), one.rows.get(0).subList(6, 9));
		
		FormDownload.Table none = new FormDownload().buildPerPatient(choices, DAY.plusDays(1), null, false, false,
		    filters(null, null, null));
		assertEquals(headers, none.headers);
		assertTrue(none.rows.isEmpty());
	}
	
	@Test
	public void filters_shouldKeepTheActiveOrInactivePatientsByTheirLatestRegistryEnrolment() {
		Program registry = registryProgram();
		enrol(7, registry, null);
		enrol(2, registry, DAY.minusDays(3));
		encounter(form, 7, 0);
		encounter(form, 2, 0);
		
		assertEquals(Arrays.asList("6TS-4"), actIds(filters("active", null, null)));
		assertEquals(Arrays.asList("101-6"), actIds(filters("inactive", null, null)));
		
		Patient seven = Context.getPatientService().getPatient(7);
		seven.setDead(true);
		seven.setDeathDate(Timestamp.valueOf(DAY.atStartOfDay()));
		seven.setCauseOfDeath(severe);
		Context.getPatientService().savePatient(seven);
		assertTrue(actIds(filters("active", null, null)).isEmpty());
	}
	
	@Test
	public void filters_shouldKeepAPatientReEnrolledAfterACompletedEnrolmentActive() {
		Program registry = registryProgram();
		enrol(7, registry, DAY.minusDays(60), DAY.minusDays(40));
		enrol(7, registry, DAY.minusDays(30), null);
		encounter(form, 7, 0);
		
		assertEquals(Arrays.asList("6TS-4"), actIds(filters("active", null, null)));
		assertTrue(actIds(filters("inactive", null, null)).isEmpty());
	}
	
	@Test
	public void filters_shouldDecideEachPatientOnceADownload() {
		Program registry = registryProgram();
		enrol(7, registry, null);
		Patient seven = Context.getPatientService().getPatient(7);
		FormDownload.Filters active = filters("active", null, null);
		assertTrue(active.matches(seven));
		
		PatientProgram enrolment = Context.getProgramWorkflowService()
		        .getPatientPrograms(seven, registry, null, null, null, null, false).get(0);
		enrolment.setDateCompleted(Timestamp.valueOf(DAY.atStartOfDay()));
		Context.getProgramWorkflowService().savePatientProgram(enrolment);
		
		assertTrue(active.matches(seven));
		assertFalse(filters("active", null, null).matches(seven));
	}
	
	@Test
	public void filters_shouldKeepThePatientsOfTheChosenClinics() {
		PersonAttributeType primaryCare = attributeType("Primary care clinic", "org.openmrs.Location");
		PersonAttributeType cardiac = attributeType("Cardiac clinic", "org.openmrs.Location");
		property(ActivityLog.CLINIC_ATTRIBUTE_TYPES_PROPERTY, primaryCare.getUuid() + "," + cardiac.getUuid());
		Location one = Context.getLocationService().getLocation(1);
		Location two = Context.getLocationService().getLocation(2);
		clinic(7, cardiac, one);
		clinic(7, primaryCare, two);
		clinic(2, cardiac, two);
		encounter(form, 7, 0);
		encounter(form, 2, 0);
		
		assertEquals(Arrays.asList("6TS-4"), actIds(filters(null, one, null)));
		assertEquals(Arrays.asList("101-6"), actIds(filters(null, two, null)));
		assertEquals(Arrays.asList("6TS-4"), actIds(filters(null, null, two)));
		assertTrue(actIds(filters(null, two, two)).isEmpty());
	}
	
	@Test
	public void build_shouldApplyTheFiltersToARowPerSavedForm() {
		Program registry = registryProgram();
		enrol(7, registry, null);
		enrol(2, registry, DAY.minusDays(3));
		encounter(form, 7, 0);
		encounter(form, 2, 0);
		
		FormDownload.Table table = new FormDownload().build(form, Arrays.asList(ejection), Arrays.asList("EF"), null, null,
		    false, false, filters("active", null, null));
		
		assertEquals(1, table.rows.size());
		assertEquals("6TS-4", table.rows.get(0).get(0));
	}
	
	private List<String> actIds(FormDownload.Filters filters) {
		List<String> ids = new java.util.ArrayList<String>();
		new FormDownload().buildPerPatient(Collections.singletonList(choice(form, "Echo", ejection, "EF")), null, null,
		    false, false, filters).rows.forEach(r -> ids.add(r.get(0)));
		return ids;
	}
	
	private static FormDownload.Filters filters(String status, Location cardiac, Location primaryCare) {
		return new FormDownload.Filters(status, cardiac, primaryCare);
	}
	
	private static FormDownload.Choice choice(Form form, String name, Concept concept, String label) {
		return new FormDownload.Choice(form, name, Collections.singletonList(concept), Collections.singletonList(label));
	}
	
	private static List<String> row(FormDownload.Table table, String actId) {
		return table.rows.stream().filter(r -> r.get(0).equals(actId)).findFirst().get();
	}
	
	private static Form otherForm(String name) {
		Form other = new Form();
		other.setName(name);
		other.setVersion("1");
		other.setEncounterType(Context.getEncounterService().getEncounterType(1));
		return Context.getFormService().saveForm(other);
	}
	
	private Program registryProgram() {
		Program program = new Program();
		program.setName("RHD Registry " + System.nanoTime());
		program.setDescription("RHD Registry");
		program.setConcept(concept("RHD Registry", "N/A"));
		program = Context.getProgramWorkflowService().saveProgram(program);
		property(EnrolOnRegistration.PROGRAM_PROPERTY, program.getUuid());
		return program;
	}
	
	private static void enrol(int patientId, Program program, LocalDate completed) {
		enrol(patientId, program, DAY.minusDays(30), completed);
	}
	
	private static void enrol(int patientId, Program program, LocalDate enrolled, LocalDate completed) {
		PatientProgram enrolment = new PatientProgram();
		enrolment.setPatient(Context.getPatientService().getPatient(patientId));
		enrolment.setProgram(program);
		enrolment.setDateEnrolled(Timestamp.valueOf(enrolled.atStartOfDay()));
		enrolment.setDateCompleted(completed == null ? null : Timestamp.valueOf(completed.atStartOfDay()));
		Context.getProgramWorkflowService().savePatientProgram(enrolment);
	}
	
	private static void clinic(int patientId, PersonAttributeType type, Location location) {
		Patient patient = Context.getPatientService().getPatient(patientId);
		patient.addAttribute(new PersonAttribute(type, location.getLocationId().toString()));
		Context.getPatientService().savePatient(patient);
	}
	
	private Encounter encounter(Form chosen, int patientId, int daysAgo) {
		Encounter encounter = encounter(patientId, daysAgo);
		encounter.setForm(chosen);
		return Context.getEncounterService().saveEncounter(encounter);
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
