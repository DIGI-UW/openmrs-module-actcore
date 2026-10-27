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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.time.YearMonth;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.Before;
import org.junit.Test;
import org.openmrs.Privilege;
import org.openmrs.Person;
import org.openmrs.PersonName;
import org.openmrs.Role;
import org.openmrs.User;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.UserService;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.clinics.UserClinics;
import org.openmrs.module.actcore.screening.ScreeningCounts;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.response.IllegalRequestException;
import org.openmrs.util.PrivilegeConstants;
import org.openmrs.web.test.BaseModuleWebContextSensitiveTest;
import org.springframework.beans.factory.annotation.Autowired;

public class ScreeningCountControllerTest extends BaseModuleWebContextSensitiveTest {
	
	/** Unknown Location and Xanadu in the standard test dataset. */
	private static final String CLINIC_A = "8d6c993e-c2cc-11de-8d13-0010c6dffd0f";
	
	private static final String CLINIC_B = "9356400c-a5a2-4532-8f2b-2361b3446eb8";
	
	@Autowired
	private ScreeningCountController controller;
	
	@Before
	public void createTheTableWithTwoClinicsSeptember() {
		StringBuilder columns = new StringBuilder();
		for (String column : ScreeningCounts.COLUMNS) {
			columns.append(", ").append(column).append(" int not null default 0");
		}
		Context.getAdministrationService().executeSQL("create table if not exists " + ScreeningCounts.TABLE
		        + " (screening_count_id int auto_increment primary key, location_id int not null, month date not null"
		        + columns + ", notes varchar(1000), changed_by int, date_changed datetime not null,"
		        + " unique (location_id, month))",
		    false);
		ScreeningCounts counts = new ScreeningCounts();
		counts.save(Context.getLocationService().getLocationByUuid(CLINIC_A), YearMonth.of(2026, 9), school(100, 10), null);
		counts.save(Context.getLocationService().getLocationByUuid(CLINIC_B), YearMonth.of(2026, 9), school(50, 5), null);
	}
	
	/** A school row of the screened, all children, half female, with the positives given. */
	private static Map<String, Integer> school(int screened, int positive) {
		Map<String, Integer> counts = new HashMap<String, Integer>();
		counts.put("school_screened", screened);
		counts.put("school_children", screened);
		counts.put("school_females", screened / 2);
		counts.put("school_positive", positive);
		return counts;
	}
	
	private static Map<String, Object> body(int screened, String notes) {
		Map<String, Object> body = new HashMap<String, Object>();
		body.put("counts", new HashMap<String, Object>(school(screened, 1)));
		body.put("notes", notes);
		return body;
	}
	
	@Test
	public void givesAMonthsCountsToADashboardReader() {
		authenticateWith(null, ScreeningCountController.DASHBOARD_PRIVILEGE);
		
		SimpleObject month = controller.get(CLINIC_A, "2026-09");
		
		assertEquals(Boolean.TRUE, month.get("saved"));
		assertEquals("2026-09", month.get("month"));
		assertEquals(CLINIC_A, month.get("clinic"));
		assertEquals(100, ((Number) counts(month).get("school_screened")).intValue());
		assertEquals(0, ((Number) counts(month).get("facility_screened")).intValue());
	}
	
	@Test
	public void savesAMonthForAUserWhoMayEnterThemAndAnswersWithIt() {
		authenticateWith(null, ScreeningCountController.ENTER_PRIVILEGE);
		
		SimpleObject saved = controller.save(CLINIC_A, "2026-08", body(40, "Two schools"));
		
		assertEquals(Boolean.TRUE, saved.get("saved"));
		assertEquals("Two schools", saved.get("notes"));
		assertEquals(40, ((Number) counts(controller.get(CLINIC_A, "2026-08")).get("school_screened")).intValue());
	}
	
	@Test
	public void refusesToSaveForAReaderOnly() {
		authenticateWith(null, ScreeningCountController.READ_PRIVILEGE);
		
		try {
			controller.save(CLINIC_A, "2026-08", body(40, null));
			fail("expected the save to be refused");
		}
		catch (APIAuthenticationException e) {
			assertTrue(e.getMessage().contains(ScreeningCountController.ENTER_PRIVILEGE));
		}
	}
	
	@Test
	public void refusesAnyoneWithoutAPrivilegeToReadThem() {
		authenticateWith(null);
		
		try {
			controller.get(CLINIC_A, "2026-09");
			fail("expected the read to be refused");
		}
		catch (APIAuthenticationException e) {
			assertTrue(e.getMessage().contains(ScreeningCountController.READ_PRIVILEGE));
		}
		try {
			controller.summary(null, null, null);
			fail("expected the summary to be refused");
		}
		catch (APIAuthenticationException e) {
			assertTrue(e.getMessage().contains(ScreeningCountController.READ_PRIVILEGE));
		}
	}
	
