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

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openmrs.Person;
import org.openmrs.PersonName;
import org.openmrs.Privilege;
import org.openmrs.Role;
import org.openmrs.User;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.APIException;
import org.openmrs.api.UserService;
import org.openmrs.api.context.Context;
import org.openmrs.module.DaemonTokens;
import org.openmrs.module.actcore.ActCoreActivator;
import org.openmrs.module.actcore.runchart.RunChartRuns;
import org.openmrs.module.actcore.runchart.RunCharts;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.response.IllegalRequestException;
import org.openmrs.web.test.BaseModuleWebContextSensitiveTest;
import org.springframework.beans.factory.annotation.Autowired;

public class RunChartControllerTest extends BaseModuleWebContextSensitiveTest {
	
	private static final String UNKNOWN_LOCATION = "8d6c993e-c2cc-11de-8d13-0010c6dffd0f";
	
	@Autowired
	private RunChartController controller;
	
	@Before
	public void createTheTableWithTwoDays() {
		StringBuilder columns = new StringBuilder();
		for (String column : RunCharts.COLUMNS) {
			columns.append(", ").append(column).append(" int not null default 0");
		}
		Context.getAdministrationService()
		        .executeSQL("create table if not exists " + RunCharts.TABLE
		                + " (record_id int auto_increment primary key, record_date date not null, location_id int" + columns
		                + ", date_created datetime not null, unique (record_date, location_id))",
		            false);
		// DDL commits, so it comes before any row is inserted.
		Context.getAdministrationService().executeSQL(
		    "create table if not exists " + RunChartRuns.TABLE
		            + " (run_id int auto_increment primary key, run_date date not null, finished datetime not null,"
		            + " duration_millis bigint not null default 0, source varchar(16) not null, status varchar(16) not null,"
		            + " row_count int not null default 0, clinic_count int not null default 0, failure_reason varchar(500))",
		    false);
		LocalDate today = LocalDate.now();
		for (String row : new String[] { "'2024-03-04', 1, 4, 1", "'2024-03-06', 1, 6, 3", "'2024-03-08', 1, 8, 2",
		        "'" + today.minusDays(3) + "', 1, 8, 2", "'" + today + "', 1, 10, 5", "'" + today + "', null, 30, 15",
		        "'" + today.minusYears(2) + "', 1, 1, 1" }) {
			Context.getAdministrationService().executeSQL("insert into " + RunCharts.TABLE
			        + " (record_date, location_id, active_registrants, active_registrants_adherent, date_created) values ("
			        + row + ", current_timestamp)",
			    false);
		}
	}
	
	private void createTheRunTableWithTwoRuns() {
		Context.getAdministrationService().executeSQL("delete from " + RunChartRuns.TABLE, false);
		Context.getAdministrationService().executeSQL("insert into " + RunChartRuns.TABLE
		        + " (run_date, finished, duration_millis, source, status, row_count, clinic_count, failure_reason) values"
		        + " ('2026-10-20', '2026-10-20 02:05:38', 38000, 'scheduled', 'taken', 61, 60, null),"
		        + " ('2026-10-21', '2026-10-21 02:05:02', 2000, 'scheduled', 'failed', 0, 0, 'APIException: no report')",
		    false);
	}
	
