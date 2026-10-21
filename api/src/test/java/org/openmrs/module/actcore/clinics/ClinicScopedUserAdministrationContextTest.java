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
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
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
import org.openmrs.test.BaseModuleContextSensitiveTest;
import org.openmrs.util.PrivilegeConstants;

public class ClinicScopedUserAdministrationContextTest extends BaseModuleContextSensitiveTest {
	
	private static final String CLINIC_A = "aaaaaaaa-0000-4000-8000-00000000000a";
	
	private static final String CLINIC_B = "bbbbbbbb-0000-4000-8000-00000000000b";
	
	private static final String CLINICIAN_ROLE = "60625a0c-3cea-4fe5-b6ca-615c743ad9d4";
	
	private final ClinicScopedUserAdministration advice = new ClinicScopedUserAdministration();
	
	private UserService users;
	
	private Role clinician;
	
	private Role siteAdmin;
	
	private Role instanceAdmin;
	
	private User siteAdminAtA;
	
	private User clinicianAtA;
	
	private User clinicianAtB;
	
	private User clinicianAtAAndB;
	
	private User otherSiteAdminAtA;
	
	@Before
	public void setUp() {
		users = Context.getUserService();
		Context.getAdministrationService().setGlobalProperty(UserClinics.ASSIGNABLE_ROLES_PROPERTY, CLINICIAN_ROLE);
		clinician = role("Organizational: ACT Clinician", CLINICIAN_ROLE);
		siteAdmin = role("Organizational: ACT Site Administrator", null, PrivilegeConstants.EDIT_USERS,
		    PrivilegeConstants.ADD_USERS, PrivilegeConstants.GET_USERS, PrivilegeConstants.GET_ROLES,
		    PrivilegeConstants.GET_PERSONS, PrivilegeConstants.ADD_PERSONS, PrivilegeConstants.EDIT_PERSONS,
		    PrivilegeConstants.EDIT_USER_PASSWORDS);
		instanceAdmin = role("Organizational: ACT Instance Administrator", null, PrivilegeConstants.EDIT_USERS,
		    PrivilegeConstants.ADD_USERS, PrivilegeConstants.GET_USERS, PrivilegeConstants.GET_ROLES,
		    PrivilegeConstants.GET_PERSONS, PrivilegeConstants.ADD_PERSONS, PrivilegeConstants.EDIT_PERSONS,
		    UserClinics.ALL_CLINICS_PRIVILEGE);
		siteAdminAtA = user("siteadmin", siteAdmin, CLINIC_A);
		clinicianAtA = user("clinicianA", clinician, CLINIC_A);
		clinicianAtB = user("clinicianB", clinician, CLINIC_B);
		clinicianAtAAndB = user("clinicianAB", clinician, CLINIC_B + "," + CLINIC_A);
		otherSiteAdminAtA = user("siteadmin2", siteAdmin, CLINIC_A);
		user("nobody", clinician, null);
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
	public void listsOnlyTheUsersOfTheirClinicsAndThemselves() {
		Context.becomeUser(siteAdminAtA.getSystemId());
		
		assertThat(usernames(users.getAllUsers()),
		    containsInAnyOrder("siteadmin", "clinicianA", "clinicianAB", "siteadmin2"));
		assertThat(usernames(users.getUsers("clinician", null, false)), containsInAnyOrder("clinicianA", "clinicianAB"));
	}
	
	@Test
	public void pagesTheVisibleUsersAndCountsThem() {
		Context.becomeUser(siteAdminAtA.getSystemId());
		
		int count = users.getCountOfUsers("clinician", null, false);
		List<User> first = users.getUsers("clinician", null, false, 0, 1);
		List<User> second = users.getUsers("clinician", null, false, 1, 1);
		
		assertEquals(2, count);
		assertEquals(1, first.size());
		assertEquals(1, second.size());
		assertThat(usernames(concat(first, second)), containsInAnyOrder("clinicianA", "clinicianAB"));
		assertTrue(users.getUsers("clinician", null, false, 2, 1).isEmpty());
	}
	
	@Test
	public void changesAUserOfTheirClinic() {
		Context.becomeUser(siteAdminAtA.getSystemId());
		User user = users.getUserByUuid(clinicianAtA.getUuid());
		user.getPerson().getPersonName().setGivenName("Renamed");
		
		users.saveUser(user);
		users.changePassword(user, "Changed123");
		users.retireUser(user, "Left the clinic");
		
		assertTrue(users.getUserByUuid(clinicianAtA.getUuid()).getRetired());
	}
	
	@Test
	public void refusesToChangeAUserOfAnotherClinic() {
		Context.becomeUser(siteAdminAtA.getSystemId());
		User user = users.getUserByUuid(clinicianAtB.getUuid());
		
		refused(() -> users.changePassword(user, "Changed123"), "not at one of your clinics");
		refused(() -> users.retireUser(user, "No"), "not at one of your clinics");
		user.setUserProperty(UserClinics.PROPERTY, CLINIC_A);
		refused(() -> users.saveUser(user), "not at one of your clinics");
	}
	
	@Test
	public void refusesToMoveAUserOfAnotherClinicIntoTheirsWhenQueriesRunBeforeTheSave() {
		Context.becomeUser(siteAdminAtA.getSystemId());
		User user = users.getUserByUuid(clinicianAtB.getUuid());
		
		// As the REST layer sets the fields, then looks up each role and the person before it saves: the
		// lookups flush the edit, and the refusal comes from whichever call writes it first.
		refused(() -> {
			user.setUserProperty(UserClinics.PROPERTY, CLINIC_A);
			users.getUsersByName("clinicianB", "Tester", false);
			Context.getPersonService().getPersonByUuid(user.getPerson().getUuid());
			users.saveUser(user);
			Context.flushSession();
		}, "not at one of your clinics");
	}
	
	@Test
	public void refusesAnEditToAnotherClinicsUserEvenWhenNoServiceSavesIt() {
		Context.becomeUser(siteAdminAtA.getSystemId());
		User user = users.getUserByUuid(clinicianAtB.getUuid());
		user.setUserProperty(UserClinics.PROPERTY, CLINIC_A);
		
		refused(Context::flushSession, "not at one of your clinics");
	}
	
	@Test
	public void refusesClinicsThatAreNotTheirsOrNoClinicAtAll() {
		Context.becomeUser(siteAdminAtA.getSystemId());
		
		User user = users.getUserByUuid(clinicianAtA.getUuid());
		user.setUserProperty(UserClinics.PROPERTY, CLINIC_A + "," + CLINIC_B);
		refused(() -> users.saveUser(user), "only your own clinics");
		refused(() -> users.setUserProperty(user, UserClinics.PROPERTY, CLINIC_B), "only your own clinics");
		refused(() -> users.removeUserProperty(user, UserClinics.PROPERTY), "at least one of your clinics");
	}
	
	@Test
	public void refusesARoleTheyCannotGiveOut() {
		Context.becomeUser(siteAdminAtA.getSystemId());
		User user = users.getUserByUuid(clinicianAtA.getUuid());
		user.addRole(users.getRoleByUuid(siteAdmin.getUuid()));
		
		refused(() -> users.saveUser(user), "only the clinician roles: Clinician");
	}
	
	@Test
	public void refusesToChangeAUserWithARoleTheyCannotGiveOut() {
		Context.becomeUser(siteAdminAtA.getSystemId());
		User user = users.getUserByUuid(otherSiteAdminAtA.getUuid());
		
		refused(() -> users.changePassword(user, "Changed123"), "only an instance administrator");
	}
	
	@Test
	public void createsAClinicianAtTheirClinic() {
		Context.becomeUser(siteAdminAtA.getSystemId());
		
		User created = users.createUser(newUser("newclinician", clinician, CLINIC_A), "Created123");
		
		assertEquals(CLINIC_A, users.getUserByUuid(created.getUuid()).getUserProperty(UserClinics.PROPERTY));
		refused(() -> users.createUser(newUser("elsewhere", clinician, CLINIC_B), "Created123"), "only your own clinics");
		refused(() -> users.createUser(newUser("noclinic", clinician, null), "Created123"), "at least one");
	}
	
	@Test
	public void savesTheirOwnAccountButNotTheirOwnClinicsOrRoles() {
		Context.becomeUser(siteAdminAtA.getSystemId());
		User me = users.getUserByUuid(siteAdminAtA.getUuid());
		
		// As the login page saves a default location: every other property sent back unchanged.
		me.setUserProperty("defaultLocation", CLINIC_A);
		users.saveUser(me);
		users.saveUserProperty("defaultLocation", CLINIC_A);
		
		refused(() -> users.saveUserProperty(UserClinics.PROPERTY, CLINIC_A + "," + CLINIC_B), "your own clinics");
		Map<String, String> without = new HashMap<String, String>();
		without.put("defaultLocation", CLINIC_A);
		refused(() -> users.saveUserProperties(without), "your own clinics");
		me.setUserProperty(UserClinics.PROPERTY, CLINIC_A + "," + CLINIC_B);
		refused(() -> users.saveUser(me), "your own clinics");
		me.setUserProperty(UserClinics.PROPERTY, CLINIC_A);
		me.addRole(users.getRoleByUuid(clinician.getUuid()));
		refused(() -> users.saveUser(me), "your own roles");
	}
	
	@Test
	public void leavesAnAdministratorOfEveryClinicUnlimited() {
		User admin = user("instanceadmin", instanceAdmin, null);
		Context.flushSession();
		Context.becomeUser(admin.getSystemId());
		
		assertFalse(UserClinics.isLimited(Context.getAuthenticatedUser()));
		assertTrue(usernames(users.getAllUsers()).contains("clinicianB"));
		User user = users.getUserByUuid(clinicianAtB.getUuid());
		user.setUserProperty(UserClinics.PROPERTY, CLINIC_A);
		users.saveUser(user);
	}
	
	@Test
	public void readsTheClinicsOfAnAccount() {
		assertThat(new ArrayList<String>(UserClinics.parse(" a, ,b,a ")), containsInAnyOrder("a", "b"));
		assertThat(new ArrayList<String>(UserClinics.stored(clinicianAtAAndB.getUserId())),
		    containsInAnyOrder(CLINIC_A, CLINIC_B));
		assertTrue(UserClinics.isLimited(siteAdminAtA));
		assertFalse(UserClinics.isLimited(clinicianAtA));
	}
	
	private interface Call {
		
		void run();
	}
	
	private static void refused(Call call, String message) {
		try {
			call.run();
			fail("Expected a refusal: " + message);
		}
		catch (APIAuthenticationException e) {
			assertThat(e.getMessage(), containsString(message));
		}
	}
	
	private Role role(String name, String uuid, String... privileges) {
		Role role = new Role(name);
		role.setDescription(name);
		if (uuid != null) {
			role.setUuid(uuid);
		}
		for (String name2 : privileges) {
			Privilege privilege = users.getPrivilege(name2);
			if (privilege == null) {
				privilege = users.savePrivilege(new Privilege(name2, name2));
			}
			role.addPrivilege(privilege);
		}
		return users.saveRole(role);
	}
	
	private User user(String username, Role role, String clinics) {
		return users.createUser(newUser(username, role, clinics), "Password123");
	}
	
	private static User newUser(String username, Role role, String clinics) {
		Person person = new Person();
		person.addName(new PersonName(username, null, "Tester"));
		person.setGender("F");
		User user = new User(person);
		user.setUsername(username);
		user.addRole(role);
		if (clinics != null) {
			user.setUserProperty(UserClinics.PROPERTY, clinics);
		}
		return user;
	}
	
	private static List<String> usernames(List<User> list) {
		List<String> names = new ArrayList<String>();
		for (User user : list) {
			names.add(user.getUsername());
		}
		return names;
	}
	
	private static List<User> concat(List<User> first, List<User> second) {
		List<User> all = new ArrayList<User>(first);
		all.addAll(second);
		return all;
	}
}