	@Test
	public void answersCountsThatDoNotHoldTogetherAsABadRequest() {
		authenticateWith(null, ScreeningCountController.ENTER_PRIVILEGE);
		Map<String, Object> body = body(40, null);
		@SuppressWarnings("unchecked")
		Map<String, Object> counts = (Map<String, Object>) body.get("counts");
		counts.put("school_children", 39);
		
		badRequest(() -> controller.save(CLINIC_A, "2026-08", body), "age groups");
		counts.put("school_children", "forty");
		badRequest(() -> controller.save(CLINIC_A, "2026-08", body), "school_children");
		counts.put("school_children", 4.5);
		badRequest(() -> controller.save(CLINIC_A, "2026-08", body), "school_children must be a whole number");
		counts.put("school_children", 3000000000L);
		badRequest(() -> controller.save(CLINIC_A, "2026-08", body), "school_children is more than 2147483647");
		badRequest(() -> controller.save(CLINIC_A, "2026-8", body(40, null)), "YYYY-MM");
		badRequest(() -> controller.save("no-such-clinic", "2026-08", body(40, null)), "no-such-clinic");
	}
	
	@Test
	public void holdsASiteAdministratorToTheClinicsOnTheirAccount() {
		authenticateWith(CLINIC_A, ScreeningCountController.ENTER_PRIVILEGE, PrivilegeConstants.EDIT_USERS);
		
		assertEquals(100, ((Number) counts(controller.get(CLINIC_A, "2026-09")).get("school_screened")).intValue());
		controller.save(CLINIC_A, "2026-08", body(40, null));
		forbidden(() -> controller.get(CLINIC_B, "2026-09"));
		forbidden(() -> controller.save(CLINIC_B, "2026-08", body(40, null)));
		
		assertEquals(100 + 40, ((Number) controller.summary(null, "2026-01", "2026-12").get("screened")).intValue());
		assertEquals(100 + 40,
		    ((Number) controller.summary(new String[] { CLINIC_A, CLINIC_B }, "2026-01", "2026-12").get("screened"))
		            .intValue());
		SimpleObject elsewhere = controller.summary(new String[] { CLINIC_B }, "2026-01", "2026-12");
		assertEquals(0, ((Number) elsewhere.get("screened")).intValue());
		assertEquals(0, ((Number) elsewhere.get("months")).intValue());
	}
	
	@Test
	public void givesASiteAdministratorWithNoClinicsNothingRatherThanEveryClinic() {
		authenticateWith("", ScreeningCountController.READ_PRIVILEGE, PrivilegeConstants.EDIT_USERS);
		
		assertEquals(0, ((Number) controller.summary(null, "2026-01", "2026-12").get("screened")).intValue());
	}
	
	@Test
	public void sumsEveryClinicOrTheClinicsGivenOverTheMonthsGiven() {
		authenticateWith(null, ScreeningCountController.DASHBOARD_PRIVILEGE);
		
		SimpleObject every = controller.summary(null, "2026-09", "2026-09");
		assertEquals(150, ((Number) every.get("screened")).intValue());
		assertEquals(15, ((Number) every.get("positive")).intValue());
		assertEquals(135, ((Number) every.get("negative")).intValue());
		assertEquals(75, ((Number) every.get("females")).intValue());
		assertEquals(150, ((Number) every.get("children")).intValue());
		assertEquals(0, ((Number) every.get("adults")).intValue());
		assertEquals(150, ((Number) ((SimpleObject) every.get("settings")).get("school")).intValue());
		assertEquals(2, ((Number) every.get("months")).intValue());
		assertEquals("2026-09", every.get("startMonth"));
		
		assertEquals(50,
		    ((Number) controller.summary(new String[] { CLINIC_B }, "2026-09", "2026-09").get("screened")).intValue());
		assertEquals(0, ((Number) controller.summary(null, "2026-10", "2026-12").get("screened")).intValue());
		badRequest(() -> controller.summary(null, "2026-10", "2026-09"), "after");
	}
	
	@SuppressWarnings("unchecked")
	private static Map<String, Object> counts(SimpleObject month) {
		return (Map<String, Object>) month.get("counts");
	}
	
	private static void badRequest(Runnable request, String naming) {
		try {
			request.run();
			fail("expected a bad request");
		}
		catch (IllegalRequestException e) {
			assertTrue(e.getMessage(), e.getMessage().contains(naming));
		}
	}
	
	private static void forbidden(Runnable request) {
		try {
			request.run();
			fail("expected the request to be refused");
		}
		catch (APIAuthenticationException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("your clinics"));
		}
	}
	
	/**
	 * Signs in a user holding only these privileges, with these clinics on the account when not null.
	 */
	private void authenticateWith(String clinics, String... privileges) {
		UserService users = Context.getUserService();
		Role role = new Role("screening caller " + UUID.randomUUID());
		for (String name : privileges) {
			Privilege privilege = users.getPrivilege(name);
			role.addPrivilege(privilege != null ? privilege : users.savePrivilege(new Privilege(name)));
		}
		users.saveRole(role);
		Person person = new Person();
		person.addName(new PersonName("Screening", null, "Caller"));
		person.setGender("F");
		User user = new User(person);
		user.setUsername("screeningcaller");
		user.addRole(role);
		if (clinics != null) {
			user.setUserProperty(UserClinics.PROPERTY, clinics);
		}
		users.createUser(user, "Screening123");
		Context.logout();
		Context.authenticate("screeningcaller", "Screening123");
	}
}
