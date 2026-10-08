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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
import org.springframework.aop.framework.ProxyFactory;

public class ClinicLimitedUserManagementContextTest extends BaseModuleContextSensitiveTest {
	
	private UserService users;
	
	private String clinicA;
	
	private String clinicB;
	
	private Role clinician;
	
	private Role siteAdministrator;
	
	@Before
	public void setUp() {
		clinicA = Context.getLocationService().getLocation(1).getUuid();
		clinicB = Context.getLocationService().getLocation(2).getUuid();
		clinician = role("Test Clinician", PrivilegeConstants.GET_PATIENTS);
		siteAdministrator = role("Test Site Administrator", PrivilegeConstants.GET_PATIENTS, PrivilegeConstants.GET_USERS,
		    PrivilegeConstants.ADD_USERS, PrivilegeConstants.EDIT_USERS, PrivilegeConstants.GET_ROLES,
		    PrivilegeConstants.EDIT_USER_PASSWORDS);
		Role instanceAdministrator = role("Test Instance Administrator", ClinicUsers.ALL_CLINICS_PRIVILEGE);
		instanceAdministrator.getInheritedRoles().add(siteAdministrator);
		Context.getUserService().saveRole(instanceAdministrator);
		
		save(newUser("siteadmin", clinicA, siteAdministrator));
		save(newUser("otheradmin", clinicA, siteAdministrator));
		save(newUser("noclinicadmin", null, siteAdministrator));
		save(newUser("instanceadmin", null, instanceAdministrator));
		save(newUser("clinicianatA", clinicA, clinician));
		save(newUser("clinicianatB", clinicB, clinician));
		save(newUser("clinicianatAB", clinicA + "," + clinicB, clinician));
		// The check reads what the database holds, so the fixture must be there, not only in the session.
		Context.flushSession();
		
		wrapTheUserService();
	}
	
	private void wrapTheUserService() {
		// The user service as the module's advice wraps it at runtime.
		ProxyFactory proxy = new ProxyFactory(Context.getUserService());
		proxy.addInterface(UserService.class);
		proxy.addAdvice(new ClinicLimitedUserManagement());
		users = (UserService) proxy.getProxy();
		signInAs("siteadmin");
	}
	
	@Test
	public void editsAUserAtItsClinic() {
		User user = users.getUserByUsername("clinicianatA");
		user.setUserProperty("defaultLocale", "fr");
		
		users.saveUser(user);
		users.retireUser(user, "left the clinic");
		users.changePassword(user, "Changed123");
	}
	
	@Test
	public void refusesAUserAtAnotherClinic() {
		User user = Context.getUserService().getUserByUsername("clinicianatB");
		
		refused(() -> users.saveUser(user), "is not at your clinics");
		refused(() -> users.retireUser(user, "gone"), "is not at your clinics");
		refused(() -> users.changePassword(user, "Changed123"), "is not at your clinics");
		refused(() -> users.setUserProperty(user, "defaultLocale", "fr"), "is not at your clinics");
	}
	
	@Test
	public void refusesMovingAUserFromAnotherClinicToItsOwn() {
		// What REST and the legacy form do: change the loaded user, then save it.
		User user = Context.getUserService().getUserByUsername("clinicianatB");
		user.setUserProperty(ClinicUsers.CLINICS_PROPERTY, clinicA);
		
		refused(() -> users.saveUser(user), "is not at your clinics");
		refused(() -> users.setUserProperty(Context.getUserService().getUserByUsername("clinicianatB"),
		    ClinicUsers.CLINICS_PROPERTY, clinicA), "is not at your clinics");
	}
	
	@Test
	public void createsAUserAtItsClinic() {
		User created = users.createUser(newUser("newclinician", clinicA, clinician), "Created123");
		
		assertEquals(clinicA, created.getUserProperty(ClinicUsers.CLINICS_PROPERTY));
	}
	
