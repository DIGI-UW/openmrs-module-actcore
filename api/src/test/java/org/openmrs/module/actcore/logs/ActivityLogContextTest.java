/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.logs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.Before;
import org.junit.Test;
import org.openmrs.Concept;
import org.openmrs.ConceptName;
import org.openmrs.ConceptNumeric;
import org.openmrs.Encounter;
import org.openmrs.Form;
import org.openmrs.Person;
import org.openmrs.PersonAttributeType;
import org.openmrs.PersonName;
import org.openmrs.Program;
import org.openmrs.Role;
import org.openmrs.User;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.Daemon;
import org.openmrs.module.actcore.studies.StudyEnrolmentRules;
import org.openmrs.module.patientflags.Flag;
import org.openmrs.module.patientflags.api.FlagService;
import org.openmrs.module.patientflags.evaluator.SQLFlagEvaluator;
import org.openmrs.test.BaseModuleContextSensitiveTest;

/**
 * The audit rows are written here as Envers writes them on a save: one revision per save, a row per
 * entity touched, with REVTYPE 0 added, 1 changed, 2 deleted.
 */
public class ActivityLogContextTest extends BaseModuleContextSensitiveTest {
	
	private static final int PATIENT = 7;
	
	private static final int SAVED = 10;
	
	private static final int EDITED = 11;
	
	private static final int VOIDED = 12;
	
	private static final int REGISTERED = 13;
	
	private static final int USER_CREATED = 14;
	
	private static final int SETUP_ONLY = 15;
	
	private static final int DECEASED = 16;
	
	private static final int MOVED = 17;
	
	private final ActivityLog log = new ActivityLog();
	
	private User clinician;
	
	private Form form;
	
	private Encounter encounter;
	
	private Concept given;
	
	private Concept dose;
	
	private Concept date;
	
	private Concept yes;
	
	private Flag flag;
	
	@Before
	public void setUp() {
		AuditTables.create();
		Role role = new Role("Organizational: ACT Clinician");
		Context.getUserService().saveRole(role);
		Person person = new Person();
		person.addName(new PersonName("Sarah", null, "Namusoke"));
		person.setGender("F");
		clinician = new User(person);
		clinician.setUsername("clinician");
		clinician.addRole(role);
		Context.getUserService().createUser(clinician, "Clinician123");
		
		form = new Form();
		form.setName("RHD BPG Delivery");
		form.setVersion("1");
		form.setEncounterType(Context.getEncounterService().getEncounterType(1));
		Context.getFormService().saveForm(form);
		yes = Context.getConceptService().getConceptByName("Yes");
		given = concept("Was BPG given at this visit?", "Coded");
		dose = concept("Dose given", "Numeric");
		date = concept("Date of Injection", "Date");
		encounter = new Encounter();
		encounter.setPatient(Context.getPatientService().getPatient(PATIENT));
		encounter.setEncounterType(form.getEncounterType());
		encounter.setForm(form);
		encounter.setLocation(Context.getLocationService().getLocation(1));
		encounter.setEncounterDatetime(Timestamp.valueOf("2026-10-21 10:52:00"));
		Context.getEncounterService().saveEncounter(encounter);
		
		flag = new Flag();
		flag.setUuid(UUID.randomUUID().toString());
		flag.setName("RHD prophylaxis not prescribed");
		flag.setCriteria("select p.patient_id from patient p where p.patient_id = " + PATIENT);
		flag.setEvaluator(SQLFlagEvaluator.class.getName());
		flag.setMessage("not prescribed");
		flag.setEnabled(Boolean.TRUE);
		Context.getService(FlagService.class).saveFlag(flag);
		
		revision(SAVED, "2026-10-21 10:52:14", clinician.getUserId());
		sql("insert into encounter_AUD (encounter_id, REV, REVTYPE, uuid, voided, encounter_datetime, form_id, patient_id)"
		        + " values (%d, %d, 0, '%s', false, '2026-10-21 10:52:00', %d, %d)",
		    encounter.getEncounterId(), SAVED, encounter.getUuid(), form.getFormId(), PATIENT);
		obs(901, SAVED, 0, false, given, "value_coded", yes.getConceptId());
		obs(902, SAVED, 0, false, dose, "value_numeric", 1.2);
		obs(903, SAVED, 0, false, date, "value_datetime", "'2026-10-21 00:00:00'");
		sql("insert into obs_AUD (obs_id, REV, REVTYPE, person_id, concept_id, encounter_id, voided) values (904, %d, 0, %d, %d, %d, false)",
		    SAVED, PATIENT, given.getConceptId(), encounter.getEncounterId());
		
		revision(EDITED, "2026-10-21 11:10:00", clinician.getUserId());
		sql("insert into encounter_AUD (encounter_id, REV, REVTYPE, uuid, voided, encounter_datetime, form_id, patient_id)"
		        + " values (%d, %d, 1, '%s', false, '2026-10-21 10:52:00', %d, %d)",
		    encounter.getEncounterId(), EDITED, encounter.getUuid(), form.getFormId(), PATIENT);
		obs(902, EDITED, 1, true, dose, "value_numeric", 1.2);
		obs(905, EDITED, 0, false, dose, "value_numeric", 0.6);
		
		revision(VOIDED, "2026-10-21 12:00:00", clinician.getUserId());
		sql("insert into encounter_AUD (encounter_id, REV, REVTYPE, uuid, voided, void_reason, encounter_datetime, form_id,"
		        + " patient_id) values (%d, %d, 1, '%s', true, 'entered on the wrong patient', '2026-10-21 10:52:00', %d, %d)",
		    encounter.getEncounterId(), VOIDED, encounter.getUuid(), form.getFormId(), PATIENT);
		obs(901, VOIDED, 1, true, given, "value_coded", yes.getConceptId());
		obs(905, VOIDED, 1, true, dose, "value_numeric", 0.6);
		
		revision(SETUP_ONLY, "2026-10-21 12:30:00", 1);
	}
	
