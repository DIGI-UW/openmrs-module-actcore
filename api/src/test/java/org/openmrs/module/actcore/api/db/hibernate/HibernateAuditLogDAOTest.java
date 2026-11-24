/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.api.db.hibernate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.Before;
import org.junit.Test;
import org.openmrs.PersonAttributeType;
import org.openmrs.api.context.Context;
import org.openmrs.api.db.hibernate.DbSessionFactory;
import org.openmrs.module.actcore.logs.AuditTables;
import org.openmrs.module.patientflags.Flag;
import org.openmrs.module.patientflags.api.FlagService;
import org.openmrs.test.BaseModuleContextSensitiveTest;

/**
 * Each named query gives the rows that the SQL the logs used to hand the DAO gives, run on the same
 * data.
 */
public class HibernateAuditLogDAOTest extends BaseModuleContextSensitiveTest {
	
	private static final int PATIENT = 7;
	
	private static final String ADMIN_UUID = "1010d442-e134-11de-babe-001e378eb67e";
	
	private HibernateAuditLogDAO dao;
	
	private String patientUuid;
	
	private String formUuid;
	
	private int encounterId;
	
	private int flagId;
	
	@Before
	public void setUp() {
		AuditTables.create();
		dao = new HibernateAuditLogDAO();
		dao.setSessionFactory(Context.getRegisteredComponent("dbSessionFactory", DbSessionFactory.class));
		patientUuid = Context.getPatientService().getPatient(PATIENT).getUuid();
		formUuid = Context.getFormService().getForm(1).getUuid();
		encounterId = Context.getEncounterService().getEncountersByPatientId(PATIENT).get(0).getEncounterId();
		
		sql("insert into role_role (parent_role, child_role) values ('Provider', 'System Developer')");
		PersonAttributeType clinic = new PersonAttributeType();
		clinic.setName("Primary Care Clinic");
		clinic.setFormat("org.openmrs.Location");
		Context.getPersonService().savePersonAttributeType(clinic);
		sql("insert into person_attribute (person_id, value, person_attribute_type_id, creator, date_created, voided, uuid)"
		        + " values (%d, '1', %d, 1, now(), false, '%s')",
		    PATIENT, clinic.getPersonAttributeTypeId(), UUID.randomUUID());
		sql("insert into user_property (user_id, property, property_value) values (1, 'actcore.clinics', 'abc')");
		sql("insert into person_name (person_id, preferred, given_name, family_name, creator, date_created, voided, uuid)"
		        + " values (%d, true, 'Voided', 'Name', 1, now(), true, '%s')",
		    PATIENT, UUID.randomUUID());
		sql("update person set voided = true where person_id = 6");
		
		revision(10, "2026-10-21 10:52:14", 1);
		revision(11, "2026-10-22 11:10:00", 501);
		sql("insert into encounter_AUD (encounter_id, REV, REVTYPE, uuid, voided, encounter_datetime, form_id, patient_id)"
		        + " values (%d, 10, 0, '%s', false, '2026-10-21 10:52:00', 1, %d)",
		    encounterId, UUID.randomUUID(), PATIENT);
		sql("insert into encounter_AUD (encounter_id, REV, REVTYPE, uuid, voided, encounter_datetime, form_id, patient_id)"
		        + " values (%d, 11, 1, '%s', true, '2026-10-21 10:52:00', 1, %d)",
		    encounterId, UUID.randomUUID(), PATIENT);
		for (int obs = 0; obs < 3; obs++) {
			sql("insert into obs_AUD (obs_id, REV, REVTYPE, person_id, concept_id, encounter_id, voided, value_numeric)"
			        + " values (%d, 10, 0, %d, 5089, %d, false, %d)",
			    900 + obs, PATIENT, encounterId, 60 + obs);
		}
		sql("insert into person_name_AUD (person_name_id, REV, REVTYPE, preferred, given_name, family_name, voided, person_id)"
		        + " values (7, 10, 0, true, 'Collet', 'Chebaskwony', false, %d)",
		    PATIENT);
		sql("insert into patient_identifier_AUD (patient_identifier_id, REV, REVTYPE, identifier, preferred, voided, patient_id)"
		        + " values (4, 10, 0, '6TS-4', true, false, %d)",
		    PATIENT);
		sql("insert into patient_state_AUD (patient_state_id, REV, REVTYPE, voided, patient_program_id, state)"
		        + " values (1, 11, 0, false, 1, 1)");
		
		Flag flag = new Flag();
		flag.setName("RHD prophylaxis not prescribed");
		flag.setCriteria("select p.patient_id from patient p");
		flag.setEvaluator("org.openmrs.module.patientflags.evaluator.SQLFlagEvaluator");
		flag.setMessage("not prescribed");
		flag.setEnabled(Boolean.TRUE);
		Context.getService(FlagService.class).saveFlag(flag);
		flagId = flag.getFlagId();
		dismissal(PATIENT, flagId, 1, "2026-10-21 09:00:00");
		dismissal(PATIENT, flagId, 501, "2026-10-22 09:00:00");
		dismissal(2, flagId, 1, "2026-10-22 09:00:00");
		dismissal(2, 9999, 1, "2026-10-23 09:00:00");
		
		read(1, "Patient", "admin", "2026-10-21 09:00:00", patientUuid);
		read(2, "Cohort", "admin", "2026-10-22 09:00:00", null);
		read(3, "Encounter", null, "2026-10-23 09:00:00", patientUuid);
	}
	
