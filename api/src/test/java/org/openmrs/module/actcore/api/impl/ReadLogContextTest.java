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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.UUID;

import org.hibernate.SessionFactory;
import org.junit.Before;
import org.junit.Test;
import org.openmrs.Patient;
import org.openmrs.Person;
import org.openmrs.PersonAttribute;
import org.openmrs.PersonAttributeType;
import org.openmrs.PersonName;
import org.openmrs.Role;
import org.openmrs.User;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.ActCoreConstants;
import org.openmrs.module.actcore.api.AuditLogService;
import org.openmrs.module.actcore.api.TooManyResultsException;
import org.openmrs.module.actcore.api.search.ReadSearchCriteria;
import org.openmrs.module.actcore.model.ReadPage;
import org.openmrs.module.actcore.model.ReadRow;
import org.openmrs.test.BaseModuleContextSensitiveTest;

public class ReadLogContextTest extends BaseModuleContextSensitiveTest {
	
	private AuditLogService log;
	
	private Patient collet;
	
	private Patient anet;
	
	private User clinician;
	
	@Before
	public void setUp() {
		AuditTables.create();
		log = Context.getService(AuditLogService.class);
		collet = Context.getPatientService().getPatient(7);
		anet = Context.getPatientService().getPatient(8);
		Person person = new Person();
		person.addName(new PersonName("Joseph", null, "Odongo"));
		person.setGender("M");
		clinician = new User(person);
		clinician.setUsername("communityClinician");
		Context.getUserService().createUser(clinician, "Clinician123");
		
		read(1, "communityClinician", clinician, "2026-10-21 07:41:20", "Patient", "ED97E5A1", collet, 1);
		read(2, "communityClinician", clinician, "2026-10-21 07:44:00", "Encounter", "ED97E5A1", collet, 2);
		read(3, "communityClinician", clinician, "2026-10-21 07:48:17", "Obs", "ED97E5A1", collet, 9);
		read(4, "communityClinician", clinician, "2026-10-21 07:50:00", "Patient", "ED97E5A1", anet, 1);
		read(5, "communityClinician", clinician, "2026-10-21 09:00:00", "Patient", "ED97E5A1", collet, 1);
		read(6, "dataClerk", null, "2026-10-21 07:45:00", "Patient", "B2", collet, 1);
		sql("insert into read_audit_log (id, entity_name, read_success, username, user_uuid, event_time, session_id, uuid)"
		        + " values (7, 'Patient', true, 'communityClinician', '%s', '2026-10-20 16:02:00', 'ED97E5A1', '%s')",
		    clinician.getUuid(), UUID.randomUUID());
		target(7, collet);
		target(7, anet);
	}
	
	@Test
	public void foldsAUsersReadsOfOnePatientWithinAnHourIntoOneRow() {
		ReadPage page = log.getReads(new ReadSearchCriteria());
		
		assertEquals(5, page.totalCount);
		ReadRow folded = page.results.get(3);
		assertEquals("communityClinician:" + collet.getUuid() + ":1", folded.id);
		assertEquals(Timestamp.valueOf("2026-10-21 07:41:20"), folded.from);
		assertEquals(Timestamp.valueOf("2026-10-21 07:48:17"), folded.to);
		assertEquals(3, folded.reads.size());
		assertEquals("the patient record", folded.reads.get(0).description);
		assertEquals("2 encounters", folded.reads.get(1).description);
		assertEquals("9 observations", folded.reads.get(2).description);
		assertEquals("Joseph Odongo", folded.user.display);
		assertEquals("Collet Test Chebaskwony", folded.patient.display);
		assertEquals("6TS-4", folded.patient.actId);
		assertEquals("7F6171EB", folded.sessionId);
		assertEquals("Patient", folded.reads.get(0).entity);
	}
	
	@Test
	public void startsANewRowForAnotherPatientAnotherUserOrALaterHour() {
		ReadPage page = log.getReads(new ReadSearchCriteria());
		
		assertEquals(Timestamp.valueOf("2026-10-21 09:00:00"), page.results.get(0).from);
		assertEquals(1, page.results.get(0).reads.size());
		assertEquals(anet.getUuid(), page.results.get(1).patient.uuid);
		assertEquals("dataClerk", page.results.get(2).user.username);
		assertEquals("dataClerk", page.results.get(2).user.display);
	}
	