	@Test
	public void groupsASavedFormWithItsObservationsIntoOneEntry() {
		ActivityLog.Entry entry = entry(log.list(new ActivityLog.Filter()), SAVED);
		
		assertEquals("saved", entry.kind);
		assertEquals("Saved RHD BPG Delivery", entry.action);
		assertEquals("3 fields set", entry.summary);
		assertEquals("clinician", entry.user.username);
		assertEquals("Sarah Namusoke", entry.user.display);
		assertEquals("Clinician", entry.user.role);
		assertEquals("patient", entry.target.kind);
		assertEquals("Collet Test Chebaskwony", entry.target.display);
		assertEquals("6TS-4", entry.target.actId);
		assertEquals(encounter.getUuid(), entry.encounter.uuid);
		assertEquals(form.getUuid(), entry.encounter.formUuid);
		assertEquals(Timestamp.valueOf("2026-10-21 10:52:00"), entry.encounter.visitDate);
	}
	
	@Test
	public void listsAnEditedFormByWhatChanged() {
		ActivityLog.Entry entry = entry(log.list(new ActivityLog.Filter()), EDITED);
		
		assertEquals("edited", entry.kind);
		assertEquals("Edited RHD BPG Delivery", entry.action);
		assertEquals("1 field changed: Dose given", entry.summary);
		
		ActivityLog.Details details = log.details(EDITED);
		assertEquals(1, details.fields.size());
		assertEquals("Dose given", details.fields.get(0).name);
		assertEquals("1.2", details.fields.get(0).previous);
		assertEquals("0.6", details.fields.get(0).current);
		assertNull(details.voidReason);
	}
	