	@Test
	public void givesTheLatestRunsNewestFirstWithTheReasonOfAFailedOneToAUserWhoMayTakeTheSnapshot() {
		createTheRunTableWithTwoRuns();
		authenticateWith(RunChartController.RUN_PRIVILEGE);
		
		SimpleObject response = controller.runs(null);
		
		@SuppressWarnings("unchecked")
		List<SimpleObject> runs = (List<SimpleObject>) response.get("runs");
		assertEquals(2, runs.size());
		assertEquals("failed", runs.get(0).get("status"));
		assertEquals("2026-10-21", runs.get(0).get("runDate"));
		assertEquals("APIException: no report", runs.get(0).get("reason"));
		assertEquals("taken", runs.get(1).get("status"));
		assertEquals(61, ((Number) runs.get(1).get("rows")).intValue());
		assertEquals(60, ((Number) runs.get(1).get("clinics")).intValue());
		assertEquals(38000L, ((Number) runs.get(1).get("durationMillis")).longValue());
		assertEquals("scheduled", runs.get(1).get("source"));
		assertTrue(((String) runs.get(1).get("finished")).endsWith("Z"));
		assertEquals(Boolean.FALSE, response.get("running"));
		assertEquals("2026-10-20", ((SimpleObject) response.get("lastTaken")).get("runDate"));
	}
	
	@Test
	public void refusesTheRunsToAUserWhoMayNotTakeTheSnapshot() {
		authenticateWith(RunChartController.READ_PRIVILEGE);
		
		try {
			controller.runs(null);
			fail("expected the request to be refused");
		}
		catch (APIAuthenticationException e) {
			assertTrue(e.getMessage().contains(RunChartController.RUN_PRIVILEGE));
		}
	}
	
	@Test
	public void asksForNoMoreRunsThanItWillGive() {
		authenticateWith(RunChartController.RUN_PRIVILEGE);
		for (Integer limit : new Integer[] { 0, 101 }) {
			try {
				controller.runs(limit);
				fail("expected the request to be refused");
			}
			catch (IllegalRequestException e) {
				// expected
			}
		}
	}
	
	@After
	public void forgetTheDaemonToken() {
		new ActCoreActivator().setDaemonToken(null);
	}
	
	@SuppressWarnings("unchecked")
	private static List<SimpleObject> points(SimpleObject response) {
		return (List<SimpleObject>) response.get("points");
	}
	
	@Test
	public void givesTheClinicsMetricOverTheYearToTodayForAUserWithOnlyTheDashboardPrivilege() {
		authenticateWith(RunChartController.READ_PRIVILEGE);
		
		SimpleObject response = controller.get("active_registrants_adherent", UNKNOWN_LOCATION, null, null, "day");
		
		List<SimpleObject> points = points(response);
		assertEquals(2, points.size());
		assertEquals(LocalDate.now().minusDays(3).toString(), points.get(0).get("date"));
		assertEquals(25.0, (Double) points.get(0).get("value"), 0);
		assertEquals(50.0, (Double) points.get(1).get("value"), 0);
		assertEquals(LocalDate.now().toString(), response.get("lastSnapshot"));
		assertEquals(37.5, (Double) response.get("median"), 0);
	}
	
	@Test
	public void groupsByWeekByDefaultAndGivesTheMedianOfTheWeeksPoints() {
		SimpleObject response = controller.get("active_registrants", clinicUuid(), "2024-03-01", "2024-03-31", null);
		
		assertEquals("week", response.get("period"));
		assertEquals(1, points(response).size());
		assertEquals("2024-03-04", points(response).get(0).get("date"));
		// A count is its latest snapshot in the week.
		assertEquals(8.0, (Double) points(response).get(0).get("value"), 0);
		assertEquals(Long.valueOf(8), points(response).get(0).get("count"));
		assertNull(points(response).get(0).get("total"));
		assertEquals(8.0, (Double) response.get("median"), 0);
	}
	
	@Test
	public void givesAPercentOfAWeekAsTheRatioOfItsSums() {
		SimpleObject response = controller.get("active_registrants_adherent", clinicUuid(), "2024-03-01", "2024-03-31",
		    "week");
		
		// 1 + 3 + 2 adherent over 4 + 6 + 8 active.
		assertEquals(33.3, (Double) points(response).get(0).get("value"), 0);
		assertEquals(Long.valueOf(6), points(response).get(0).get("count"));
		assertEquals(Long.valueOf(18), points(response).get(0).get("total"));
	}
	