	@Test
	public void labelsEachSessionWithoutGivingItsId() {
		ReadPage page = log.getReads(new ReadSearchCriteria());
		
		for (ReadRow row : page.results) {
			assertEquals("dataClerk".equals(((ReadLog.Row) row).username) ? "ABDBC2B5" : "7F6171EB", row.sessionId);
		}
	}
	
	@Test
	public void leavesOutAReadOfMetadataSuchAsAnEncounterType() {
		sql("insert into read_audit_log (id, entity_name, read_success, username, user_uuid, event_time, uuid)"
		        + " values (8, 'EncounterType', true, 'communityClinician', '%s', '2026-10-21 09:30:00', '%s')",
		    clinician.getUuid(), UUID.randomUUID());
		sql("insert into read_audit_entity_metadata (read_audit_id, entity_uuid) values (8, '%s')", UUID.randomUUID());
		sql("insert into read_audit_log (id, entity_name, read_success, username, user_uuid, event_time, uuid)"
		        + " values (9, 'Patient', true, 'communityClinician', '%s', '2026-10-21 09:31:00', '%s')",
		    clinician.getUuid(), UUID.randomUUID());
		
		ReadPage page = log.getReads(new ReadSearchCriteria());
		
		assertEquals(6, page.totalCount);
		assertEquals("no patient found", page.results.get(0).reads.get(0).description);
	}
	
	@Test
	public void listsAReadOfSeveralPatientsAsASearchWithNoPatient() {
		ReadPage page = log.getReads(new ReadSearchCriteria());
		ReadRow search = page.results.get(4);
		
		assertNull(search.patient);
		assertEquals("a search: 2 patients", search.reads.get(0).description);
		assertEquals("communityClinician:-:7", search.id);
	}
	
	@Test
	public void saysWhetherThePatientIsAtTheUsersClinic() {
		PersonAttributeType clinicType = new PersonAttributeType();
		clinicType.setName("Primary Care Clinic");
		clinicType.setFormat("org.openmrs.Location");
		Context.getPersonService().savePersonAttributeType(clinicType);
		Context.getAdministrationService().setGlobalProperty(ActCoreConstants.GP_LOGS_CLINIC_ATTRIBUTE_TYPES,
		    clinicType.getUuid());
		collet.addAttribute(new PersonAttribute(clinicType, "1"));
		Context.getPatientService().savePatient(collet);
		anet.addAttribute(new PersonAttribute(clinicType, "2"));
		Context.getPatientService().savePatient(anet);
		clinician.setUserProperty(ActCoreConstants.USER_PROPERTY_CLINICS,
		    Context.getLocationService().getLocation(1).getUuid());
		Context.getUserService().saveUser(clinician);
		Context.flushSession();
		
		ReadPage page = log.getReads(new ReadSearchCriteria());
		
		assertEquals(Boolean.TRUE, page.results.get(0).sameClinicAsUser);
		assertEquals("Unknown Location", page.results.get(0).patient.primaryCareClinic);
		assertEquals(Boolean.FALSE, page.results.get(1).sameClinicAsUser);
		assertNull(page.results.get(2).sameClinicAsUser);
	}
	
	@Test
	public void foldsReadsOfNoSinglePatientPerUserAndEntity() {
		for (int i = 0; i < 3; i++) {
			sql("insert into read_audit_log (id, entity_name, read_success, username, user_uuid, event_time, uuid)"
			        + " values (%d, 'Cohort', true, 'communityClinician', '%s', '2026-10-21 09:3%d:00', '%s')",
			    10 + i, clinician.getUuid(), i, UUID.randomUUID());
			sql("insert into read_audit_entity_metadata (read_audit_id, entity_uuid) values (%d, '%s')", 10 + i,
			    UUID.randomUUID());
		}
		
		ReadPage page = log.getReads(new ReadSearchCriteria());
		
		assertEquals(6, page.totalCount);
		assertEquals(3, page.results.get(0).reads.size());
		assertEquals("1 list", page.results.get(0).reads.get(0).description);
		assertNull(page.results.get(0).patient);
	}
	