	@Test
	public void refusesCreatingAUserWithoutItsClinics() {
		refused(() -> users.createUser(newUser("noclinic", null, clinician), "Created123"), "at least one of your clinics");
		refused(() -> users.createUser(newUser("atb", clinicB, clinician), "Created123"), "only your own clinics");
	}
	
	@Test
	public void refusesGivingARoleThatManagesUsers() {
		refused(() -> users.createUser(newUser("newadmin", clinicA, siteAdministrator), "Created123"),
		    "roles that do not manage users");
		
		User user = Context.getUserService().getUserByUsername("clinicianatA");
		user.addRole(siteAdministrator);
		refused(() -> users.saveUser(user), "roles that do not manage users");
	}
	
	@Test
	public void refusesEditingAnotherAdministratorAtItsClinic() {
		User other = Context.getUserService().getUserByUsername("otheradmin");
		other.removeRole(siteAdministrator);
		
		refused(() -> users.saveUser(other), "administers users");
	}
	
	@Test
	public void changesOnlyItsOwnClinicsOfAUserAtTwo() {
		User user = Context.getUserService().getUserByUsername("clinicianatAB");
		user.setUserProperty(ClinicUsers.CLINICS_PROPERTY, clinicA);
		refused(() -> users.saveUser(user), "only your own clinics");
		
		User again = Context.getUserService().getUserByUsername("clinicianatAB");
		again.setUserProperty(ClinicUsers.CLINICS_PROPERTY, clinicB);
		users.saveUser(again);
	}
	
	@Test
	public void savesItsOwnPreferencesButNotItsOwnClinicsOrRoles() {
		User me = Context.getUserService().getUserByUsername("siteadmin");
		me.setUserProperty("defaultLocation", clinicA);
		users.saveUser(me);
		
		me.setUserProperty(ClinicUsers.CLINICS_PROPERTY, clinicA + "," + clinicB);
		refused(() -> users.saveUser(me), "your own clinics or roles");
	}
	
	@Test
	public void leavesAnAdministratorOfEveryClinicUnlimited() {
		signInAs("instanceadmin");
		User user = Context.getUserService().getUserByUsername("clinicianatB");
		user.setUserProperty(ClinicUsers.CLINICS_PROPERTY, clinicA);
		
		users.saveUser(user);
		users.createUser(newUser("newsiteadmin", clinicB, siteAdministrator), "Created123");
	}
	
	@Test
	public void refusesAClinicLimitedAdministratorWithNoClinics() {
		signInAs("noclinicadmin");
		
		refused(() -> users.saveUser(Context.getUserService().getUserByUsername("clinicianatA")), "You have no clinics");
	}
	
	private static Role role(String name, String... privileges) {
		UserService service = Context.getUserService();
		Role role = new Role(name);
		for (String privilege : privileges) {
			Privilege held = service.getPrivilege(privilege);
			role.addPrivilege(held != null ? held : service.savePrivilege(new Privilege(privilege)));
		}
		return service.saveRole(role);
	}
	
	private static User newUser(String username, String clinics, Role role) {
		Person person = new Person();
		person.addName(new PersonName(username, null, "Tester"));
		person.setGender("F");
		User user = new User(person);
		user.setUsername(username);
		user.addRole(role);
		if (clinics != null) {
			user.setUserProperty(ClinicUsers.CLINICS_PROPERTY, clinics);
		}
		return user;
	}
	
	private static void save(User user) {
		Context.getUserService().createUser(user, "Tester123");
	}
	
	private static void signInAs(String username) {
		Context.flushSession();
		Context.logout();
		Context.authenticate(username, "Tester123");
	}
	
	private static void refused(Runnable change, String reason) {
		try {
			change.run();
			fail("expected the change to be refused: " + reason);
		}
		catch (APIAuthenticationException e) {
			assertTrue(e.getMessage(), e.getMessage().contains(reason));
		}
	}
}