	@Test
	public void leavesOutAnAnswerResavedUnchangedWhenOnlyTheVisitDateMoves() {
		sql("delete from obs_AUD where REV = %d", VOIDED);
		sql("delete from encounter_AUD where REV = %d", VOIDED);
		obs(907, SAVED, 0, false, date, "value_datetime", "'2026-10-22 00:00:00'");
		revision(MOVED, "2026-10-21 13:30:00", clinician.getUserId());
		sql("insert into encounter_AUD (encounter_id, REV, REVTYPE, uuid, voided, encounter_datetime, form_id, patient_id)"
		        + " values (%d, %d, 1, '%s', false, '2026-10-20 10:52:00', %d, %d)",
		    encounter.getEncounterId(), MOVED, encounter.getUuid(), form.getFormId(), PATIENT);
		obs(903, MOVED, 1, true, date, "value_datetime", "'2026-10-21 00:00:00'");
		obs(907, MOVED, 1, true, date, "value_datetime", "'2026-10-22 00:00:00'");
		obs(908, MOVED, 0, false, date, "value_datetime", "'2026-10-22 00:00:00'");
		obs(909, MOVED, 0, false, date, "value_datetime", "'2026-10-21 00:00:00'");
		
		assertEquals("1 field changed: Visit date", entry(log.list(new ActivityLog.Filter()), MOVED).summary);
		List<ActivityLog.Field> fields = log.details(MOVED).fields;
		assertEquals(1, fields.size());
		assertEquals("Visit date", fields.get(0).name);
	}
	
	@Test
	public void listsAVoidedFormWithItsReason() {
		ActivityLog.Entry entry = entry(log.list(new ActivityLog.Filter()), VOIDED);
		
		assertEquals("voided", entry.kind);
		assertEquals("Voided RHD BPG Delivery", entry.action);
		assertEquals("Reason: entered on the wrong patient", entry.summary);
		assertEquals("entered on the wrong patient", log.details(VOIDED).voidReason);
		assertTrue(log.details(VOIDED).fields.isEmpty());
	}
	
	@Test
	public void expandsASavedFormToEachQuestionAndAnswer() {
		ActivityLog.Details details = log.details(SAVED);
		
		assertEquals(3, details.fields.size());
		assertEquals("Was BPG given at this visit?", details.fields.get(0).name);
		assertEquals(yes.getName(Context.getLocale()).getName(), details.fields.get(0).current);
		assertNull(details.fields.get(0).previous);
		assertEquals("Dose given", details.fields.get(1).name);
		assertEquals("1.2", details.fields.get(1).current);
		assertEquals("Date of Injection", details.fields.get(2).name);
		assertEquals("21-Oct-2026", details.fields.get(2).current);
	}
	
	@Test
	public void groupsARegistrationIntoOneEntryNamingTheClinicAndConsent() {
		registration();
		
		ActivityLog.Entry entry = entry(log.list(new ActivityLog.Filter()), REGISTERED);
		
		assertEquals("registered", entry.kind);
		assertEquals("Registered a patient", entry.action);
		assertEquals("Unknown Location, consent recorded", entry.summary);
		assertEquals("Collet Test Chebaskwony", entry.target.display);
		assertNull(entry.encounter);
		List<String> fields = new ArrayList<String>();
		for (ActivityLog.Field field : log.details(REGISTERED).fields) {
			fields.add(field.name + "=" + field.current);
		}
		assertTrue(fields.toString(), fields.contains("Gender=F"));
		assertTrue(fields.toString(), fields.contains("Given name=Collet"));
		assertTrue(fields.toString(), fields.contains("Primary Care Clinic=Unknown Location"));
		assertTrue(fields.toString(), fields.contains("OpenMRS Identification Number=6TS-4"));
		assertTrue(fields.toString(), fields.contains("Enrolled in=HIV PROGRAM"));
	}
	
	@Test
	public void namesAUserCreatedByTheirRole() {
		userCreated();
		
		ActivityLog.Entry entry = entry(log.list(new ActivityLog.Filter()), USER_CREATED);
		
		assertEquals("user", entry.kind);
		assertEquals("Created user", entry.action);
		assertEquals("Clinician", entry.summary);
		assertEquals("user", entry.target.kind);
		assertEquals("Sarah Namusoke", entry.target.display);
		assertEquals("clinician", entry.target.actId);
		assertEquals("admin", entry.user.username);
	}
	
	@Test
	public void listsAFlagDismissalFromActCoreOwnRecord() {
		sql("insert into actcore_flag_dismissal (patient_id, flag_id, dismissed_by, date_dismissed)"
		        + " values (%d, %d, %d, '2026-10-21 08:30:00')",
		    PATIENT, flag.getFlagId(), clinician.getUserId());
		
		ActivityLog.Page page = log.list(new ActivityLog.Filter());
		ActivityLog.Entry entry = page.results.get(page.results.size() - 1);
		
		assertNull(entry.revisionId);
		assertEquals("flag", entry.kind);
		assertEquals("Dismissed flag", entry.action);
		assertEquals("RHD prophylaxis not prescribed", entry.summary);
		assertEquals("6TS-4", entry.target.actId);
		assertEquals("clinician", entry.user.username);
	}
	
