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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.PreparedStatement;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.aopalliance.intercept.MethodInterceptor;
import org.hibernate.SessionFactory;
import org.junit.Before;
import org.junit.Test;
import org.openmrs.GlobalProperty;
import org.openmrs.Person;
import org.openmrs.PersonName;
import org.openmrs.Privilege;
import org.openmrs.Role;
import org.openmrs.User;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.UserService;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.users.ClinicUsers;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.util.PrivilegeConstants;
import org.openmrs.web.test.BaseModuleWebContextSensitiveTest;
import org.springframework.beans.factory.annotation.Autowired;

public class UsersControllerTest extends BaseModuleWebContextSensitiveTest {
	
	@Autowired
	private UsersController controller;
	
	private String clinicA;
	
	private String clinicB;
	
	private Role siteAdministrator;
	
	@Before
	public void setUp() {
		clinicA = Context.getLocationService().getLocation(1).getUuid();
		clinicB = Context.getLocationService().getLocation(2).getUuid();
		Role clinician = role("Test Clinician", PrivilegeConstants.GET_PATIENTS);
		role("Test Exports", PrivilegeConstants.GET_PATIENTS);
		siteAdministrator = role("Test Site Administrator", PrivilegeConstants.GET_PATIENTS, PrivilegeConstants.GET_USERS,
		    PrivilegeConstants.ADD_USERS, PrivilegeConstants.EDIT_USERS, PrivilegeConstants.GET_ROLES);
		Role instanceAdministrator = role("Test Instance Administrator", ClinicUsers.ALL_CLINICS_PRIVILEGE);
		instanceAdministrator.getInheritedRoles().add(siteAdministrator);
		Context.getUserService().saveRole(instanceAdministrator);
		Context.getAdministrationService()
		        .saveGlobalProperty(new GlobalProperty(ClinicUsers.CLINICIAN_ROLES_PROPERTY, clinician.getUuid()));
		Context.getAdministrationService().saveGlobalProperty(
		    new GlobalProperty(ClinicUsers.SITE_ADMINISTRATOR_ROLES_PROPERTY, siteAdministrator.getUuid()));
		user("siteadmin", clinicA, siteAdministrator);
		user("instanceadmin", null, instanceAdministrator);
		user("clinicianatA", clinicA, clinician);
		user("clinicianatB", clinicB, clinician);
		user("clinicianatAB", clinicA + "," + clinicB, clinician);
	}
	
	@Test
	public void listsOnlyTheUsersSharingAClinicWithAClinicLimitedAdministrator() {
		signInAs("siteadmin");
		
		SimpleObject response = controller.getUsers();
		
		assertEquals(Boolean.TRUE, response.get("clinicLimited"));
		assertEquals(Collections.singletonList(clinicA), response.get("clinics"));
		assertEquals(Arrays.asList("clinicianatA", "clinicianatAB", "siteadmin"), sorted(usernames(response)));
	}
	
	@Test
	public void listsTheUsersAtTheClinicsTheDatabaseHoldsForTheAdministrator() {
		signInAs("siteadmin");
		// What another administrator's change does: the signed-in user's copy keeps its old clinics.
		Context.getRegisteredComponent("sessionFactory", SessionFactory.class).getCurrentSession().doWork(connection -> {
			try (PreparedStatement update = connection
			        .prepareStatement("update user_property set property_value = ? where property = ? and user_id = ?")) {
				update.setString(1, clinicB);
				update.setString(2, ClinicUsers.CLINICS_PROPERTY);
				update.setInt(3, Context.getAuthenticatedUser().getUserId());
				update.executeUpdate();
			}
		});
		
		SimpleObject response = controller.getUsers();
		
		assertEquals(Collections.singletonList(clinicB), response.get("clinics"));
		assertTrue(usernames(response).contains("clinicianatB"));
		assertFalse(usernames(response).contains("clinicianatA"));
	}
	
	@Test
	public void marksTheUsersAClinicLimitedAdministratorMayNotEdit() {
		user("otheradmin", clinicA, siteAdministrator);
		signInAs("siteadmin");
		
		Map<String, Object> editable = editable(controller.getUsers());
		
		assertEquals(Boolean.TRUE, editable.get("clinicianatA"));
		assertEquals(Boolean.TRUE, editable.get("clinicianatAB"));
		assertEquals(Boolean.FALSE, editable.get("siteadmin"));
		assertEquals(Boolean.FALSE, editable.get("otheradmin"));
	}
	
