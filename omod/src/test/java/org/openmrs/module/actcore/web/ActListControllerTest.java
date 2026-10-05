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

import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import javax.servlet.http.HttpServletRequest;

import org.junit.Test;
import org.openmrs.Person;
import org.openmrs.PersonName;
import org.openmrs.Privilege;
import org.openmrs.Role;
import org.openmrs.User;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.UserService;
import org.openmrs.api.context.Context;
import org.openmrs.module.webservices.rest.web.response.IllegalRequestException;
import org.openmrs.module.webservices.rest.web.response.ObjectNotFoundException;
import org.openmrs.web.test.jupiter.BaseModuleWebContextSensitiveTest;
import org.springframework.beans.factory.annotation.Autowired;

public class ActListControllerTest extends BaseModuleWebContextSensitiveTest {
	
	@Autowired
	private ActListController controller;
	
	@Test(expected = ObjectNotFoundException.class)
	public void answersNotFoundForAListItDoesNotServe() {
		controller.getList("everyReport", request(), null);
	}
	
	@Test
	public void refusesAUserWithoutTheListsOwnPrivilege() {
		authenticateWith("App: act.worklists");
		try {
			controller.getList("registry", request(), null);
			throw new AssertionError("expected the request to be refused");
		}
		catch (APIAuthenticationException e) {
			assertTrue(e.getMessage().contains("App: act.registry"));
		}
	}
	
	@Test(expected = IllegalRequestException.class)
	public void refusesACohortForTheReportToStartFrom() {
		authenticateWith("App: act.registry");
		controller.getList("registry", request("endDate", "2026-10-05", "cohort.startDate", "2020-01-01"), null);
	}
	
	/**
	 * A GET request with these parameters; the test classpath's Servlet 2.5 API has no mock request.
	 */
	private static HttpServletRequest request(String... nameValuePairs) {
		final Map<String, String[]> parameters = new LinkedHashMap<String, String[]>();
		for (int i = 0; i < nameValuePairs.length; i += 2) {
			parameters.put(nameValuePairs[i], new String[] { nameValuePairs[i + 1] });
		}
		return (HttpServletRequest) Proxy.newProxyInstance(ActListControllerTest.class.getClassLoader(),
		    new Class<?>[] { HttpServletRequest.class }, (proxy, method, args) -> {
			    switch (method.getName()) {
				    case "getParameterMap":
					    return parameters;
				    case "getParameter":
					    return parameters.containsKey(args[0]) ? parameters.get(args[0])[0] : null;
				    case "getParameterValues":
					    return parameters.get(args[0]);
				    case "getParameterNames":
					    return Collections.enumeration(parameters.keySet());
				    case "getMethod":
					    return "GET";
				    default:
					    return null;
			    }
		    });
	}
	
	private void authenticateWith(String... privileges) {
		UserService users = Context.getUserService();
		Role role = new Role("list reader " + UUID.randomUUID());
		for (String name : privileges) {
			Privilege privilege = users.getPrivilege(name);
			role.addPrivilege(privilege != null ? privilege : users.savePrivilege(new Privilege(name)));
		}
		users.saveRole(role);
		Person person = new Person();
		person.addName(new PersonName("List", null, "Reader"));
		person.setGender("F");
		User user = new User(person);
		user.setUsername("listreader");
		user.addRole(role);
		users.createUser(user, "ListReader123");
		Context.logout();
		Context.authenticate("listreader", "ListReader123");
	}
}