	@Test
	public void marksADeathAsAPatientStatus() {
		revision(DECEASED, "2026-10-21 13:00:00", clinician.getUserId());
		sql("insert into person_AUD (person_id, REV, REVTYPE, gender, dead, death_date) values (%d, %d, 1, 'F', true, '2026-10-18 00:00:00')",
		    PATIENT, DECEASED);
		
		ActivityLog.Entry entry = entry(log.list(new ActivityLog.Filter()), DECEASED);
		
		assertEquals("patientStatus", entry.kind);
		assertEquals("Marked deceased", entry.action);
		assertEquals("Date of death 18-Oct-2026", entry.summary);
	}
	
	@Test
	public void leavesOutTheDaemonsRevisions() {
		int daemon = Rows.integer(Rows.select("select user_id from users where uuid = ?", Daemon.getDaemonUserUuid()).get(0),
		    "user_id");
		revision(17, "2026-10-21 13:30:00", daemon);
		sql("insert into encounter_AUD (encounter_id, REV, REVTYPE, uuid, voided, encounter_datetime, form_id, patient_id)"
		        + " values (%d, 17, 0, '%s', false, '2026-10-21 10:52:00', %d, %d)",
		    encounter.getEncounterId(), encounter.getUuid(), form.getFormId(), PATIENT);
		
		ActivityLog.Page page = log.list(new ActivityLog.Filter());
		
		assertEquals(3, page.totalCount);
		for (ActivityLog.Entry entry : page.results) {
			assertTrue(entry.revisionId != 17);
		}
	}
	
	@Test
	public void listsOnlyDismissalsForTheFlagKind() {
		sql("insert into actcore_flag_dismissal (patient_id, flag_id, dismissed_by, date_dismissed)"
		        + " values (%d, %d, %d, '2026-10-21 08:30:00')",
		    PATIENT, flag.getFlagId(), clinician.getUserId());
		
		ActivityLog.Entry entry = only(filter("flag", null, null, null, null, null));
		
		assertEquals("Dismissed flag", entry.action);
		assertEquals(0, log.list(filter("flag", "admin", null, null, null, null)).totalCount);
	}
	
	@Test
	public void listsALaterEditOfADeceasedPatientOrAVoidedFormAsAnEdit() {
		revision(DECEASED, "2026-10-21 13:00:00", clinician.getUserId());
		sql("insert into person_AUD (person_id, REV, REVTYPE, gender, dead, death_date) values (%d, %d, 1, 'F', true, '2026-10-18 00:00:00')",
		    PATIENT, DECEASED);
		revision(18, "2026-10-21 14:00:00", clinician.getUserId());
		sql("insert into person_AUD (person_id, REV, REVTYPE, gender, birthdate, dead, death_date) values (%d, 18, 1, 'F', '1976-08-26 00:00:00', true, '2026-10-18 00:00:00')",
		    PATIENT);
		revision(19, "2026-10-21 15:00:00", clinician.getUserId());
		sql("insert into encounter_AUD (encounter_id, REV, REVTYPE, uuid, voided, void_reason, encounter_datetime, form_id,"
		        + " patient_id) values (%d, 19, 1, '%s', true, 'entered on the wrong patient', '2026-10-22 10:52:00', %d, %d)",
		    encounter.getEncounterId(), encounter.getUuid(), form.getFormId(), PATIENT);
		
		ActivityLog.Page page = log.list(new ActivityLog.Filter());
		
		assertEquals("patientStatus", entry(page, DECEASED).kind);
		assertEquals("edited", entry(page, 18).kind);
		assertEquals("Edited patient details", entry(page, 18).action);
		assertEquals("1 field changed: Birth date", entry(page, 18).summary);
		assertEquals("edited", entry(page, 19).kind);
		assertEquals("1 field changed: Visit date", entry(page, 19).summary);
		assertEquals(3, log.list(filter("edited", null, null, null, null, null)).totalCount);
	}
	
