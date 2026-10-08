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

import java.util.UUID;

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
import org.openmrs.module.actcore.task.PatientFlagRefreshTask;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.web.test.BaseModuleWebContextSensitiveTest;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Running the refresh as the daemon user needs the token the module receives when it starts, which
 * a context test never has, so that path is checked on a running server.
 */
public class RefreshControllerTest extends BaseModuleWebContextSensitiveTest {
	
	@Autowired
	private RefreshController controller;
	
	@Test
	public void refusesAUserWithoutThePrivilege() {
		authenticateWith("Get Patients");
		
		for (Runnable call : new Runnable[] { () -> controller.getStatus(), () -> controller.refresh() }) {
			try {
				call.run();
				fail("expected the request to be refused");
			}
			catch (APIAuthenticationException e) {
				assertTrue(e.getMessage().contains(RefreshController.PRIVILEGE));
			}
		}
	}
	
	@Test
	public void tellsAUserWithOnlyThePrivilegeWhenTheRefreshLastFinished() {
		Context.getAdministrationService().saveGlobalProperty(
		    new GlobalProperty(PatientFlagRefreshTask.LAST_FINISHED_PROPERTY, "2026-10-08T06:14:29Z"));
		authenticateWith(RefreshController.PRIVILEGE);
		
		SimpleObject status = controller.getStatus();
		
		assertEquals("2026-10-08T06:14:29Z", status.get("lastRefreshed"));
		assertEquals(Boolean.FALSE, status.get("running"));
	}
	
	@Test
	public void saysNoRefreshHasFinishedYet() {
		Context.getAdministrationService()
		        .saveGlobalProperty(new GlobalProperty(PatientFlagRefreshTask.LAST_FINISHED_PROPERTY, ""));
		
		assertNull(controller.getStatus().get("lastRefreshed"));
	}
	
	@Test
	public void refusesToRunBeforeTheModuleHasStarted() {
		try {
			controller.refresh();
			fail("expected the refresh to be refused");
		}
		catch (APIException e) {
			assertTrue(e.getMessage().contains("has not started"));
		}
	}
	
	private void authenticateWith(String... privileges) {
		UserService users = Context.getUserService();
		Role role = new Role("refresh caller " + UUID.randomUUID());
		for (String name : privileges) {
			Privilege privilege = users.getPrivilege(name);
			role.addPrivilege(privilege != null ? privilege : users.savePrivilege(new Privilege(name)));
		}
		users.saveRole(role);
		Person person = new Person();
		person.addName(new PersonName("Refresh", null, "Caller"));
		person.setGender("F");
		User user = new User(person);
		user.setUsername("refreshcaller");
		user.addRole(role);
		users.createUser(user, "Refresh123");
		Context.logout();
		Context.authenticate("refreshcaller", "Refresh123");
	}
}
