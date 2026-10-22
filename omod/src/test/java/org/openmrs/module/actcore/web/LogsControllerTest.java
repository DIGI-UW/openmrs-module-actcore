/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.apache.commons.io.IOUtils;
import org.junit.Before;
import org.junit.Test;
import org.openmrs.Person;
import org.openmrs.PersonName;
import org.openmrs.Privilege;
import org.openmrs.Role;
import org.openmrs.User;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.UserService;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.Transactions;
import org.openmrs.module.actcore.logs.ActivityLog;
import org.openmrs.module.actcore.logs.ReadLog;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.response.IllegalRequestException;
import org.openmrs.module.webservices.rest.web.response.ObjectNotFoundException;
import org.openmrs.web.test.BaseModuleWebContextSensitiveTest;
import org.springframework.beans.factory.annotation.Autowired;

/** The endpoints' privileges and shapes; the grouping and folding are the api tests'. */
public class LogsControllerTest extends BaseModuleWebContextSensitiveTest {
	
	@Autowired
	private ActivityController activity;
	
	@Autowired
	private ReadsController reads;
	
	@Before
	public void setUp() {
		// The api's audit-tables.sql is on this module's test classpath too; H2 commits the DDL at once.
		Transactions.inNewTransaction(() -> {
			try (InputStream script = getClass().getClassLoader().getResourceAsStream("audit-tables.sql")) {
				for (String statement : IOUtils.toString(script, StandardCharsets.UTF_8).replaceAll("(?m)^--.*$", "")
				        .split(";")) {
					if (statement.trim().startsWith("create table")) {
						Context.getAdministrationService().executeSQL(statement.trim(), false);
						Context.getAdministrationService().executeSQL("delete from " + statement.trim().split("\\s+")[5],
						    false);
					}
				}
			}
			catch (java.io.IOException e) {
				throw new IllegalStateException(e);
			}
		});
		sql("insert into revision_entity (id, timestamp, changedBy, changedOn) values (10, 0, 1, '2026-10-21 10:52:14')");
		sql("insert into encounter_AUD (encounter_id, REV, REVTYPE, uuid, voided, encounter_datetime, form_id, patient_id)"
		        + " values (3, 10, 0, 'enc', false, '2026-10-21 10:52:00', 1, 7)");
		sql("insert into read_audit_log (id, entity_name, read_success, username, user_uuid, event_time, uuid)"
		        + " values (1, 'Patient', true, 'admin', 'admin-uuid', '" + Timestamp.valueOf(LocalDate.now().atTime(7, 41))
		        + "', '" + UUID.randomUUID() + "')");
	}
	
	@Test
	public void refusesTheActivityToAUserWithoutViewAuditLogs() {
		signInAs("reader", ReadLog.VIEW_PRIVILEGE);
		for (Runnable call : new Runnable[] { () -> activity.getActivity(null, null, null, null, null, null, null, null),
		        () -> activity.getDetails(10) }) {
			try {
				call.run();
				fail("expected the activity to be refused");
			}
			catch (APIAuthenticationException e) {
				assertTrue(e.getMessage().contains(ActivityLog.VIEW_PRIVILEGE));
			}
		}
	}
	
	@Test
	public void listsTheActivityWithItsCountForAUserWithViewAuditLogs() {
		signInAs("auditor", ActivityLog.VIEW_PRIVILEGE);
		
		SimpleObject page = activity.getActivity(null, null, null, null, null, null, null, null);
		
		assertEquals(Integer.valueOf(1), page.get("totalCount"));
		assertEquals(java.time.LocalDate.now().minusMonths(12).toString(), page.get("from"));
		assertNull(page.get("to"));
		assertEquals(Integer.valueOf(1),
		    activity.getActivity(null, null, null, null, null, null, null, 0).get("totalCount"));
		assertEquals(Integer.valueOf(0),
		    activity.getActivity(null, null, "2026-10-20", null, null, null, null, null).get("totalCount"));
		SimpleObject entry = (SimpleObject) ((List<?>) page.get("results")).get(0);
		assertEquals(Integer.valueOf(10), entry.get("revisionId"));
		assertEquals("saved", entry.get("kind"));
		assertEquals("admin", ((SimpleObject) entry.get("user")).get("username"));
		assertEquals("6TS-4", ((SimpleObject) entry.get("target")).get("actId"));
		assertNull(entry.get("ip"));
		
		SimpleObject details = activity.getDetails(10);
		assertEquals(Integer.valueOf(10), details.get("revisionId"));
		assertTrue(((List<?>) details.get("fields")).isEmpty());
	}
	
	@Test
	public void refusesABadKindDateOrUnknownRevision() {
		signInAs("auditor", ActivityLog.VIEW_PRIVILEGE);
		try {
			activity.getActivity(null, null, null, "deleted", null, null, null, null);
			fail("expected the kind to be refused");
		}
		catch (IllegalRequestException expected) {}
		try {
			activity.getActivity(null, "2026-10-22", "2026-10-21", null, null, null, null, null);
			fail("expected the dates to be refused");
		}
		catch (IllegalRequestException expected) {}
		try {
			activity.getDetails(999);
			fail("expected the revision to be unknown");
		}
		catch (ObjectNotFoundException expected) {}
	}
	