	@Test
	public void saysAnEnrolmentEndedOnlyWhenItWasOpenBefore() {
		Program program = Context.getProgramWorkflowService().getProgram(1);
		revision(20, "2026-10-21 16:00:00", clinician.getUserId());
		sql("insert into patient_program_AUD (patient_program_id, REV, REVTYPE, date_enrolled, date_completed, voided, program_id, patient_id)"
		        + " values (701, 20, 1, '2026-10-01 00:00:00', '2026-10-21 00:00:00', false, %d, %d)",
		    program.getProgramId(), PATIENT);
		revision(21, "2026-10-21 17:00:00", clinician.getUserId());
		sql("insert into patient_program_AUD (patient_program_id, REV, REVTYPE, date_enrolled, date_completed, voided, program_id, patient_id)"
		        + " values (701, 21, 1, '2026-10-02 00:00:00', '2026-10-21 00:00:00', false, %d, %d)",
		    program.getProgramId(), PATIENT);
		
		ActivityLog.Page page = log.list(new ActivityLog.Filter());
		
		assertEquals("Ended enrolment in study HIV PROGRAM", entry(page, 20).action);
		assertEquals("Changed enrolment in study HIV PROGRAM", entry(page, 21).action);
		assertEquals("1 field changed: Date enrolled", entry(page, 21).summary);
	}
	
	@Test
	public void namesAnEnrolmentSavedWithItsFirstStatusAsTheEnrolment() {
		java.util.Map<String, Object> enrolment = Rows
		        .select("select patient_program_id, patient_id from patient_program where program_id = 1").get(0);
		revision(22, "2026-10-21 18:00:00", clinician.getUserId());
		sql("insert into patient_program_AUD (patient_program_id, REV, REVTYPE, date_enrolled, voided, program_id, patient_id)"
		        + " values (%d, 22, 0, '2026-10-21 00:00:00', false, 1, %d)",
		    Rows.integer(enrolment, "patient_program_id"), Rows.integer(enrolment, "patient_id"));
		sql("insert into patient_state_AUD (patient_state_id, REV, REVTYPE, voided, start_date, patient_program_id, state)"
		        + " values (901, 22, 0, false, '2026-10-21 00:00:00', %d, 1)",
		    Rows.integer(enrolment, "patient_program_id"));
		
		ActivityLog.Entry entry = entry(log.list(new ActivityLog.Filter()), 22);
		
		assertEquals("Enrolled in study HIV PROGRAM", entry.action);
		assertEquals("From 21-Oct-2026", entry.summary);
	}
	
	@Test
	public void listsAnEditOfAPersonWhoIsNeitherPatientNorUserAsOther() {
		Person person = new Person();
		person.addName(new PersonName("Peter", null, "Okello"));
		person.setGender("M");
		Context.getPersonService().savePerson(person);
		Context.flushSession();
		revision(23, "2026-10-21 19:00:00", clinician.getUserId());
		sql("insert into person_name_AUD (person_name_id, REV, REVTYPE, preferred, given_name, family_name, voided, person_id)"
		        + " values (%d, 23, 1, true, 'Peter', 'Okello', false, %d)",
		    person.getPersonName().getPersonNameId(), person.getPersonId());
		
		ActivityLog.Page page = log.list(new ActivityLog.Filter());
		ActivityLog.Entry entry = entry(page, 23);
		
		assertEquals(4, page.totalCount);
		assertEquals("other", entry.kind);
		assertEquals("Edited a person's details", entry.action);
		assertEquals("Peter Okello", entry.target.display);
		assertEquals(Integer.valueOf(23), only(filter("other", null, null, null, null, null)).revisionId);
	}
	