	@Test
	public void getUserIdsByUsername_shouldGiveTheRowsOfItsSql() {
		sameRows(dao.getUserIdsByUsername("admin"),
		    "select user_id from users where username = ?"
		            + " or ((username is null or username = '') and system_id = ?) order by username desc",
		    "admin", "admin");
	}
	
	@Test
	public void getUserById_shouldGiveTheRowsOfItsSql() {
		sameRows(dao.getUserById(1),
		    "select u.user_id, u.uuid, u.username, u.system_id, u.person_id from users u where u.user_id = ?", 1);
	}
	
	@Test
	public void getUserByUuid_shouldGiveTheRowsOfItsSql() {
		sameRows(dao.getUserByUuid(ADMIN_UUID),
		    "select u.user_id, u.uuid, u.username, u.system_id, u.person_id from users u where u.uuid = ?", ADMIN_UUID);
	}
	
	@Test
	public void getUserIdByUuid_shouldGiveTheRowsOfItsSql() {
		sameRows(dao.getUserIdByUuid(ADMIN_UUID), "select user_id from users where uuid = ?", ADMIN_UUID);
	}
	
	@Test
	public void getUserIdsByPerson_shouldGiveTheRowsOfItsSql() {
		sameRows(dao.getUserIdsByPerson(1), "select user_id from users where person_id = ?", 1);
	}
	
	@Test
	public void getUserRoles_shouldGiveTheRowsOfItsSql() {
		sameRows(dao.getUserRoles(1), "select role from user_role where user_id = ?", 1);
	}
	
	@Test
	public void getInheritedRoles_shouldGiveTheRowsOfItsSql() {
		sameRows(dao.getInheritedRoles(1), "select rr.parent_role as role from role_role rr"
		        + " join user_role ur on ur.role = rr.child_role where ur.user_id = ?",
		    1);
	}
	
	@Test
	public void getPersonUuid_shouldGiveTheRowsOfItsSql() {
		sameRows(dao.getPersonUuid(PATIENT), "select uuid from person where person_id = ?", PATIENT);
	}
	
	@Test
	public void getPersonId_shouldGiveTheRowsOfItsSql() {
		sameRows(dao.getPersonId(patientUuid), "select person_id from person where uuid = ?", patientUuid);
	}
	
	@Test
	public void getUnvoidedPersonNames_shouldGiveTheRowsOfItsSql() {
		sameRows(dao.getUnvoidedPersonNames(PATIENT),
		    "select given_name, middle_name, family_name from person_name where person_id = ? and voided = false"
		            + " order by preferred desc, person_name_id",
		    PATIENT);
	}
	
	@Test
	public void getPersonNames_shouldGiveTheRowsOfItsSql() {
		sameRows(dao.getPersonNames(PATIENT),
		    "select given_name, middle_name, family_name from person_name where person_id = ?"
		            + " order by voided, preferred desc, person_name_id",
		    PATIENT);
	}
	