	private String clinicUuid() {
		return Context.getLocationService().getLocation(1).getUuid();
	}
	
	@Test
	public void givesEveryClinicsRowsWhenNoClinicIsGiven() {
		SimpleObject response = controller.get("active_registrants", null, LocalDate.now().toString(), null, "day");
		
		assertEquals(1, points(response).size());
		assertEquals(30.0, (Double) points(response).get(0).get("value"), 0);
	}
	
	@Test
	public void refusesAnUnknownMetricOrClinicAndBadDates() {
		String today = LocalDate.now().toString();
		for (Runnable call : new Runnable[] { () -> controller.get("consultation_visit_total", null, null, null, null),
		        () -> controller.get("active_registrants", UUID.randomUUID().toString(), null, null, null),
		        () -> controller.get("active_registrants", null, "21/10/2026", null, null),
		        () -> controller.get("active_registrants", null, today, LocalDate.now().minusDays(1).toString(), null),
		        () -> controller.get("active_registrants", null, null, null, "year") }) {
			try {
				call.run();
				fail("expected the request to be refused");
			}
			catch (IllegalRequestException e) {
				// expected
			}
		}
	}
	
	@Test
	public void refusesAUserWithoutTheDashboardPrivilege() {
		authenticateWith("Get Patients");
		
		try {
			controller.get("active_registrants", null, null, null, null);
			fail("expected the request to be refused");
		}
		catch (APIAuthenticationException e) {
			assertTrue(e.getMessage().contains(RunChartController.READ_PRIVILEGE));
		}
	}
	
	@Test
	public void refusesTheSnapshotToAUserWithOnlyTheDashboardPrivilege() {
		authenticateWith(RunChartController.READ_PRIVILEGE);
		
		try {
			controller.snapshot();
			fail("expected the snapshot to be refused");
		}
		catch (APIAuthenticationException e) {
			assertTrue(e.getMessage().contains(RunChartController.RUN_PRIVILEGE));
		}
	}
	
	@Test
	public void takesTheSnapshotAsTheDaemonAndSaysWhyTheAdherenceRefreshBeforeItFailed() {
		DaemonTokens.pass("actcore", new ActCoreActivator());
		// The adherence the report reads is recomputed first, and this context configures none of it.
		authenticateWith(RunChartController.RUN_PRIVILEGE);
		
		try {
			controller.snapshot();
			fail("expected the snapshot to fail");
		}
		catch (APIException e) {
			assertEquals("The run chart snapshot failed", e.getMessage());
			assertTrue(e.getCause().getMessage(), e.getCause().getMessage().contains("actcore.adherence"));
		}
	}
	
	@Test
	public void refusesToTakeTheSnapshotBeforeTheModuleHasStarted() {
		try {
			controller.snapshot();
			fail("expected the snapshot to be refused");
		}
		catch (APIException e) {
			assertTrue(e.getMessage().contains("has not started"));
		}
	}
	
	@Test
	public void saysNoSnapshotWasTakenWhenTheTableIsEmpty() {
		Context.getAdministrationService().executeSQL("delete from " + RunCharts.TABLE, false);
		
		assertNull(controller.get("active_registrants", null, null, null, null).get("lastSnapshot"));
	}
	
	private void authenticateWith(String... privileges) {
		UserService users = Context.getUserService();
		Role role = new Role("run chart caller " + UUID.randomUUID());
		for (String name : privileges) {
			Privilege privilege = users.getPrivilege(name);
			role.addPrivilege(privilege != null ? privilege : users.savePrivilege(new Privilege(name)));
		}
		users.saveRole(role);
		Person person = new Person();
		person.addName(new PersonName("Run", null, "Chart"));
		person.setGender("F");
		User user = new User(person);
		user.setUsername("runchartcaller");
		user.addRole(role);
		users.createUser(user, "RunChart123");
		Context.logout();
		Context.authenticate("runchartcaller", "RunChart123");
	}
}