	@Test
	public void findsThePatientFilterWithoutThePatientServiceSoNoReadIsRecorded() {
		Person person = new Person();
		person.addName(new PersonName("Log", null, "Reader"));
		person.setGender("F");
		User reader = new User(person);
		reader.setUsername("reader");
		reader.addRole(Context.getUserService().saveRole(new Role("no privileges")));
		Context.getUserService().createUser(reader, "Reader123");
		Context.logout();
		Context.authenticate("reader", "Reader123");
		int before = ((Number) AuditTables.select("select count(*) as n from read_audit_log").get(0).get("n")).intValue();
		
		// The privilege the log asks for, and not Get Patients, which the patient service would.
		Context.addProxyPrivilege(ActCoreConstants.AUDITLOGWEB_VIEW_READ_AUDIT_LOGS);
		try {
			assertEquals(4, log.getReads(filter(null, "6TS-4", null, null)).totalCount);
			assertEquals(4, log.getReads(filter(null, "chebask", null, null)).totalCount);
		}
		finally {
			Context.removeProxyPrivilege(ActCoreConstants.AUDITLOGWEB_VIEW_READ_AUDIT_LOGS);
		}
		
		assertEquals(before,
		    ((Number) AuditTables.select("select count(*) as n from read_audit_log").get(0).get("n")).intValue());
	}
	