	@Test
	public void getPatientIdentifiers_shouldGiveTheRowsOfItsSql() {
		sameRows(dao.getPatientIdentifiers(PATIENT),
		    "select i.identifier, t.uuid type_uuid from patient_identifier i"
		            + " join patient_identifier_type t on t.patient_identifier_type_id = i.identifier_type"
		            + " where i.patient_id = ? order by i.voided, i.preferred desc, i.patient_identifier_id",
		    PATIENT);
	}
	
	@Test
	public void getLocationAttributes_shouldGiveTheRowsOfItsSql() {
		sameRows(dao.getLocationAttributes(PATIENT),
		    "select a.value, t.uuid as type_uuid from person_attribute a"
		            + " join person_attribute_type t on t.person_attribute_type_id = a.person_attribute_type_id"
		            + " where a.person_id = ? and a.voided = false and t.format = 'org.openmrs.Location'",
		    PATIENT);
	}
	
	@Test
	public void getUserProperty_shouldGiveTheRowsOfItsSql() {
		sameRows(dao.getUserProperty(ADMIN_UUID, "actcore.clinics"),
		    "select p.property_value from user_property p join users u on u.user_id = p.user_id"
		            + " where u.uuid = ? and p.property = ?",
		    ADMIN_UUID, "actcore.clinics");
	}
	
	@Test
	public void getFormId_shouldGiveTheRowsOfItsSql() {
		sameRows(dao.getFormId(formUuid), "select form_id from form where uuid = ?", formUuid);
	}
	
	@Test
	public void getRow_shouldGiveTheRowsOfItsSql() {
		sameRows(dao.getRow("location", "location_id", 1), "select * from location where location_id = ?", 1);
		sameRows(dao.getRow("patient", "patient_id", PATIENT), "select * from patient where patient_id = ?", PATIENT);
	}
	
	@Test
	public void findPatientUuids_shouldGiveTheRowsOfItsSql() {
		String identifier = Context.getPatientService().getPatient(PATIENT).getPatientIdentifier().getIdentifier();
		for (String query : Arrays.asList("chebaskwony", "E", identifier)) {
			String part = "%" + query.toLowerCase().replaceAll("[!%_]", "!$0") + "%";
			sameRows(dao.findPatientUuids(query, 101),
			    "select distinct p.uuid from person p join patient t on t.patient_id = p.person_id and t.voided = false"
			            + " left join patient_identifier i on i.patient_id = p.person_id and i.voided = false"
			            + " left join person_name n on n.person_id = p.person_id and n.voided = false"
			            + " where p.voided = false and (i.identifier = ? or lower(n.given_name) like ? escape '!'"
			            + " or lower(n.middle_name) like ? escape '!' or lower(n.family_name) like ? escape '!') limit 101",
			    query, part, part, part);
		}
	}
	
