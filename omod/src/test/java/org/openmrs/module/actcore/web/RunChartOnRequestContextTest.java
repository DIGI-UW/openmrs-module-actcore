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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openmrs.GlobalProperty;
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
import org.openmrs.module.actcore.runchart.RunCharts;
import org.openmrs.module.reporting.dataset.definition.SqlDataSetDefinition;
import org.openmrs.module.reporting.evaluation.parameter.Mapped;
import org.openmrs.module.reporting.evaluation.parameter.Parameter;
import org.openmrs.module.reporting.report.definition.ReportDefinition;
import org.openmrs.module.reporting.report.definition.service.ReportDefinitionService;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.response.IllegalRequestException;
import org.openmrs.web.test.BaseModuleWebContextSensitiveTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The run chart as the Dashboard reads it: each point calculated from the report when asked.
 * Outside a test transaction, so the daemon thread that evaluates the report sees the stub report
 * and the property naming it.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class RunChartOnRequestContextTest extends BaseModuleWebContextSensitiveTest {
	
	private static final String REPORT_UUID = "c0b4e2a1-7d3f-4e5a-9b1c-2d6f8a0e4b77";
	
	@Autowired
	private RunChartController controller;
	
	private final List<Integer> createdUsers = new ArrayList<Integer>();
	
	private final List<String> createdRoles = new ArrayList<String>();
	
	private final List<String> createdPrivileges = new ArrayList<String>();
	
	/**
	 * A report answering a row of every clinic for four point dates, with the day of month as the
	 * active count, whatever range it is asked for.
	 */
	@Before
	public void pointAtAReportOfFourPoints() {
		DaemonTokens.pass("actcore", new ActCoreActivator());
		StringBuilder select = new StringBuilder();
		for (int day : new int[] { 4, 11, 18, 21 }) {
			if (select.length() > 0) {
				select.append(" union all ");
			}
			select.append("select date '2026-10-").append(day).append("' as point_date, null as clinic_uuid,")
			        .append(" 40 as total_registrants, ").append(day).append(" as active_registrants,")
			        .append(" 2 as active_registrants_seen_last_7_months, 3 as active_registrants_prescribed_prophylaxis,")
			        .append(" 2 as consultation_visit_total, 1 as consultation_visits_with_echocardiogram_resulted,")
			        .append(" 0 as consultation_visits_with_intervention, 1 as interventions_completed,")
			        .append(" 0 as active_registrants_on_bpg, null as bpg_patient_ids");
		}
		SqlDataSetDefinition sql = new SqlDataSetDefinition();
		sql.addParameter(new Parameter("startDate", "From", java.util.Date.class));
		sql.addParameter(new Parameter("endDate", "To", java.util.Date.class));
		sql.addParameter(new Parameter("points", "Points", String.class));
		sql.setSqlQuery(select.toString());
		ReportDefinition report = new ReportDefinition();
		report.setUuid(REPORT_UUID);
		report.setName("Run chart on request test report");
		report.addParameter(new Parameter("startDate", "From", java.util.Date.class));
		report.addParameter(new Parameter("endDate", "To", java.util.Date.class));
		report.addParameter(new Parameter("points", "Points", String.class));
		report.addDataSetDefinition("metrics", Mapped.mapStraightThrough(sql));
		Context.getService(ReportDefinitionService.class).saveDefinition(report);
		Context.getAdministrationService().saveGlobalProperty(new GlobalProperty(RunCharts.GP_REPORT, REPORT_UUID));
	}
	
	@After
	public void purgeWhatWasCommitted() {
		new ActCoreActivator().setDaemonToken(null);
		Context.logout();
		authenticate();
		ReportDefinitionService reports = Context.getService(ReportDefinitionService.class);
		ReportDefinition report = reports.getDefinitionByUuid(REPORT_UUID);
		if (report != null) {
			reports.purgeDefinition(report);
		}
		Context.getAdministrationService()
		        .executeSQL("delete from global_property where property = '" + RunCharts.GP_REPORT + "'", false);
		UserService users = Context.getUserService();
		for (Integer id : createdUsers) {
			users.purgeUser(users.getUser(id));
		}
		for (String name : createdRoles) {
			users.purgeRole(users.getRole(name));
		}
		for (String name : createdPrivileges) {
			Context.getAdministrationService().executeSQL("delete from privilege where privilege = '" + name + "'", false);
		}
	}
	
	@SuppressWarnings("unchecked")
	private static List<SimpleObject> points(SimpleObject response) {
		return (List<SimpleObject>) response.get("points");
	}
	
	@Test
	public void givesAPointPerWeekOfTheRangeCalculatedFromTheReportToAUserWithTheDashboardPrivilege() {
		authenticateWith(RunChartController.READ_PRIVILEGE);
		
		SimpleObject response = controller.get("active_registrants", null, "2026-10-01", "2026-10-21", "week");
		
		List<SimpleObject> points = points(response);
		assertEquals(4, points.size());
		assertEquals("2026-10-04", points.get(0).get("date"));
		assertEquals(4.0, (Double) points.get(0).get("value"), 0);
		assertEquals("2026-10-11", points.get(1).get("date"));
		assertEquals("2026-10-18", points.get(2).get("date"));
		assertEquals("2026-10-21", points.get(3).get("date"));
		assertEquals(21.0, (Double) points.get(3).get("value"), 0);
		assertEquals(Long.valueOf(21), points.get(3).get("count"));
		assertNull(points.get(3).get("total"));
		assertEquals(14.5, (Double) response.get("median"), 0);
	}
	
	@Test
	public void answersEveryMetricWithTheOneNamedOnTopAndAllOfThemWithoutAName() {
		authenticateWith(RunChartController.READ_PRIVILEGE);
		
		SimpleObject named = controller.get("active_registrants", null, "2026-10-19", "2026-10-21", "day");
		SimpleObject unnamed = controller.get(null, null, "2026-10-19", "2026-10-21", "day");
		
		SimpleObject metrics = (SimpleObject) named.get("metrics");
		assertEquals(RunCharts.METRICS, new ArrayList<String>(metrics.keySet()));
		assertEquals(points(named), points((SimpleObject) metrics.get("active_registrants")));
		assertEquals(3, points((SimpleObject) metrics.get("active_registrants_seen_last_7_months")).size());
		assertNull(unnamed.get("metric"));
		assertNull(unnamed.get("points"));
		assertEquals(3, points((SimpleObject) ((SimpleObject) unnamed.get("metrics")).get("total_registrants")).size());
	}
	
	@Test
	public void refusesAUserWithoutTheDashboardPrivilege() {
		authenticateWith("Some other privilege");
		
		try {
			controller.get("active_registrants", null, null, null, null);
			fail("expected the request to be refused");
		}
		catch (APIAuthenticationException e) {
			assertTrue(e.getMessage().contains(RunChartController.READ_PRIVILEGE));
		}
	}
	
	@Test
	public void refusesToCalculateBeforeTheModuleHasStarted() {
		new ActCoreActivator().setDaemonToken(null);
		authenticateWith(RunChartController.READ_PRIVILEGE);
		
		try {
			controller.get("active_registrants", null, null, null, null);
			fail("expected the request to be refused");
		}
		catch (APIException e) {
			assertTrue(e.getMessage().contains("has not started"));
		}
	}
	
	@Test
	public void refusesDailyPointsOverMoreThan92DaysAndMoreThanAThousandPoints() {
		authenticateWith(RunChartController.READ_PRIVILEGE);
		
		try {
			controller.get("active_registrants", null, "2026-01-01", "2026-10-21", "day");
			fail("expected the request to be refused");
		}
		catch (IllegalRequestException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("92"));
		}
		try {
			controller.get("active_registrants", null, "2000-01-01", "2026-10-21", "week");
			fail("expected the request to be refused");
		}
		catch (IllegalRequestException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("1000"));
		}
		assertEquals(93, points(controller.get("active_registrants", null, "2026-07-21", "2026-10-21", "day")).size());
	}
	
	@Test
	public void refusesAnUnknownMetricOrClinicAndBadDates() {
		authenticateWith(RunChartController.READ_PRIVILEGE);
		for (Runnable call : new Runnable[] { () -> controller.get("consultation_visit_total", null, null, null, null),
		        () -> controller.get("active_registrants", UUID.randomUUID().toString(), null, null, null),
		        () -> controller.get("active_registrants", null, "21/10/2026", null, null),
		        () -> controller.get("active_registrants", null, "2026-10-22", "2026-10-21", null),
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
	
	private void authenticateWith(String... privileges) {
		UserService users = Context.getUserService();
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		Role role = new Role("run chart caller " + suffix);
		for (String name : privileges) {
			Privilege privilege = users.getPrivilege(name);
			if (privilege == null) {
				privilege = users.savePrivilege(new Privilege(name));
				createdPrivileges.add(name);
			}
			role.addPrivilege(privilege);
		}
		users.saveRole(role);
		createdRoles.add(role.getRole());
		Person person = new Person();
		person.addName(new PersonName("Run", null, "Chart"));
		person.setGender("F");
		User user = new User(person);
		user.setUsername("runchart" + suffix);
		user.addRole(role);
		createdUsers.add(users.createUser(user, "RunChart123").getId());
		Context.logout();
		Context.authenticate(user.getUsername(), "RunChart123");
	}
}