	@Test
	public void refusesAKindFilterOverMoreRevisionsThanItClassifies() {
		sql("insert into revision_entity (id, timestamp, changedBy, changedOn)"
		        + " select 1000 + x, 0, %d, '2026-10-21 09:00:00' from system_range(1, %d)",
		    clinician.getUserId(), ActivityLog.MAX_CLASSIFIED + 1);
		sql("insert into visit_AUD (visit_id, REV, REVTYPE, patient_id) select 1, 1000 + x, 0, %d from system_range(1, %d)",
		    PATIENT, ActivityLog.MAX_CLASSIFIED + 1);
		
		try {
			log.list(filter("other", null, null, null, null, null));
			fail("expected the kind filter to be refused");
		}
		catch (Rows.TooMany expected) {}
		assertEquals(ActivityLog.MAX_CLASSIFIED + 4, log.list(new ActivityLog.Filter()).totalCount);
	}
	
	@Test
	public void leavesOutARevisionThatOnlyTouchesTheServerSetup() {
		ActivityLog.Page page = log.list(new ActivityLog.Filter());
		
		assertEquals(3, page.totalCount);
		for (ActivityLog.Entry entry : page.results) {
			assertTrue(entry.revisionId != SETUP_ONLY);
		}
		assertNull(log.details(SETUP_ONLY));
	}
	
	@Test
	public void listsNewestFirstAndPages() {
		ActivityLog.Filter filter = new ActivityLog.Filter();
		filter.startIndex = 1;
		filter.limit = 1;
		
		ActivityLog.Page page = log.list(filter);
		
		assertEquals(3, page.totalCount);
		assertEquals(1, page.results.size());
		assertEquals(Integer.valueOf(EDITED), page.results.get(0).revisionId);
	}
	
