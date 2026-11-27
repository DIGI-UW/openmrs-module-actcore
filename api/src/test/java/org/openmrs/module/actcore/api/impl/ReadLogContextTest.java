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
		
		assertEquals(5, page.getTotalCount());
		ReadRow folded = page.getResults().get(3);
		assertEquals("communityClinician:" + collet.getUuid() + ":1", folded.getId());
		assertEquals(Timestamp.valueOf("2026-10-21 07:41:20"), folded.getFrom());
		assertEquals(Timestamp.valueOf("2026-10-21 07:48:17"), folded.getTo());
		assertEquals(3, folded.getReads().size());
		assertEquals("the patient record", folded.getReads().get(0).getDescription());
		assertEquals("2 encounters", folded.getReads().get(1).getDescription());
		assertEquals("9 observations", folded.getReads().get(2).getDescription());
		assertEquals("Joseph Odongo", folded.getUser().getDisplay());
		assertEquals("Collet Test Chebaskwony", folded.getPatient().getDisplay());
		assertEquals("6TS-4", folded.getPatient().getActId());
		assertEquals("7F6171EB", folded.getSessionId());
		assertEquals("Patient", folded.getReads().get(0).getEntity());
	}
	
	@Test
	public void startsANewRowForAnotherPatientAnotherUserOrALaterHour() {
		ReadPage page = log.getReads(new ReadSearchCriteria());
		
		assertEquals(Timestamp.valueOf("2026-10-21 09:00:00"), page.getResults().get(0).getFrom());
		assertEquals(1, page.getResults().get(0).getReads().size());
		assertEquals(anet.getUuid(), page.getResults().get(1).getPatient().getUuid());
		assertEquals("dataClerk", page.getResults().get(2).getUser().getUsername());
		assertEquals("dataClerk", page.getResults().get(2).getUser().getDisplay());
	}
	
	@Test
	public void labelsEachSessionWithoutGivingItsId() {
		ReadPage page = log.getReads(new ReadSearchCriteria());
		
		for (ReadRow row : page.getResults()) {
			assertEquals("dataClerk".equals(((ReadLog.Row) row).username) ? "ABDBC2B5" : "7F6171EB", row.getSessionId());
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
		
		assertEquals(6, page.getTotalCount());
		assertEquals("no patient found", page.getResults().get(0).getReads().get(0).getDescription());
	}
	
	@Test
	public void listsAReadOfSeveralPatientsAsASearchWithNoPatient() {
		ReadPage page = log.getReads(new ReadSearchCriteria());
		ReadRow search = page.getResults().get(4);
		
		assertNull(search.getPatient());
		assertEquals("a search: 2 patients", search.getReads().get(0).getDescription());
		assertEquals("communityClinician:-:7", search.getId());
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
		
		assertEquals(Boolean.TRUE, page.getResults().get(0).getSameClinicAsUser());
		assertEquals("Unknown Location", page.getResults().get(0).getPatient().getPrimaryCareClinic());
		assertEquals(Boolean.FALSE, page.getResults().get(1).getSameClinicAsUser());
		assertNull(page.getResults().get(2).getSameClinicAsUser());
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
		
		assertEquals(6, page.getTotalCount());
		assertEquals(3, page.getResults().get(0).getReads().size());
		assertEquals("1 list", page.getResults().get(0).getReads().get(0).getDescription());
		assertNull(page.getResults().get(0).getPatient());
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
			assertEquals(4, log.getReads(filter(null, "6TS-4", null, null)).getTotalCount());
			assertEquals(4, log.getReads(filter(null, "chebask", null, null)).getTotalCount());
		}
		finally {
			Context.removeProxyPrivilege(ActCoreConstants.AUDITLOGWEB_VIEW_READ_AUDIT_LOGS);
		}
		
		assertEquals(before,
		    ((Number) AuditTables.select("select count(*) as n from read_audit_log").get(0).get("n")).intValue());
	}
	
	@Test
	public void filtersByUserPatientAndDatesAndPages() {
		assertEquals(1, log.getReads(filter("dataClerk", null, null, null)).getTotalCount());
		assertEquals(4, log.getReads(filter(null, collet.getUuid(), null, null)).getTotalCount());
		assertEquals(4, log.getReads(filter(null, "6TS-4", null, null)).getTotalCount());
		assertEquals(2, log.getReads(filter(null, "7TU-8", null, null)).getTotalCount());
		assertEquals(0, log.getReads(filter(null, "nobody at all", null, null)).getTotalCount());
		assertEquals(1,
		    log.getReads(filter(null, null, LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 20))).getTotalCount());
		ReadSearchCriteria page = filter("communityClinician", null, null, null);
		page.setStartIndex(3);
		page.setLimit(10);
		assertEquals(4, log.getReads(page).getTotalCount());
		assertEquals(1, log.getReads(page).getResults().size());
	}
	
	@Test
	public void takesThePercentAndUnderscoreOfAPatientSearchLiterally() {
		assertEquals(0, log.getReads(filter(null, "%", null, null)).getTotalCount());
		assertEquals(0, log.getReads(filter(null, "_", null, null)).getTotalCount());
	}
	
	@Test
	public void refusesAPatientSearchMatchingMoreThanItCanList() {
		for (int i = 0; i < ReadLog.PATIENT_MATCHES; i++) {
			patientNamed("Grace");
		}
		assertEquals(0, log.getReads(filter(null, "grace", null, null)).getTotalCount());
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
		
		assertEquals(1, page.getTotalCount());
		assertEquals(bulk, page.getResults().get(0).getReads().size());
		assertEquals(4, log.getReads(filter("communityClinician", null, null, null)).getTotalCount());
		assertEquals(log.getReads(filter("communityClinician", null, null, null)).getTotalCount() + 1
		        + log.getReads(filter("dataClerk", null, null, null)).getTotalCount(),
		    all.getTotalCount());
	}
	
	@Test
	public void listsADaysReadsWhenIdsAreOutOfTimeOrder() {
		read(300, "lateReader", clinician, "2026-10-22 08:00:00", "Patient", "C3", collet, 1);
		read(10, "lateReader", clinician, "2026-10-22 18:00:00", "Patient", "C3", anet, 1);
		read(20, "lateReader", clinician, "2026-10-23 08:00:00", "Patient", "C3", collet, 1);
		read(400, "lateReader", clinician, "2026-10-21 23:00:00", "Patient", "C3", anet, 1);
		
		ReadPage day = log.getReads(filter("lateReader", null, LocalDate.of(2026, 10, 22), LocalDate.of(2026, 10, 22)));
		
		assertEquals(2, day.getTotalCount());
		assertEquals("lateReader:" + anet.getUuid() + ":10", day.getResults().get(0).getId());
		assertEquals("lateReader:" + collet.getUuid() + ":300", day.getResults().get(1).getId());
	}
	
	@Test
	public void spansOnlyTheIdsWithinTheDates() {
		read(3 * ReadLog.READ_SPAN + 1, "spanReader", clinician, "2026-10-22 08:00:00", "Patient", "D4", collet, 1);
		read(ReadLog.READ_SPAN, "spanReader", clinician, "2020-01-01 08:00:00", "Patient", "D4", collet, 1);
		read(10 * ReadLog.READ_SPAN, "spanReader", clinician, "2026-10-30 08:00:00", "Patient", "D4", collet, 1);
		queryStatistics(false);
		queryStatistics(true);
		
		ReadPage day = log.getReads(filter(null, null, LocalDate.of(2026, 10, 22), LocalDate.of(2026, 10, 22)));
		
		assertEquals(1, day.getTotalCount());
		assertEquals(1,
		    Rows.integer(AuditTables.select("select sum(execution_count) as spans"
		            + " from information_schema.query_statistics where sql_statement like '%l.id > ?%'").get(0),
		        "spans").intValue());
		queryStatistics(false);
	}
	
	@Test
	public void listsNothingForADayWithNoReads() {
		ReadPage day = log.getReads(filter(null, null, LocalDate.of(2030, 1, 1), LocalDate.of(2030, 1, 1)));
		
		assertEquals(0, day.getTotalCount());
		assertEquals(0, day.getResults().size());
	}
	
	private static ReadSearchCriteria filter(String username, String patient, LocalDate from, LocalDate to) {
		ReadSearchCriteria filter = new ReadSearchCriteria();
		filter.setUsername(username);
		filter.setPatient(patient);
		filter.setFrom(from);
		filter.setTo(to);
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
