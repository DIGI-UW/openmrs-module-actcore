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
import static org.junit.Assert.assertNull;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.UUID;

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
import org.openmrs.module.actcore.clinics.UserClinics;
import org.openmrs.test.BaseModuleContextSensitiveTest;

public class ReadLogContextTest extends BaseModuleContextSensitiveTest {
	
	private final ReadLog log = new ReadLog();
	
	private Patient collet;
	
	private Patient anet;
	
	private User clinician;
	
	@Before
	public void setUp() {
		AuditTables.create();
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
		ReadLog.Page page = log.list(new ReadLog.Filter());
		
		assertEquals(5, page.totalCount);
		ReadLog.Row folded = page.results.get(3);
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
		assertEquals("ED97E5A1", folded.sessionId);
		assertEquals("Patient", folded.reads.get(0).entity);
	}
	
	@Test
	public void startsANewRowForAnotherPatientAnotherUserOrALaterHour() {
		ReadLog.Page page = log.list(new ReadLog.Filter());
		
		assertEquals(Timestamp.valueOf("2026-10-21 09:00:00"), page.results.get(0).from);
		assertEquals(1, page.results.get(0).reads.size());
		assertEquals(anet.getUuid(), page.results.get(1).patient.uuid);
		assertEquals("dataClerk", page.results.get(2).user.username);
		assertEquals("dataClerk", page.results.get(2).user.display);
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
		
		ReadLog.Page page = log.list(new ReadLog.Filter());
		
		assertEquals(6, page.totalCount);
		assertEquals("no patient found", page.results.get(0).reads.get(0).description);
	}
	
	@Test
	public void listsAReadOfSeveralPatientsAsASearchWithNoPatient() {
		ReadLog.Page page = log.list(new ReadLog.Filter());
		ReadLog.Row search = page.results.get(4);
		
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
		Context.getAdministrationService().setGlobalProperty(ActivityLog.CLINIC_ATTRIBUTE_TYPES_PROPERTY,
		    clinicType.getUuid());
		collet.addAttribute(new PersonAttribute(clinicType, "1"));
		Context.getPatientService().savePatient(collet);
		anet.addAttribute(new PersonAttribute(clinicType, "2"));
		Context.getPatientService().savePatient(anet);
		clinician.setUserProperty(UserClinics.PROPERTY, Context.getLocationService().getLocation(1).getUuid());
		Context.getUserService().saveUser(clinician);
		Context.flushSession();
		
		ReadLog.Page page = log.list(new ReadLog.Filter());
		
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
		
		ReadLog.Page page = log.list(new ReadLog.Filter());
		
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
		int before = ((Number) Rows.select("select count(*) as n from read_audit_log").get(0).get("n")).intValue();
		
		assertEquals(4, log.list(filter(null, "6TS-4", null, null)).totalCount);
		assertEquals(4, log.list(filter(null, "chebask", null, null)).totalCount);
		
		assertEquals(before, ((Number) Rows.select("select count(*) as n from read_audit_log").get(0).get("n")).intValue());
	}
	
	@Test
	public void filtersByUserPatientAndDatesAndPages() {
		assertEquals(1, log.list(filter("dataClerk", null, null, null)).totalCount);
		assertEquals(4, log.list(filter(null, collet.getUuid(), null, null)).totalCount);
		assertEquals(4, log.list(filter(null, "6TS-4", null, null)).totalCount);
		assertEquals(2, log.list(filter(null, "7TU-8", null, null)).totalCount);
		assertEquals(0, log.list(filter(null, "nobody at all", null, null)).totalCount);
		assertEquals(1, log.list(filter(null, null, LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 20))).totalCount);
		ReadLog.Filter page = filter("communityClinician", null, null, null);
		page.startIndex = 3;
		page.limit = 10;
		assertEquals(4, log.list(page).totalCount);
		assertEquals(1, log.list(page).results.size());
	}
	
	private static ReadLog.Filter filter(String username, String patient, LocalDate from, LocalDate to) {
		ReadLog.Filter filter = new ReadLog.Filter();
		filter.username = username;
		filter.patient = patient;
		filter.from = from;
		filter.to = to;
		return filter;
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
	
	private static void sql(String statement, Object... args) {
		Context.getAdministrationService().executeSQL(String.format(statement, args), false);
	}
}