	@Test
	public void refusesTheReadsToAUserWithoutViewReadAuditLogs() {
		signInAs("auditor", ActivityLog.VIEW_PRIVILEGE);
		try {
			reads.getReads(null, null, null, null, null, null);
			fail("expected the reads to be refused");
		}
		catch (APIAuthenticationException e) {
			assertTrue(e.getMessage().contains(ReadLog.VIEW_PRIVILEGE));
		}
	}
	
	@Test
	public void listsTheReadsWithTheirCountForAUserWithViewReadAuditLogs() {
		signInAs("reader", ReadLog.VIEW_PRIVILEGE);
		
		SimpleObject page = reads.getReads(null, null, null, null, null, null);
		
		assertEquals(Integer.valueOf(1), page.get("totalCount"));
		assertEquals(LocalDate.now().minusDays(30).toString(), page.get("from"));
		SimpleObject row = (SimpleObject) ((List<?>) page.get("results")).get(0);
		assertEquals(Integer.valueOf(1), row.get("count"));
		assertEquals("admin", ((SimpleObject) row.get("user")).get("username"));
		assertNull(row.get("patient"));
		assertEquals("no patient found", ((SimpleObject) ((List<?>) row.get("reads")).get(0)).get("description"));
	}
	
	@Test
	public void refusesAKindFilterOverTooManyChangesAsABadRequest() {
		sql("insert into revision_entity (id, timestamp, changedBy, changedOn) select 1000 + x, 0, 1, '"
		        + Timestamp.valueOf(LocalDate.now().atStartOfDay()) + "' from system_range(1, 10001)");
		sql("insert into visit_AUD (visit_id, REV, REVTYPE, patient_id) select 1, 1000 + x, 0, 7 from system_range(1, 10001)");
		signInAs("auditor", ActivityLog.VIEW_PRIVILEGE);
		
		try {
			activity.getActivity(null, null, null, "other", null, null, null, null);
			fail("expected the kind filter to be refused");
		}
		catch (IllegalRequestException e) {
			assertTrue(e.getMessage(), e.getMessage().startsWith("More than 10000 changes"));
		}
	}
	
	@Test
	public void readsAKindFilterWithoutAStartDateOverTheLastThirtyDays() {
		sql("insert into revision_entity (id, timestamp, changedBy, changedOn) select 1000 + x, 0, 1, '"
		        + Timestamp.valueOf(LocalDate.now().minusDays(40).atStartOfDay()) + "' from system_range(1, 10001)");
		sql("insert into visit_AUD (visit_id, REV, REVTYPE, patient_id) select 1, 1000 + x, 0, 7 from system_range(1, 10001)");
		signInAs("auditor", ActivityLog.VIEW_PRIVILEGE);
		
		SimpleObject page = activity.getActivity(null, null, null, "other", null, null, null, null);
		
		assertEquals(LocalDate.now().minusDays(30).toString(), page.get("from"));
		assertEquals(Integer.valueOf(0), page.get("totalCount"));
		assertEquals(LocalDate.now().minusMonths(12).toString(),
		    activity.getActivity(null, null, null, null, null, null, null, null).get("from"));
	}
	
	@Test
	public void refusesReadsTooManyToFoldAsABadRequest() {
		sql("insert into read_audit_log (entity_name, read_success, username, user_uuid, event_time, uuid)"
		        + " select 'Patient', true, 'bulk', 'bulk-uuid', '" + Timestamp.valueOf(LocalDate.now().atStartOfDay())
		        + "', random_uuid() from system_range(1, 50000)");
		signInAs("reader", ReadLog.VIEW_PRIVILEGE);
		
		try {
			reads.getReads(null, null, null, null, null, null);
			fail("expected the reads to be refused");
		}
		catch (IllegalRequestException e) {
			assertTrue(e.getMessage(), e.getMessage().startsWith("More than 50000 reads match"));
		}
	}
	
	private static void sql(String statement) {
		Context.getAdministrationService().executeSQL(statement, false);
	}
	
	private static void signInAs(String username, String... privileges) {
		UserService users = Context.getUserService();
		Context.authenticate("admin", "test");
		Role role = new Role("log reader " + UUID.randomUUID());
		for (String name : privileges) {
			Privilege privilege = users.getPrivilege(name);
			role.addPrivilege(privilege != null ? privilege : users.savePrivilege(new Privilege(name)));
		}
		users.saveRole(role);
		Person person = new Person();
		person.addName(new PersonName("Log", null, "Reader"));
		person.setGender("F");
		User user = new User(person);
		user.setUsername(username);
		user.addRole(role);
		users.createUser(user, "Reader123");
		Context.logout();
		Context.authenticate(username, "Reader123");
	}
}
