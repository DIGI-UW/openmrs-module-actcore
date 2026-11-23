/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.clinics;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openmrs.Person;
import org.openmrs.PersonName;
import org.openmrs.Privilege;
import org.openmrs.Role;
import org.openmrs.User;
import org.openmrs.api.APIException;
import org.openmrs.api.UserService;
import org.openmrs.api.context.Context;
import org.openmrs.test.BaseModuleContextSensitiveTest;
import org.openmrs.util.PrivilegeConstants;
import org.openmrs.util.RoleConstants;

/**
 * Checks that an instance administrator, who holds the privileges of the roles it gives out as the
 * distribution grants them, can give those roles under OpenMRS's own rule and no others.
 */
public class AdministratorRolesContextTest extends BaseModuleContextSensitiveTest {
	
	private static final String CLINICAL_PRIVILEGE = "Task: act.test.clinical";
	
	private static final String CLERK_PRIVILEGE = "Task: act.test.clerk";
	
	private static final String GLOBAL_PRIVILEGE = "Task: act.test.global";
	
	private final ClinicScopedUserAdministration advice = new ClinicScopedUserAdministration();
	
	private UserService users;
	
	private Role clinician;
	
	private Role siteAdministrator;
	
	private Role globalAdministrator;
	
	private User instanceAdministrator;
	
	@Before
	public void setUp() {
		users = Context.getUserService();
		clinician = role("Test Clinician", null, CLINICAL_PRIVILEGE);
		siteAdministrator = role("Test Site Administrator", clinician, CLERK_PRIVILEGE, PrivilegeConstants.ADD_USERS);
		globalAdministrator = role("Test Global Administrator", null, GLOBAL_PRIVILEGE);
		Role instance = role("Test Instance Administrator", null, CLINICAL_PRIVILEGE, CLERK_PRIVILEGE,
		    PrivilegeConstants.ADD_USERS, PrivilegeConstants.EDIT_USERS, PrivilegeConstants.GET_USERS,
		    PrivilegeConstants.GET_ROLES, PrivilegeConstants.GET_PERSONS, PrivilegeConstants.ADD_PERSONS,
		    PrivilegeConstants.EDIT_PERSONS, PrivilegeConstants.GET_GLOBAL_PROPERTIES, UserClinics.ALL_CLINICS_PRIVILEGE);
		instanceAdministrator = users.createUser(newUser("instanceadmin", instance), "Password123");
		Context.flushSession();
		Context.addAdvice(UserService.class, advice);
	}
	
	@After
	public void tearDown() {
		Context.removeAdvice(UserService.class, advice);
		Context.logout();
		authenticate();
	}
	
	@Test
	public void createsAClinicianAndASiteAdministrator() {
		Context.becomeUser(instanceAdministrator.getSystemId());
		
		User created = users.createUser(newUser("newclinician", clinician), "Created123");
		users.createUser(newUser("newsiteadmin", siteAdministrator), "Created123");
		
		assertTrue(users.getUserByUuid(created.getUuid()).hasRole(clinician.getRole()));
	}
	
	@Test
	public void refusesARoleWhosePrivilegesTheyDoNotHold() {
		Context.becomeUser(instanceAdministrator.getSystemId());
		
		refused(newUser("newglobaladmin", globalAdministrator), "You must have privilege");
	}
	
	@Test
	public void refusesSystemDeveloper() {
		Context.becomeUser(instanceAdministrator.getSystemId());
		
		refused(newUser("newdeveloper", users.getRole(RoleConstants.SUPERUSER)), "You must have the role");
	}
	
	private void refused(User user, String message) {
		try {
			users.createUser(user, "Created123");
			fail("Expected OpenMRS to refuse: " + message);
		}
		catch (APIException e) {
			assertThat(e.getMessage(), containsString(message));
		}
	}
	
	private Role role(String name, Role inherited, String... privileges) {
		Role role = new Role(name);
		role.setDescription(name);
		if (inherited != null) {
			role.getInheritedRoles().add(inherited);
		}
		for (String privilegeName : privileges) {
			Privilege privilege = users.getPrivilege(privilegeName);
			if (privilege == null) {
				privilege = users.savePrivilege(new Privilege(privilegeName, privilegeName));
			}
			role.addPrivilege(privilege);
		}
		return users.saveRole(role);
	}
	
	private static User newUser(String username, Role role) {
		Person person = new Person();
		person.addName(new PersonName(username, null, "Tester"));
		person.setGender("F");
		User user = new User(person);
		user.setUsername(username);
		user.addRole(role);
		return user;
	}
}