	@Test
	public void filtersByKindUserPatientFormAndDates() {
		registration();
		userCreated();
		
		assertEquals(Integer.valueOf(SAVED), only(filter("saved", null, null, null, null, null)).revisionId);
		assertEquals(Integer.valueOf(USER_CREATED), only(filter("user", null, null, null, null, null)).revisionId);
		assertEquals(4, log.list(filter(null, "clinician", null, null, null, null)).totalCount);
		assertEquals(0, log.list(filter(null, "nobody", null, null, null, null)).totalCount);
		assertEquals(4, log.list(filter(null, null, encounter.getPatient().getUuid(), null, null, null)).totalCount);
		assertEquals(3, log.list(filter(null, null, null, form.getUuid(), null, null)).totalCount);
		assertEquals(4,
		    log.list(filter(null, null, null, null, LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 21))).totalCount);
		assertEquals(0, log.list(filter(null, null, null, null, LocalDate.of(2026, 10, 22), null)).totalCount);
	}
	
	private ActivityLog.Filter filter(String kind, String username, String patient, String form, LocalDate from,
	        LocalDate to) {
		ActivityLog.Filter filter = new ActivityLog.Filter();
		filter.kind = kind;
		filter.username = username;
		filter.patientUuid = patient;
		filter.formUuid = form;
		filter.from = from;
		filter.to = to;
		return filter;
	}
	
	private ActivityLog.Entry only(ActivityLog.Filter filter) {
		ActivityLog.Page page = log.list(filter);
		assertEquals(1, page.totalCount);
		return page.results.get(0);
	}
	
	/**
	 * A registration as the registration page saves it: the person, patient, name, identifier,
	 * attributes and enrolment.
	 */
	private void registration() {
		PersonAttributeType clinic = attributeType("Primary Care Clinic", "org.openmrs.Location");
		PersonAttributeType consent = attributeType("Registry Consent", "org.openmrs.Concept");
		Context.getAdministrationService().setGlobalProperty(ActivityLog.CLINIC_ATTRIBUTE_TYPES_PROPERTY, clinic.getUuid());
		Context.getAdministrationService().setGlobalProperty(StudyEnrolmentRules.CONSENT_PROPERTY, consent.getUuid());
		Context.getAdministrationService().setGlobalProperty(StudyEnrolmentRules.YES_PROPERTY, yes.getUuid());
		Program program = Context.getProgramWorkflowService().getProgram(1);
		revision(REGISTERED, "2026-10-21 08:58:00", clinician.getUserId());
		sql("insert into person_AUD (person_id, REV, REVTYPE, gender, birthdate, dead, voided) values (%d, %d, 0, 'F', '1976-08-25 00:00:00', false, false)",
		    PATIENT, REGISTERED);
		sql("insert into patient_AUD (patient_id, REV, voided) values (%d, %d, false)", PATIENT, REGISTERED);
		sql("insert into person_name_AUD (person_name_id, REV, REVTYPE, preferred, given_name, middle_name, family_name, voided, person_id)"
		        + " values (7, %d, 0, true, 'Collet', 'Test', 'Chebaskwony', false, %d)",
		    REGISTERED, PATIENT);
		sql("insert into patient_identifier_AUD (patient_identifier_id, REV, REVTYPE, identifier, preferred, voided, patient_id, identifier_type)"
		        + " values (4, %d, 0, '6TS-4', true, false, %d, 1)",
		    REGISTERED, PATIENT);
		sql("insert into person_attribute_AUD (person_attribute_id, REV, REVTYPE, value, voided, person_id, person_attribute_type_id)"
		        + " values (801, %d, 0, '1', false, %d, %d)",
		    REGISTERED, PATIENT, clinic.getPersonAttributeTypeId());
		sql("insert into person_attribute_AUD (person_attribute_id, REV, REVTYPE, value, voided, person_id, person_attribute_type_id)"
		        + " values (802, %d, 0, '%d', false, %d, %d)",
		    REGISTERED, yes.getConceptId(), PATIENT, consent.getPersonAttributeTypeId());
		sql("insert into patient_program_AUD (patient_program_id, REV, REVTYPE, date_enrolled, voided, program_id, patient_id)"
		        + " values (701, %d, 0, '2026-10-21 00:00:00', false, %d, %d)",
		    REGISTERED, program.getProgramId(), PATIENT);
	}
	
	private void userCreated() {
		revision(USER_CREATED, "2026-10-20 17:05:00", 1);
		sql("insert into users_AUD (user_id, REV, REVTYPE, retired, username, system_id, person_id) values (%d, %d, 0, false, 'clinician', '2-4', %d)",
		    clinician.getUserId(), USER_CREATED, clinician.getPerson().getPersonId());
		sql("insert into user_role_AUD (REV, user_id, role, REVTYPE) values (%d, %d, 'Organizational: ACT Clinician', 0)",
		    USER_CREATED, clinician.getUserId());
	}
	
	private static void revision(int id, String time, int userId) {
		sql("insert into revision_entity (id, timestamp, changedBy, changedOn) values (%d, %d, %d, '%s')", id,
		    Timestamp.valueOf(time).getTime(), userId, time);
	}
	
	private void obs(int obsId, int revision, int type, boolean voided, Concept concept, String column, Object value) {
		sql("insert into obs_AUD (obs_id, REV, REVTYPE, person_id, concept_id, encounter_id, voided, %s) values (%d, %d, %d, %d, %d, %d, %s, %s)",
		    column, obsId, revision, type, PATIENT, concept.getConceptId(), encounter.getEncounterId(), voided, value);
	}
	
	private static void sql(String statement, Object... args) {
		Context.getAdministrationService().executeSQL(String.format(statement, args), false);
	}
	
	private static ActivityLog.Entry entry(ActivityLog.Page page, int revisionId) {
		for (ActivityLog.Entry entry : page.results) {
			if (entry.revisionId != null && entry.revisionId == revisionId) {
				return entry;
			}
		}
		fail("no entry for revision " + revisionId + " among " + page.results.size());
		return null;
	}
	
	private static Concept concept(String name, String datatype) {
		Concept concept = "Numeric".equals(datatype) ? new ConceptNumeric() : new Concept();
		concept.setFullySpecifiedName(new ConceptName(name, Context.getLocale()));
		concept.setDatatype(Context.getConceptService().getConceptDatatypeByName(datatype));
		concept.setConceptClass(Context.getConceptService().getConceptClass(1));
		Context.getConceptService().saveConcept(concept);
		assertNotNull(concept.getConceptId());
		return concept;
	}
	
	private static PersonAttributeType attributeType(String name, String format) {
		PersonAttributeType type = new PersonAttributeType();
		type.setName(name);
		type.setFormat(format);
		Context.getPersonService().savePersonAttributeType(type);
		return type;
	}
}
