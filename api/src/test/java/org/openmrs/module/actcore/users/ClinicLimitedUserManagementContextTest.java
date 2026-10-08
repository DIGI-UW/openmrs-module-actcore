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

import java.util.Collections;

import org.aopalliance.intercept.MethodInterceptor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openmrs.GlobalProperty;
import org.openmrs.Person;
import org.openmrs.PersonName;
import org.openmrs.Privilege;
import org.openmrs.Provider;
import org.openmrs.Role;
import org.openmrs.User;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.AdministrationService;
import org.openmrs.api.PersonService;
import org.openmrs.api.ProviderService;
import org.openmrs.api.UserService;
import org.openmrs.api.context.Context;
import org.openmrs.test.BaseModuleContextSensitiveTest;
import org.openmrs.util.PrivilegeConstants;

public class ClinicLimitedUserManagementContextTest extends BaseModuleContextSensitiveTest {
	
	private UserService users;
	
	private String clinicA;
	
	private String clinicB;
	
	private Role clinician;
	
	private Role siteAdministrator;
	
	private Role instanceAdministrator;
	
	private Role exports;
	
	private Provider providerAtB;
	
	private final ClinicLimitedUserManagement userGuard = new ClinicLimitedUserManagement();
	
	private final ClinicLimitedPersonManagement personGuard = new ClinicLimitedPersonManagement();
	
	// Core 2.8 reads a global property only for Get Global Properties, which an administrator lacks.
	private final MethodInterceptor coreTwoEightAuthorization = invocation -> {
		Object[] args = invocation.getArguments();
		if ("getGlobalProperty".equals(invocation.getMethod().getName())
		        && String.valueOf(args[0]).startsWith("actcore.users.")
		        && !Context.hasPrivilege(PrivilegeConstants.GET_GLOBAL_PROPERTIES)) {
			throw new APIAuthenticationException("Privilege required: " + PrivilegeConstants.GET_GLOBAL_PROPERTIES);
		}
		return invocation.proceed();
	};
	
	@Before
	public void setUp() {
		clinicA = Context.getLocationService().getLocation(1).getUuid();
		clinicB = Context.getLocationService().getLocation(2).getUuid();
		exports = role("Test Exports", PrivilegeConstants.GET_PATIENTS);
		clinician = role("Test Clinician", PrivilegeConstants.GET_PATIENTS);
		clinician.getInheritedRoles().add(exports);
		Context.getUserService().saveRole(clinician);
		siteAdministrator = role("Test Site Administrator", PrivilegeConstants.GET_PATIENTS, PrivilegeConstants.GET_USERS,
		    PrivilegeConstants.ADD_USERS, PrivilegeConstants.EDIT_USERS, PrivilegeConstants.GET_ROLES,
		    PrivilegeConstants.EDIT_USER_PASSWORDS, PrivilegeConstants.ADD_PERSONS, PrivilegeConstants.EDIT_PERSONS,
		    PrivilegeConstants.GET_PERSONS, PrivilegeConstants.MANAGE_PROVIDERS, PrivilegeConstants.GET_PROVIDERS);
		instanceAdministrator = role("Test Instance Administrator", ClinicUsers.ALL_CLINICS_PRIVILEGE);
		instanceAdministrator.getInheritedRoles().add(siteAdministrator);
		Context.getUserService().saveRole(instanceAdministrator);
		property(ClinicUsers.CLINICIAN_ROLES_PROPERTY, clinician.getUuid());
		property(ClinicUsers.SITE_ADMINISTRATOR_ROLES_PROPERTY, siteAdministrator.getUuid());
		
		save(newUser("siteadmin", clinicA, siteAdministrator));
		save(newUser("otheradmin", clinicA, siteAdministrator));
		save(newUser("noclinicadmin", null, siteAdministrator));
		save(newUser("instanceadmin", null, instanceAdministrator));
		save(newUser("clinicianatA", clinicA, clinician));
		save(newUser("clinicianatB", clinicB, clinician));
		save(newUser("clinicianatAB", clinicA + "," + clinicB, clinician));
		providerAtB = new Provider();
		providerAtB.setPerson(Context.getUserService().getUserByUsername("clinicianatB").getPerson());
		providerAtB.setIdentifier("providerAtB");
		Context.getProviderService().saveProvider(providerAtB);
		// The check reads what the database holds, so the fixture must be there, not only in the session.
		Context.flushSession();
		
		Context.addAdvice(UserService.class, userGuard);
		Context.addAdvice(PersonService.class, personGuard);
		Context.addAdvice(ProviderService.class, personGuard);
		Context.addAdvice(AdministrationService.class, coreTwoEightAuthorization);
		users = Context.getUserService();
		signInAs("siteadmin");
	}
	
	@After
	public void removeModuleAdvice() {
		Context.removeAdvice(UserService.class, userGuard);
		Context.removeAdvice(PersonService.class, personGuard);
		Context.removeAdvice(ProviderService.class, personGuard);
		Context.removeAdvice(AdministrationService.class, coreTwoEightAuthorization);
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
		refused(() -> users.changeQuestionAnswer(user, "Pet?", "Rex"), "is not at your clinics");
	}
	
