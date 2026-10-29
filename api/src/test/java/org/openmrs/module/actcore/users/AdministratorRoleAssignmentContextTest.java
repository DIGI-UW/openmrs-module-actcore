/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.users;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
import org.openmrs.module.actcore.clinics.UserClinics;
import org.openmrs.test.BaseModuleContextSensitiveTest;
import org.openmrs.util.PrivilegeConstants;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs the advice as administrators who lack the clinical privileges of the roles they give, on
 * committed data that each test removes by name.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class AdministratorRoleAssignmentContextTest extends BaseModuleContextSensitiveTest {
	
	private static final String CLINICIAN = "60625a0c-3cea-4fe5-b6ca-615c743ad9d4";
	
	private static final String COMMUNITY_CLINICIAN = "b9ab4b76-e7b9-46a7-b4ec-5b7fdd8fa540";
	
	private static final String DATA_CLERK = "61e9bb28-11df-4df1-965d-11ef928d5239";
	
	private static final String SITE_ADMINISTRATOR = "a1295a20-7273-4339-b5df-2f8a3a841c77";
	
	private static final String CLINICAL_PRIVILEGE = "Task: act.test.clinical";
	
	private static final String CLERK_PRIVILEGE = "Task: act.test.clerk";
	
	private static final String INSTANCE_PRIVILEGE = "Task: act.test.instance";
	
	private static final String GLOBAL_PRIVILEGE = "Task: act.test.global";
	
	private static final String ADMINISTERS_USERS = PrivilegeConstants.ADD_USERS + "," + PrivilegeConstants.EDIT_USERS + ","
	        + PrivilegeConstants.GET_USERS + "," + PrivilegeConstants.GET_ROLES + "," + PrivilegeConstants.GET_PERSONS + ","
	        + PrivilegeConstants.ADD_PERSONS + "," + PrivilegeConstants.EDIT_PERSONS;
	
	private final AdministratorRoleAssignment advice = new AdministratorRoleAssignment();
	
	private UserService users;
	
	private User instanceAdmin;
	
	private User globalAdmin;
	
	private User siteAdmin;
	
	private final List<Integer> createdUsers = new ArrayList<Integer>();
	
	private final List<String> createdRoles = new ArrayList<String>();
	
	private final List<String> createdPrivileges = new ArrayList<String>();
	
	@Before
	public void setUp() {
		users = Context.getUserService();
		setProperty(AdministratorRoleAssignment.INSTANCE_ROLES_PROPERTY,
		    SITE_ADMINISTRATOR + "," + COMMUNITY_CLINICIAN + "," + CLINICIAN);
		setProperty(AdministratorRoleAssignment.GLOBAL_ROLES_PROPERTY,
		    AdministratorRoleAssignment.GLOBAL_ADMINISTRATOR_ROLE + ","
		            + AdministratorRoleAssignment.INSTANCE_ADMINISTRATOR_ROLE + "," + SITE_ADMINISTRATOR + ","
		            + COMMUNITY_CLINICIAN + "," + CLINICIAN);
		Role clinician = role("Test Clinician", CLINICIAN, null, CLINICAL_PRIVILEGE);
		role("Test Community Clinician", COMMUNITY_CLINICIAN, null, CLINICAL_PRIVILEGE);
		role("Test Data Clerk", DATA_CLERK, null, CLERK_PRIVILEGE);
		role("Test Site Administrator", SITE_ADMINISTRATOR, clinician, ADMINISTERS_USERS);
		Role instance = role("Test Instance Administrator", AdministratorRoleAssignment.INSTANCE_ADMINISTRATOR_ROLE, null,
		    ADMINISTERS_USERS + "," + UserClinics.ALL_CLINICS_PRIVILEGE + "," + INSTANCE_PRIVILEGE);
		Role global = role("Test Global Administrator", AdministratorRoleAssignment.GLOBAL_ADMINISTRATOR_ROLE, null,
		    ADMINISTERS_USERS + "," + UserClinics.ALL_CLINICS_PRIVILEGE + "," + GLOBAL_PRIVILEGE);
		instanceAdmin = user("instanceadmin", instance);
		globalAdmin = user("globaladmin", global);
		siteAdmin = user("siteadmin", users.getRoleByUuid(SITE_ADMINISTRATOR));
		Context.addAdvice(UserService.class, advice);
	}
	
	@After
	public void tearDown() throws Exception {
		Context.removeAdvice(UserService.class, advice);
		Context.logout();
		authenticate();
		for (User created : users.getUsers("zz128-", null, true)) {
			purge(created);
		}
		Collections.reverse(createdUsers);
		for (Integer id : createdUsers) {
			purge(users.getUser(id));
		}
		Context.getAdministrationService().executeSQL("delete from role_role where parent_role like 'Test %'", false);
		Context.getAdministrationService().executeSQL("delete from role_privilege where role like 'Test %'", false);
		for (String name : createdRoles) {
			Context.getAdministrationService().executeSQL("delete from role where role = '" + name + "'", false);
		}
		for (String name : createdPrivileges) {
			Context.getAdministrationService().executeSQL("delete from privilege where privilege = '" + name + "'", false);
		}
		Context.getAdministrationService().executeSQL(
		    "delete from global_property where property in ('" + AdministratorRoleAssignment.INSTANCE_ROLES_PROPERTY + "','"
		            + AdministratorRoleAssignment.GLOBAL_ROLES_PROPERTY + "')",
		    false);
	}
	
	private void purge(User user) {
		Person person = user.getPerson();
		users.purgeUser(user);
		Context.getPersonService().purgePerson(person);
	}
	
	@Test
	public void leavesTheClinicianToOpenMRSWhenTheAdviceIsNotInstalled() {
		Context.removeAdvice(UserService.class, advice);
		Context.becomeUser(instanceAdmin.getSystemId());
		
		refused(() -> users.createUser(newUser("zz128-core", CLINICIAN), "Created123"), "in order to assign");
	}
	
	@Test
	public void letsTheInstanceAdministratorCreateAClinicianAsThemselves() {
		Context.becomeUser(instanceAdmin.getSystemId());
		
		User created = users.createUser(newUser("zz128-clinician", CLINICIAN), "Created123");
		
		User saved = users.getUserByUuid(created.getUuid());
		assertEquals(instanceAdmin.getUserId(), saved.getCreator().getUserId());
		assertTrue(saved.hasRole("Test Clinician"));
	}
	
	@Test
	public void letsTheInstanceAdministratorCreateASiteAdministratorAndChangeAClinician() {
		Context.becomeUser(instanceAdmin.getSystemId());
		
		User site = users.createUser(newUser("zz128-site", SITE_ADMINISTRATOR), "Created123");
		User clinician = users.createUser(newUser("zz128-edit", CLINICIAN), "Created123");
		User edited = users.getUserByUuid(clinician.getUuid());
		edited.addRole(users.getRoleByUuid(COMMUNITY_CLINICIAN));
		users.saveUser(edited);
		
		User saved = users.getUserByUuid(clinician.getUuid());
		assertTrue(saved.hasRole("Test Clinician") && saved.hasRole("Test Community Clinician"));
	}
	
	@Test
	public void refusesTheInstanceAdministratorTheDataClerkRole() {
		Context.becomeUser(instanceAdmin.getSystemId());
		
		refused(() -> users.createUser(newUser("zz128-clerk", DATA_CLERK), "Created123"), "in order to assign");
	}
	
	@Test
	public void refusesTheInstanceAdministratorTheGlobalAdministratorRole() {
		Context.becomeUser(instanceAdmin.getSystemId());
		
		refused(() -> users.createUser(newUser("zz128-global", AdministratorRoleAssignment.GLOBAL_ADMINISTRATOR_ROLE),
		    "Created123"), "in order to assign");
	}
	
	@Test
	public void refusesTheInstanceAdministratorAWholeSetThatHoldsARoleThatIsNotTheirs() {
		Context.becomeUser(instanceAdmin.getSystemId());
		
		refused(() -> users.createUser(newUser("zz128-mixed", CLINICIAN, DATA_CLERK), "Created123"), "in order to assign");
	}
	
	@Test
	public void refusesTheInstanceAdministratorAChangeToAUserWithARoleThatIsNotTheirs() {
		User clerk = users.createUser(newUser("zz128-held", DATA_CLERK), "Password123");
		Context.becomeUser(instanceAdmin.getSystemId());
		User edited = users.getUserByUuid(clerk.getUuid());
		edited.addRole(users.getRoleByUuid(CLINICIAN));
		
		refused(() -> users.saveUser(edited), "in order to assign");
	}
	
	@Test
	public void refusesAnAdministratorARoleOnTheirOwnAccount() {
		Context.becomeUser(globalAdmin.getSystemId());
		Context.evictFromSession(Context.getAuthenticatedUser());
		User me = users.getUserByUuid(globalAdmin.getUuid());
		me.addRole(users.getRoleByUuid(CLINICIAN));
		
		refused(() -> users.saveUser(me), "in order to assign");
	}
	
	@Test
	public void letsTheGlobalAdministratorGiveAllFiveRolesAndNoOther() {
		Context.becomeUser(globalAdmin.getSystemId());
		
		String[] roles = { AdministratorRoleAssignment.GLOBAL_ADMINISTRATOR_ROLE,
		        AdministratorRoleAssignment.INSTANCE_ADMINISTRATOR_ROLE, SITE_ADMINISTRATOR, COMMUNITY_CLINICIAN,
		        CLINICIAN };
		for (int i = 0; i < roles.length; i++) {
			users.createUser(newUser("zz128-global" + i, roles[i]), "Created123");
		}
		
		refused(() -> users.createUser(newUser("zz128-gclerk", DATA_CLERK), "Created123"), "in order to assign");
	}
	
	@Test
	public void leavesRolesToOpenMRSWhenTheAdministratorsPropertyIsEmpty() {
		setProperty(AdministratorRoleAssignment.INSTANCE_ROLES_PROPERTY, "");
		Context.becomeUser(instanceAdmin.getSystemId());
		
		refused(() -> users.createUser(newUser("zz128-empty", CLINICIAN), "Created123"), "in order to assign");
	}
	
	@Test
	public void leavesAnAdministratorWhoIsNeitherInstanceNorGlobalToOpenMRS() {
		setProperty(AdministratorRoleAssignment.INSTANCE_ROLES_PROPERTY, SITE_ADMINISTRATOR + "," + DATA_CLERK);
		Context.becomeUser(siteAdmin.getSystemId());
		
		users.createUser(newUser("zz128-bysite", CLINICIAN), "Created123");
		refused(() -> users.createUser(newUser("zz128-bysite2", DATA_CLERK), "Created123"), "in order to assign");
	}
	
	private interface Call {
		
		void run();
	}
	
	private static void refused(Call call, String message) {
		try {
			call.run();
			fail("Expected a refusal: " + message);
		}
		catch (APIException e) {
			assertThat(e.getMessage(), containsString(message));
		}
	}
	
	private static void setProperty(String property, String value) {
		Context.getAdministrationService().setGlobalProperty(property, value);
	}
	
	private Role role(String name, String uuid, Role inherited, String privileges) {
		Role role = new Role(name);
		role.setDescription(name);
		role.setUuid(uuid);
		if (inherited != null) {
			role.getInheritedRoles().add(inherited);
		}
		for (String privilegeName : privileges.split(",")) {
			Privilege privilege = users.getPrivilege(privilegeName);
			if (privilege == null) {
				privilege = users.savePrivilege(new Privilege(privilegeName, privilegeName));
				createdPrivileges.add(privilegeName);
			}
			role.addPrivilege(privilege);
		}
		createdRoles.add(name);
		return users.saveRole(role);
	}
	
	private User user(String username, Role role) {
		User created = users.createUser(newUser(username, role.getUuid()), "Password123");
		createdUsers.add(created.getUserId());
		return created;
	}
	
	private User newUser(String username, String... roleUuids) {
		Person person = new Person();
		person.addName(new PersonName(username, null, "Tester"));
		person.setGender("F");
		User user = new User(person);
		user.setUsername(username);
		for (String uuid : roleUuids) {
			user.addRole(users.getRoleByUuid(uuid));
		}
		return user;
	}
}
