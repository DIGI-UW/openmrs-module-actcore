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

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.UUID;

import org.aopalliance.intercept.MethodInterceptor;
import org.hibernate.SessionFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openmrs.GlobalProperty;
import org.openmrs.Patient;
import org.openmrs.PatientIdentifier;
import org.openmrs.Person;
import org.openmrs.PersonName;
import org.openmrs.Privilege;
import org.openmrs.Provider;
import org.openmrs.Role;
import org.openmrs.User;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.AdministrationService;
import org.openmrs.api.UserService;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.ContextAuthenticationException;
import org.openmrs.test.BaseModuleContextSensitiveTest;
import org.openmrs.util.OpenmrsConstants;
import org.openmrs.util.PrivilegeConstants;
import org.openmrs.util.RoleConstants;

public class ClinicLimitedUserManagementContextTest extends BaseModuleContextSensitiveTest {
	
	private UserService users;
	
	private String clinicA;
	
	private String clinicB;
	
	private Role clinician;
	
	private Role siteAdministrator;
	
	private Role instanceAdministrator;
	
	private Role exports;
	
	private Provider providerAtB;
	
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
		    PrivilegeConstants.GET_PERSONS, PrivilegeConstants.MANAGE_PROVIDERS, PrivilegeConstants.GET_PROVIDERS,
		    PrivilegeConstants.ADD_PATIENTS, PrivilegeConstants.EDIT_PATIENTS, PrivilegeConstants.GET_IDENTIFIER_TYPES,
		    PrivilegeConstants.GET_LOCATIONS, PrivilegeConstants.GET_PATIENT_IDENTIFIERS,
		    PrivilegeConstants.ADD_PATIENT_IDENTIFIERS, PrivilegeConstants.EDIT_PATIENT_IDENTIFIERS);
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
		
		Context.addAdvice(AdministrationService.class, coreTwoEightAuthorization);
		users = Context.getUserService();
		signInAs("siteadmin");
	}
	
	@After
	public void removeCoreTwoEightAuthorization() {
		Context.removeAdvice(AdministrationService.class, coreTwoEightAuthorization);
	}
	
	@Test
	public void editsAUserAtItsClinic() {
		User user = users.getUserByUsername("clinicianatA");
		user.setUserProperty("defaultLocale", "fr");
		
		users.saveUser(user);
		users.retireUser(user, "left the clinic");
		users.changePassword(user, "Changed123");
		Context.flushSession();
	}
	
	@Test
	public void refusesAUserAtAnotherClinic() {
		User user = users.getUserByUsername("clinicianatB");
		user.setUserProperty("defaultLocale", "fr");
		
		refused(() -> users.saveUser(user), "is not at your clinics");
		refused(() -> users.retireUser(users.getUserByUsername("clinicianatB"), "gone"), "is not at your clinics");
		refused(() -> users.changePassword(users.getUserByUsername("clinicianatB"), "Changed123"), "is not at your clinics");
		refused(() -> users.setUserProperty(users.getUserByUsername("clinicianatB"), "defaultLocale", "fr"),
		    "is not at your clinics");
		refused(() -> users.changeQuestionAnswer(users.getUserByUsername("clinicianatB"), "Pet?", "Rex"),
		    "is not at your clinics");
	}
	
	@Test
	public void refusesEditingThePersonOrProviderOfAUserAtAnotherClinic() {
		Person person = users.getUserByUsername("clinicianatB").getPerson();
		person.getPersonName().setGivenName("Renamed");
		refused(() -> Context.getPersonService().savePerson(person), "is not at your clinics");
		
		PersonName name = users.getUserByUsername("clinicianatB").getPersonName();
		name.setFamilyName("Renamed");
		refused(() -> Context.getPersonService().savePersonName(name), "is not at your clinics");
		
		Person admin = users.getUserByUsername("otheradmin").getPerson();
		admin.setGender("M");
		refused(() -> Context.getPersonService().savePerson(admin), "administers users");
		
		Provider provider = Context.getProviderService().getProviderByIdentifier("providerAtB");
		provider.setIdentifier("changed");
		refused(() -> Context.getProviderService().saveProvider(provider), "is not at your clinics");
		refused(
		    () -> Context.getProviderService()
		            .retireProvider(Context.getProviderService().getProviderByIdentifier("providerAtB"), "gone"),
		    "is not at your clinics");
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
		Context.flushSession();
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
		Context.flushSession();
		
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
		Context.flushSession();
	}
	
	@Test
	public void savesItsOwnPreferencesButNotItsOwnClinicsOrRoles() {
		User me = users.getUserByUsername("siteadmin");
		me.setUserProperty("defaultLocation", clinicA);
		users.saveUser(me);
		Context.flushSession();
		
		me.setUserProperty(ClinicUsers.CLINICS_PROPERTY, clinicA + "," + clinicB);
		refused(() -> users.saveUser(me), "your own clinics or roles");
		
		User again = users.getUserByUsername("siteadmin");
		again.addRole(clinician);
		refused(() -> users.saveUser(again), "your own clinics or roles");
	}
	
	@Test
	public void refusesWideningItsOwnClinicsThroughItsOwnProperties() {
		users.saveUserProperty("defaultLocale", "fr");
		Context.flushSession();
		
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
		Person person = users.getUserByUsername("clinicianatB").getPerson();
		person.setGender("M");
		Context.getPersonService().savePerson(person);
		Context.flushSession();
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
		Context.flushSession();
	}
	
	@Test
	public void refusesChangingARoleThroughAUserSave() {
		// What REST does with a nested role: change the loaded role, then save the user.
		User user = users.getUserByUsername("clinicianatA");
		clinician.setDescription("changed through a user save");
		
		refused(() -> users.saveUser(user), "needs Manage Roles");
	}
	
	@Test
	public void refusesChangingWhatARoleInheritsThroughAUserSave() {
		signInAsSuperuser();
		Role communityClinician = role("Test Community Clinician", PrivilegeConstants.GET_PATIENTS);
		signInAs("siteadmin");
		
		User user = users.getUserByUsername("clinicianatA");
		communityClinician.getInheritedRoles().add(siteAdministrator);
		
		refused(() -> users.saveUser(user), "needs Manage Roles");
	}
	
	@Test
	public void refusesMovingAUserOntoAnotherPerson() {
		User user = users.getUserByUsername("clinicianatA");
		user.setPerson(users.getUserByUsername("clinicianatB").getPerson());
		refused(() -> users.saveUser(user), "to another person");
		
		User again = users.getUserByUsername("clinicianatA");
		again.setPerson(Context.getPersonService().getPerson(1));
		refused(() -> users.saveUser(again), "to another person");
	}
	
	@Test
	public void refusesChangingThePersonOfAUserAtAnotherClinicThroughThePatientService() {
		Integer personId = users.getUserByUsername("clinicianatB").getPerson().getPersonId();
		signInAsSuperuser();
		makePatient(personId);
		signInAs("siteadmin");
		
		Patient patient = Context.getPatientService().getPatient(personId);
		patient.setGender("M");
		
		refused(() -> Context.getPatientService().savePatient(patient), "is not at your clinics");
	}
	
	@Test
	public void refusesChangingThePersonOfAUserAtAnotherClinicStraightInTheSession() {
		// What fhir2 does: save the changed person through the session, with no service call.
		Person person = Context.getPersonService()
		        .getPerson(users.getUserByUsername("clinicianatB").getPerson().getPersonId());
		person.setGender("M");
		
		refused(() -> sessions().getCurrentSession().saveOrUpdate(person), "is not at your clinics");
	}
	
	@Test
	public void judgesObjectsLoadedInAnEarlierSessionByWhatTheDatabaseHolds() {
		Person person = users.getUserByUsername("clinicianatB").getPerson();
		User user = users.getUserByUsername("clinicianatA");
		Context.clearSession();
		
		// Reattached, they have no snapshot: unchanged, the person and the user's roles stay open.
		sessions().getCurrentSession().saveOrUpdate(person);
		user.setUserProperty("defaultLocale", "fr");
		users.saveUser(user);
		Context.flushSession();
		
		Context.clearSession();
		person.setGender("M");
		refused(() -> sessions().getCurrentSession().saveOrUpdate(person), "is not at your clinics");
	}
	
	@Test
	public void refusesARoleOrClinicChangedBeforeRetiringOrRepasswordingAUser() {
		User retired = users.getUserByUsername("clinicianatA");
		retired.addRole(siteAdministrator);
		refused(() -> users.retireUser(retired, "left"), "give or remove the role Test Site Administrator");
		
		User repassworded = users.getUserByUsername("clinicianatA");
		repassworded.addRole(siteAdministrator);
		refused(() -> users.changePassword(repassworded, "Changed123"), "give or remove the role Test Site Administrator");
		
		User unretired = users.getUserByUsername("clinicianatA");
		unretired.setUserProperty(ClinicUsers.CLINICS_PROPERTY, clinicB);
		refused(() -> users.unretireUser(unretired), "only your own clinics");
	}
	
	@Test
	public void judgesTheClinicsAUserPropertyCallLeaves() {
		refused(() -> users.setUserProperty(users.getUserByUsername("siteadmin"), ClinicUsers.CLINICS_PROPERTY,
		    clinicA + "," + clinicB), "your own clinics or roles");
		refused(() -> users.removeUserProperty(users.getUserByUsername("clinicianatA"), ClinicUsers.CLINICS_PROPERTY),
		    "at least one of your clinics");
	}
	
	@Test
	public void registersAPatientAndSavesAnUnchangedUserAtAnotherClinic() {
		Patient patient = new Patient();
		patient.addName(new PersonName("Registered", null, "Patient"));
		patient.setGender("F");
		PatientIdentifier identifier = new PatientIdentifier("rhd99901",
		        Context.getPatientService().getPatientIdentifierType(2), Context.getLocationService().getLocation(1));
		identifier.setPreferred(true);
		patient.addIdentifier(identifier);
		Context.getPatientService().savePatient(patient);
		
		users.saveUser(users.getUserByUsername("clinicianatB"));
		Context.flushSession();
	}
	
	@Test
	public void countsAndClearsTheSignInsOfAUserAtAnotherClinic() {
		Integer userId = users.getUserByUsername("clinicianatB").getUserId();
		Context.flushSession();
		Context.logout();
		try {
			Context.authenticate("clinicianatB", "Wrong123");
			fail("expected the sign-in to fail");
		}
		catch (ContextAuthenticationException e) {
			Context.flushSession();
		}
		assertEquals("1", ClinicUsers.Stored.of(userId).properties.get(OpenmrsConstants.USER_PROPERTY_LOGIN_ATTEMPTS));
		
		Context.authenticate("clinicianatB", "Tester123");
		Context.flushSession();
		assertEquals("0", ClinicUsers.Stored.of(userId).properties.get(OpenmrsConstants.USER_PROPERTY_LOGIN_ATTEMPTS));
	}
	
	@Test
	public void refusesLockingOutAUserItMayNotManage() {
		User user = users.getUserByUsername("clinicianatB");
		user.setUserProperty(OpenmrsConstants.USER_PROPERTY_LOCKOUT_TIMESTAMP, String.valueOf(System.currentTimeMillis()));
		refused(() -> users.saveUser(user), "is not at your clinics");
		
		User admin = users.getUserByUsername("otheradmin");
		admin.setUserProperty(OpenmrsConstants.USER_PROPERTY_LOGIN_ATTEMPTS, "1000");
		refused(() -> users.saveUser(admin), "administers users");
	}
	
	@Test
	public void refusesResettingThePasswordOfAUserWhoseRolesOutrankItsOwn() {
		signInAsSuperuser();
		save(newUser("developer", null, Context.getUserService().getRole(RoleConstants.SUPERUSER)));
		save(newUser("clinicmanager", clinicA, role("Test Clinic Manager", PrivilegeConstants.MANAGE_LOCATIONS)));
		
		signInAs("instanceadmin");
		refused(() -> users.changePassword(users.getUserByUsername("developer"), "Taken123"),
		    "a role or privilege you lack");
		refused(() -> users.changeQuestionAnswer(users.getUserByUsername("developer"), "Pet?", "Rex"),
		    "a role or privilege you lack");
		
		signInAs("siteadmin");
		refused(() -> users.changePassword(users.getUserByUsername("clinicmanager"), "Taken123"),
		    "a role or privilege you lack");
	}
	
	@Test
	public void resetsThePasswordsOfUsersItOutranksAndItsOwn() {
		signInAsSuperuser();
		save(newUser("encounterreader", clinicA, role("Test Encounter Reader", PrivilegeConstants.GET_ENCOUNTERS)));
		Role authenticated = Context.getUserService().getRole(RoleConstants.AUTHENTICATED);
		authenticated.addPrivilege(Context.getUserService().getPrivilege(PrivilegeConstants.GET_ENCOUNTERS));
		Context.getUserService().saveRole(authenticated);
		
		signInAs("instanceadmin");
		users.changePassword(users.getUserByUsername("otheradmin"), "Changed123");
		users.changePassword(users.getUserByUsername("clinicianatB"), "Changed123");
		users.changePassword("Tester123", "Changed456");
		Context.flushSession();
		
		signInAs("siteadmin");
		// It holds Get Encounters only through Authenticated, as every user does.
		users.changePassword(users.getUserByUsername("encounterreader"), "Changed123");
		Context.flushSession();
	}
	
	@Test
	public void refusesRemovingAPrivilegeFromARoleThroughAUserSave() {
		User user = users.getUserByUsername("clinicianatA");
		clinician.getPrivileges().clear();
		
		refused(() -> users.saveUser(user), "needs Manage Roles");
	}
	
	@Test
	public void refusesDeletingAUserAtAnotherClinicOrItsPersonsName() {
		refused(() -> sessions().getCurrentSession().delete(users.getUserByUsername("clinicianatB")),
		    "is not at your clinics");
		refused(() -> sessions().getCurrentSession().delete(users.getUserByUsername("clinicianatB").getPersonName()),
		    "is not at your clinics");
	}
	
	@Test
	public void editsThePersonOfAUserItCreatedInTheSameFlush() {
		// What a REST create does: the new user's clinics reach the database only as the session flushes.
		User created = newUser("newclinician", clinicA, clinician);
		created.setSystemId("newclinician-1");
		sessions().getCurrentSession().save(created);
		created.getPersonName().setFamilyName("Renamed");
		Context.flushSession();
	}
	
	@Test
	public void judgesAPersonByItsUsersBeforeTheAdministratorsClinics() {
		signInAs("noclinicadmin");
		Person newcomer = new Person();
		newcomer.addName(new PersonName("Newcomer", null, "Tester"));
		newcomer.setGender("M");
		Context.getPersonService().savePerson(newcomer);
		Context.flushSession();
		
		Person person = users.getUserByUsername("clinicianatA").getPerson();
		person.setGender("M");
		refused(() -> Context.getPersonService().savePerson(person), "is not at your clinics");
	}
	
	@Test
	public void refusesAClinicLimitedAdministratorWithNoClinics() {
		signInAs("noclinicadmin");
		
		User user = users.getUserByUsername("clinicianatA");
		user.setUserProperty("defaultLocale", "fr");
		
		refused(() -> users.saveUser(user), "You have no clinics");
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
			Context.flushSession();
			fail("expected the change to be refused: " + reason);
		}
		catch (APIAuthenticationException e) {
			assertTrue(e.getMessage(), e.getMessage().contains(reason));
			// Drops the refused change, so the next one is judged alone.
			Context.clearSession();
		}
	}
	
	private static SessionFactory sessions() {
		return Context.getRegisteredComponent("sessionFactory", SessionFactory.class);
	}
	
	private static void makePatient(Integer personId) {
		sessions().getCurrentSession().doWork(connection -> {
			try (PreparedStatement insert = connection.prepareStatement(
			    "insert into patient (patient_id, creator, date_created, voided, allergy_status) values (?, 1, ?, false, 'Unknown')")) {
				insert.setInt(1, personId);
				insert.setTimestamp(2, new Timestamp(System.currentTimeMillis()));
				insert.executeUpdate();
			}
			try (PreparedStatement insert = connection.prepareStatement(
			    "insert into patient_identifier (patient_id, identifier, identifier_type, preferred, location_id, creator, date_created, voided, uuid) values (?, 'rhd99902', 2, true, 1, 1, ?, false, ?)")) {
				insert.setInt(1, personId);
				insert.setTimestamp(2, new Timestamp(System.currentTimeMillis()));
				insert.setString(3, UUID.randomUUID().toString());
				insert.executeUpdate();
			}
		});
		Context.clearSession();
	}
}