	@Test
	public void getRevisions_shouldGiveTheRowsOfItsSql() {
		Map<String, String> tables = new LinkedHashMap<String, String>();
		tables.put("encounter_AUD", "patient_id");
		tables.put("obs_AUD", "person_id");
		tables.put("patient_state_AUD", "patient_program_id");
		String select = "select r.id, r.changedOn as rev_time, r.changedBy as rev_user from revision_entity r where 1 = 1";
		String exists = " and (exists (select 1 from encounter_AUD a where a.REV = r.id%s)"
		        + " or exists (select 1 from obs_AUD a where a.REV = r.id%s)"
		        + " or exists (select 1 from patient_state_AUD a where a.REV = r.id%s)) order by r.changedOn desc, r.id desc";
		
		sameRows(dao.getRevisions(tables, null, null, null, null, null, null), select + String.format(exists, "", "", ""));
		sameRows(dao.getRevisions(tables, 1, 501, LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 21), null, null),
		    select + " and r.changedBy = ? and (r.changedBy is null or r.changedBy <> ?) and r.changedOn >= ?"
		            + " and r.changedOn < ?" + String.format(exists, "", "", ""),
		    1, 501, Timestamp.valueOf("2026-10-21 00:00:00"), Timestamp.valueOf("2026-10-22 00:00:00"));
		sameRows(dao.getRevisions(tables, null, null, null, null, PATIENT, null),
		    select + String.format(exists, " and a.patient_id = ?", " and a.person_id = ?",
		        " and a.patient_program_id in (select patient_program_id from patient_program where patient_id = ?)"),
		    PATIENT, PATIENT, PATIENT);
		tables.remove("patient_state_AUD");
		sameRows(dao.getRevisions(tables, null, null, null, null, PATIENT, 1),
		    select + " and (exists (select 1 from encounter_AUD a where a.REV = r.id and a.patient_id = ? and a.form_id = ?)"
		            + " or exists (select 1 from obs_AUD a where a.REV = r.id and a.person_id = ?"
		            + " and a.encounter_id in (select encounter_id from encounter where form_id = ?)))"
		            + " order by r.changedOn desc, r.id desc",
		    PATIENT, 1, PATIENT, 1);
	}
	
	@Test
	public void getRevisionRows_shouldGiveTheRowsOfItsSql() {
		sameRows(dao.getRevisionRows("encounter_AUD", Arrays.asList(10, 11)),
		    "select a.*, r.changedOn as rev_time, r.changedBy as rev_user from encounter_AUD"
		            + " a join revision_entity r on r.id = a.REV where a.REV in (?, ?)",
		    10, 11);
		sameRows(dao.getRevisionRows("obs_AUD", Collections.singletonList(10)),
		    "select a.REV, a.REVTYPE, a.encounter_id, a.person_id, count(*) as n, r.changedOn as rev_time,"
		            + " r.changedBy as rev_user from obs_AUD a join revision_entity r on r.id = a.REV where a.REV in (?)"
		            + " group by a.REV, a.REVTYPE, a.encounter_id, a.person_id, r.changedOn, r.changedBy",
		    10);
	}
	
	@Test
	public void getPreviousAuditRows_shouldGiveTheRowsOfItsSql() {
		sameRows(dao.getPreviousAuditRows("encounter_AUD", "encounter_id", encounterId, 11),
		    "select * from encounter_AUD where encounter_id = ? and REV < ? order by REV desc", encounterId, 11);
	}
	
	@Test
	public void getAuditedObs_shouldGiveTheRowsOfItsSql() {
		sameRows(dao.getAuditedObs(10, encounterId),
		    "select * from obs_AUD where REV = ? and encounter_id = ? order by obs_id", 10, encounterId);
	}
	
	@Test
	public void getAuditedPersonNames_shouldGiveTheRowsOfItsSql() {
		sameRows(dao.getAuditedPersonNames(PATIENT, Integer.MAX_VALUE),
		    "select given_name, middle_name, family_name from person_name_AUD where person_id = ? and REV <= ?"
		            + " order by REV desc",
		    PATIENT, Integer.MAX_VALUE);
	}
	
	@Test
	public void getAuditedIdentifiers_shouldGiveTheRowsOfItsSql() {
		sameRows(dao.getAuditedIdentifiers(PATIENT, 10),
		    "select identifier from patient_identifier_AUD where patient_id = ? and REV <= ? order by preferred desc, REV desc",
		    PATIENT, 10);
	}
	
	@Test
	public void getFlagDismissals_shouldGiveTheRowsOfTheSqlItReplaces() {
		String select = "select f.patient_id, f.dismissed_by, f.date_dismissed, g.name from actcore_flag_dismissal f"
		        + " left join patientflags_flag g on g.flag_id = f.flag_id where 1 = 1";
		String order = " order by f.date_dismissed desc, f.dismissal_id desc";
		sameRows(dao.getFlagDismissals(null, null, null, null), select + order);
		sameRows(dao.getFlagDismissals(501, LocalDate.of(2026, 10, 22), LocalDate.of(2026, 10, 23), PATIENT),
		    select + " and f.dismissed_by = ? and f.date_dismissed >= ? and f.date_dismissed < ? and f.patient_id = ?"
		            + order,
		    501, Timestamp.valueOf("2026-10-22 00:00:00"), Timestamp.valueOf("2026-10-24 00:00:00"), PATIENT);
		sameRows(dao.getFlagDismissals(1, null, null, 2), select + " and f.dismissed_by = ? and f.patient_id = ?" + order, 1,
		    2);
	}
	
	@Test
	public void getReadIdRange_shouldGiveTheRowsOfItsSql() {
		String select = "select min(l.id) as lowest, max(l.id) as highest from read_audit_log l where 1 = 1";
		sameRows(dao.getReadIdRange(null, null), select);
		sameRows(dao.getReadIdRange(LocalDate.of(2026, 10, 22), LocalDate.of(2026, 10, 22)),
		    select + " and l.event_time >= ? and l.event_time < ?", Timestamp.valueOf("2026-10-22 00:00:00"),
		    Timestamp.valueOf("2026-10-23 00:00:00"));
	}
	
	@Test
	public void getReads_shouldGiveTheRowsOfItsSql() {
		List<String> entities = Arrays.asList("Patient", "Person");
		String select = "select l.id, l.username, l.user_uuid, l.event_time, l.entity_name, l.session_id, l.ip_address,"
		        + " l.user_agent, l.read_success, count(m.id) as targets, count(distinct m.patient_uuid) as patients,"
		        + " min(m.patient_uuid) as patient_uuid, min(m.patient_name) as patient_name from read_audit_log l"
		        + " left join read_audit_entity_metadata m on m.read_audit_id = l.id"
		        + " where (l.entity_name in (?, ?) or exists (select 1 from read_audit_entity_metadata p"
		        + " where p.read_audit_id = l.id and p.patient_uuid is not null))";
		String span = " and l.id > ? and l.id <= ? group by l.id, l.username, l.user_uuid, l.event_time, l.entity_name,"
		        + " l.session_id, l.ip_address, l.user_agent, l.read_success";
		sameRows(dao.getReads(entities, null, null, null, null, 0L, 10000L), select + span, "Patient", "Person", 0L, 10000L);
		sameRows(
		    dao.getReads(entities, "admin", LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 22),
		        Collections.singletonList(patientUuid), 0L, 10000L),
		    select + " and l.username = ? and l.event_time >= ? and l.event_time < ? and l.id in"
		            + " (select read_audit_id from read_audit_entity_metadata where patient_uuid in (?))" + span,
		    "Patient", "Person", "admin", Timestamp.valueOf("2026-10-21 00:00:00"), Timestamp.valueOf("2026-10-23 00:00:00"),
		    patientUuid, 0L, 10000L);
	}
	
	/**
	 * Checks the rows match those of the SQL the logs used to hand the DAO, and that there are some.
	 */
	private static void sameRows(List<Map<String, Object>> rows, String sql, Object... params) {
		List<Map<String, Object>> expected = AuditTables.select(sql, params);
		assertFalse("the data gives the query no rows: " + sql, expected.isEmpty());
		assertEquals(expected, new ArrayList<Map<String, Object>>(rows));
	}
	
	private static void revision(int id, String time, int userId) {
		sql("insert into revision_entity (id, timestamp, changedBy, changedOn) values (%d, %d, %d, '%s')", id,
		    Timestamp.valueOf(time).getTime(), userId, time);
	}
	
	private static void dismissal(int patientId, int flagId, int userId, String time) {
		sql("insert into actcore_flag_dismissal (patient_id, flag_id, dismissed_by, date_dismissed, uuid)"
		        + " values (%d, %d, %d, '%s', '%s')",
		    patientId, flagId, userId, time, UUID.randomUUID());
	}
	
	private static void read(int id, String entity, String username, String time, String patientUuid) {
		sql("insert into read_audit_log (id, entity_name, read_success, username, user_uuid, event_time, uuid)"
		        + " values (%d, '%s', true, %s, '%s', '%s', '%s')",
		    id, entity, username == null ? "null" : "'" + username + "'", ADMIN_UUID, time, UUID.randomUUID());
		sql("insert into read_audit_entity_metadata (read_audit_id, entity_uuid, patient_uuid) values (%d, '%s', %s)", id,
		    UUID.randomUUID(), patientUuid == null ? "null" : "'" + patientUuid + "'");
	}
	
	private static void sql(String statement, Object... args) {
		Context.getAdministrationService().executeSQL(String.format(statement, args), false);
	}
}