	@Test
	public void refusesEditingThePersonOrProviderOfAUserAtAnotherClinic() {
		Person person = Context.getUserService().getUserByUsername("clinicianatB").getPerson();
		person.getPersonName().setGivenName("Renamed");
		refused(() -> Context.getPersonService().savePerson(person), "is not at your clinics");
		refused(() -> Context.getPersonService().savePersonName(person.getPersonName()), "is not at your clinics");
		
		Person admin = Context.getUserService().getUserByUsername("otheradmin").getPerson();
		refused(() -> Context.getPersonService().savePerson(admin), "administers users");
		
		providerAtB.setIdentifier("changed");
		refused(() -> Context.getProviderService().saveProvider(providerAtB), "is not at your clinics");
		refused(() -> Context.getProviderService().retireProvider(providerAtB, "gone"), "is not at your clinics");
	}
	
	@Test
	public void refusesMovingAProviderFromAUserAtAnotherClinic() {
		// What REST does: change the loaded provider, then save it.
		providerAtB.setPerson(Context.getUserService().getUserByUsername("clinicianatA").getPerson());
		
		refused(() -> Context.getProviderService().saveProvider(providerAtB), "is not at your clinics");
	}
	
	@Test
	public void editsThePersonAndProviderOfAUserAtItsClinicAndANewPerson() {
		Person person = Context.getUserService().getUserByUsername("clinicianatA").getPerson();
		person.getPersonName().setGivenName("Renamed");
		Context.getPersonService().savePerson(person);
		
		Provider provider = new Provider();
		provider.setPerson(person);
		provider.setIdentifier("providerAtA");
		Context.getProviderService().saveProvider(provider);
		
		Person newcomer = new Person();
		newcomer.addName(new PersonName("Newcomer", null, "Tester"));
		newcomer.setGender("M");
		Context.getPersonService().savePerson(newcomer);
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
	public void refusesGivingARoleOutsideTheClinicianRoles() {
		refused(() -> users.createUser(newUser("newadmin", clinicA, siteAdministrator), "Created123"),
		    "give or remove the role Test Site Administrator");
		// A role the clinician role inherits is not a clinician role of its own.
		refused(() -> users.createUser(newUser("newexporter", clinicA, exports), "Created123"),
		    "give or remove the role Test Exports");
		
		User user = Context.getUserService().getUserByUsername("clinicianatA");
		user.addRole(siteAdministrator);
		refused(() -> users.saveUser(user), "give or remove the role Test Site Administrator");
	}
	
	@Test
	public void refusesRemovingARoleOutsideTheClinicianRoles() {
		signInAsSuperuser();
		User exporter = newUser("exporter", clinicA, clinician);
		exporter.addRole(exports);
		save(exporter);
		signInAs("siteadmin");
		
		User user = Context.getUserService().getUserByUsername("exporter");
		user.removeRole(exports);
		refused(() -> users.saveUser(user), "give or remove the role Test Exports");
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
		
		me.setUserProperty(ClinicUsers.CLINICS_PROPERTY, clinicA);
		me.addRole(clinician);
		refused(() -> users.saveUser(me), "your own clinics or roles");
	}
	
	@Test
	public void refusesWideningItsOwnClinicsThroughItsOwnProperties() {
		users.saveUserProperty("defaultLocale", "fr");
		
		refused(() -> users.saveUserProperty(ClinicUsers.CLINICS_PROPERTY, clinicA + "," + clinicB),
		    "your own clinics or roles");
		refused(
		    () -> users.saveUserProperties(Collections.singletonMap(ClinicUsers.CLINICS_PROPERTY, clinicA + "," + clinicB)),
		    "your own clinics or roles");
	}
	
	@Test
	public void leavesAnAdministratorOfEveryClinicUnlimited() {
		signInAs("instanceadmin");
		User user = Context.getUserService().getUserByUsername("clinicianatB");
		user.setUserProperty(ClinicUsers.CLINICS_PROPERTY, clinicA);
		
		users.saveUser(user);
		users.createUser(newUser("newsiteadmin", clinicB, siteAdministrator), "Created123");
		Context.getPersonService().savePerson(Context.getUserService().getUserByUsername("clinicianatB").getPerson());
	}
	
	@Test
	public void refusesAnAdministratorOfEveryClinicARoleBeyondSiteAdministrator() {
		signInAs("instanceadmin");
		
		refused(() -> users.createUser(newUser("newinstanceadmin", clinicB, instanceAdministrator), "Created123"),
		    "give or remove the role Test Instance Administrator");
	}
	
	@Test
	public void letsASuperuserGiveAnyRole() {
		signInAsSuperuser();
		
		users.createUser(newUser("newinstanceadmin", null, instanceAdministrator), "Created123");
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
	
	private static void property(String name, String value) {
		Context.getAdministrationService().saveGlobalProperty(new GlobalProperty(name, value));
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
	
	private void signInAsSuperuser() {
		Context.flushSession();
		Context.logout();
		authenticate();
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