	@Test
	public void marksTheUsersWhoseRolesOutrankTheAdministratorsAsNotEditable() {
		user("clinicmanager", clinicA, role("Test Clinic Manager", PrivilegeConstants.MANAGE_LOCATIONS));
		
		signInAs("siteadmin");
		assertEquals(Boolean.FALSE, editable(controller.getUsers()).get("clinicmanager"));
		
		signInAs("instanceadmin");
		Map<String, Object> editable = editable(controller.getUsers());
		assertEquals(Boolean.FALSE, editable.get("admin"));
		assertEquals(Boolean.TRUE, editable.get("siteadmin"));
		assertEquals(Boolean.TRUE, editable.get("clinicianatB"));
	}
	
	@Test
	public void offersAClinicLimitedAdministratorOnlyTheClinicianRoles() {
		signInAs("siteadmin");
		
		List<String> roles = names(controller.getUsers().get("assignableRoles"));
		
		assertEquals(Collections.singletonList("Test Clinician"), roles);
	}
	
	@Test
	public void listsEveryUserAndOffersAdministratorRolesToAnAdministratorOfEveryClinic() {
		signInAs("instanceadmin");
		
		SimpleObject response = controller.getUsers();
		
		assertEquals(Boolean.FALSE, response.get("clinicLimited"));
		assertNull(response.get("clinics"));
		assertTrue(usernames(response).containsAll(Arrays.asList("clinicianatA", "clinicianatB", "siteadmin", "admin")));
		assertEquals(Arrays.asList("Test Clinician", "Test Site Administrator"),
		    sorted(names(response.get("assignableRoles"))));
	}
	
	@Test
	public void listsTheRolesWithoutGivingTheCallerManageRoles() {
		signInAs("siteadmin");
		AtomicBoolean held = new AtomicBoolean();
		AtomicBoolean probing = new AtomicBoolean();
		MethodInterceptor probe = invocation -> {
			// Checking a privilege calls the user service again.
			if (probing.compareAndSet(false, true)) {
				try {
					held.compareAndSet(false, Context.hasPrivilege(PrivilegeConstants.MANAGE_ROLES));
				}
				finally {
					probing.set(false);
				}
			}
			return invocation.proceed();
		};
		Context.addAdvice(UserService.class, probe);
		try {
			controller.getUsers();
		}
		finally {
			Context.removeAdvice(UserService.class, probe);
		}
		
		assertFalse(held.get());
	}
	
	@Test
	public void refusesAUserWhoCannotEditUsers() {
		signInAs("clinicianatA");
		
		try {
			controller.getUsers();
			fail("expected the request to be refused");
		}
		catch (APIAuthenticationException e) {
			assertTrue(e.getMessage().contains(PrivilegeConstants.EDIT_USERS));
		}
	}
	
	@SuppressWarnings("unchecked")
	private static List<SimpleObject> users(SimpleObject response) {
		return (List<SimpleObject>) response.get("users");
	}
	
	private static Map<String, Object> editable(SimpleObject response) {
		Map<String, Object> editable = new HashMap<>();
		for (SimpleObject user : users(response)) {
			editable.put((String) user.get("username"), user.get("editable"));
		}
		return editable;
	}
	
	private static List<String> usernames(SimpleObject response) {
		return users(response).stream().map(u -> (String) u.get("username")).collect(Collectors.toList());
	}
	
	@SuppressWarnings("unchecked")
	private static List<String> names(Object roles) {
		return ((List<SimpleObject>) roles).stream().map(r -> (String) r.get("name")).collect(Collectors.toList());
	}
	
	private static List<String> sorted(List<String> values) {
		return values.stream().sorted().collect(Collectors.toList());
	}
	
	private static Role role(String name, String... privileges) {
		UserService users = Context.getUserService();
		Role role = new Role(name);
		for (String privilege : privileges) {
			Privilege held = users.getPrivilege(privilege);
			role.addPrivilege(held != null ? held : users.savePrivilege(new Privilege(privilege)));
		}
		return users.saveRole(role);
	}
	
	private static void user(String username, String clinics, Role role) {
		Person person = new Person();
		person.addName(new PersonName(username, null, "Tester"));
		person.setGender("F");
		User user = new User(person);
		user.setUsername(username);
		user.addRole(role);
		if (clinics != null) {
			user.setUserProperty(ClinicUsers.CLINICS_PROPERTY, clinics);
		}
		Context.getUserService().createUser(user, "Tester123");
	}
	
	private static void signInAs(String username) {
		Context.logout();
		Context.authenticate(username, "Tester123");
	}
}