	@Test
	public void filtersByUserPatientAndDatesAndPages() {
		assertEquals(1, log.getReads(filter("dataClerk", null, null, null)).totalCount);
		assertEquals(4, log.getReads(filter(null, collet.getUuid(), null, null)).totalCount);
		assertEquals(4, log.getReads(filter(null, "6TS-4", null, null)).totalCount);
		assertEquals(2, log.getReads(filter(null, "7TU-8", null, null)).totalCount);
		assertEquals(0, log.getReads(filter(null, "nobody at all", null, null)).totalCount);
		assertEquals(1, log.getReads(filter(null, null, LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 20))).totalCount);
		ReadSearchCriteria page = filter("communityClinician", null, null, null);
		page.startIndex = 3;
		page.limit = 10;
		assertEquals(4, log.getReads(page).totalCount);
		assertEquals(1, log.getReads(page).results.size());
	}
	
	@Test
	public void takesThePercentAndUnderscoreOfAPatientSearchLiterally() {
		assertEquals(0, log.getReads(filter(null, "%", null, null)).totalCount);
		assertEquals(0, log.getReads(filter(null, "_", null, null)).totalCount);
	}
	
	@Test
	public void refusesAPatientSearchMatchingMoreThanItCanList() {
		for (int i = 0; i < ReadLog.PATIENT_MATCHES; i++) {
			patientNamed("Grace");
		}
		assertEquals(0, log.getReads(filter(null, "grace", null, null)).totalCount);
		patientNamed("Grace");
		
		try {
			log.getReads(filter(null, "grace", null, null));
			fail("expected the search to be refused");
		}
		catch (TooManyResultsException expected) {
			// A ValidationException, so the REST layer answers it with 400 and this as the global error.
			assertEquals(expected.getMessage(), expected.getErrors().getGlobalError().getDefaultMessage());
		}
		try {
			log.getReads(filter(null, "grace", LocalDate.of(2030, 1, 1), LocalDate.of(2030, 1, 1)));
			fail("expected the search to be refused on a day with no reads");
		}
		catch (TooManyResultsException expected) {}
	}
	
	@Test
	public void foldsReadsAcrossIdSpansWithoutACap() {
		int bulk = 5 * ReadLog.READ_SPAN + 1;
		sql("insert into read_audit_log (entity_name, read_success, username, user_uuid, event_time, uuid)"
		        + " select 'Patient', true, 'bulk', 'bulk-uuid', '2026-10-21 10:00:00', random_uuid()"
		        + " from system_range(1, %d)",
		    bulk);
		
		ReadPage all = log.getReads(new ReadSearchCriteria());
		ReadPage page = log.getReads(filter("bulk", null, null, null));
		
		assertEquals(1, page.totalCount);
		assertEquals(bulk, page.results.get(0).reads.size());
		assertEquals(4, log.getReads(filter("communityClinician", null, null, null)).totalCount);
		assertEquals(log.getReads(filter("communityClinician", null, null, null)).totalCount + 1
		        + log.getReads(filter("dataClerk", null, null, null)).totalCount,
		    all.totalCount);
	}
	
	@Test
	public void listsADaysReadsWhenIdsAreOutOfTimeOrder() {
		read(300, "lateReader", clinician, "2026-10-22 08:00:00", "Patient", "C3", collet, 1);
		read(10, "lateReader", clinician, "2026-10-22 18:00:00", "Patient", "C3", anet, 1);
		read(20, "lateReader", clinician, "2026-10-23 08:00:00", "Patient", "C3", collet, 1);
		read(400, "lateReader", clinician, "2026-10-21 23:00:00", "Patient", "C3", anet, 1);
		
		ReadPage day = log.getReads(filter("lateReader", null, LocalDate.of(2026, 10, 22), LocalDate.of(2026, 10, 22)));
		
		assertEquals(2, day.totalCount);
		assertEquals("lateReader:" + anet.getUuid() + ":10", day.results.get(0).id);
		assertEquals("lateReader:" + collet.getUuid() + ":300", day.results.get(1).id);
	}
	
	@Test
	public void spansOnlyTheIdsWithinTheDates() {
		read(3 * ReadLog.READ_SPAN + 1, "spanReader", clinician, "2026-10-22 08:00:00", "Patient", "D4", collet, 1);
		read(ReadLog.READ_SPAN, "spanReader", clinician, "2020-01-01 08:00:00", "Patient", "D4", collet, 1);
		read(10 * ReadLog.READ_SPAN, "spanReader", clinician, "2026-10-30 08:00:00", "Patient", "D4", collet, 1);
		queryStatistics(false);
		queryStatistics(true);
		
		ReadPage day = log.getReads(filter(null, null, LocalDate.of(2026, 10, 22), LocalDate.of(2026, 10, 22)));
		
		assertEquals(1, day.totalCount);
		assertEquals(1,
		    Rows.integer(AuditTables.select("select sum(execution_count) as spans"
		            + " from information_schema.query_statistics where sql_statement like '%l.id > ?%'").get(0),
		        "spans").intValue());
		queryStatistics(false);
	}
	
	@Test
	public void listsNothingForADayWithNoReads() {
		ReadPage day = log.getReads(filter(null, null, LocalDate.of(2030, 1, 1), LocalDate.of(2030, 1, 1)));
		
		assertEquals(0, day.totalCount);
		assertEquals(0, day.results.size());
	}
	
	private static ReadSearchCriteria filter(String username, String patient, LocalDate from, LocalDate to) {
		ReadSearchCriteria filter = new ReadSearchCriteria();
		filter.username = username;
		filter.patient = patient;
		filter.from = from;
		filter.to = to;
		return filter;
	}
	
	private static void patientNamed(String given) {
		Person person = new Person();
		person.addName(new PersonName(given, null, "Mukasa"));
		person.setGender("F");
		Context.getPersonService().savePerson(person);
		Context.flushSession();
		sql("insert into patient (patient_id, creator, date_created, voided) values (%d, 1, '2026-10-21', false)",
		    person.getPersonId());
	}
	
	private static void read(int id, String username, User user, String time, String entity, String session, Patient patient,
	        int targets) {
		sql("insert into read_audit_log (id, entity_name, read_success, username, user_uuid, event_time, session_id,"
		        + " ip_address, user_agent, uuid) values (%d, '%s', true, '%s', '%s', '%s', '%s', '172.21.0.1', 'Chrome', '%s')",
		    id, entity, username, user == null ? "no-such-user" : user.getUuid(), time, session, UUID.randomUUID());
		for (int i = 0; i < targets; i++) {
			target(id, patient);
		}
	}
	
	private static void target(int readId, Patient patient) {
		sql("insert into read_audit_entity_metadata (read_audit_id, entity_uuid, patient_uuid, patient_name) values (%d, '%s', '%s', '%s')",
		    readId, UUID.randomUUID(), patient.getUuid(), patient.getPersonName().getFullName());
	}
	
	/**
	 * H2's per-statement execution counts; turning them off clears them. Set on a connection of its
	 * own, as a set commits the test's transaction.
	 */
	private static void queryStatistics(boolean on) {
		Context.getRegisteredComponents(SessionFactory.class).get(0).getCurrentSession().doWork(test -> {
			try (Connection connection = DriverManager.getConnection(test.getMetaData().getURL(), "sa", "");
			        Statement statement = connection.createStatement()) {
				statement.execute("set query_statistics " + on);
			}
		});
	}
	
	private static void sql(String statement, Object... args) {
		Context.getAdministrationService().executeSQL(String.format(statement, args), false);
	}
}
