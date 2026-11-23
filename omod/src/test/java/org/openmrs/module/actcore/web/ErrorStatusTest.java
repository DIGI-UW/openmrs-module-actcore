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
import static org.junit.Assert.assertNotNull;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.junit.Test;
import org.openmrs.Person;
import org.openmrs.PersonName;
import org.openmrs.Privilege;
import org.openmrs.Role;
import org.openmrs.User;
import org.openmrs.api.UserService;
import org.openmrs.api.context.Context;
import org.openmrs.util.PrivilegeConstants;
import org.openmrs.web.test.BaseModuleWebContextSensitiveTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The statuses refused requests get when Spring MVC dispatches them and resolves their exceptions.
 */
public class ErrorStatusTest extends BaseModuleWebContextSensitiveTest {
	
	@Autowired
	private List<RequestMappingHandlerMapping> mappings;
	
	@Autowired
	private RequestMappingHandlerAdapter adapter;
	
	@Autowired
	private ExceptionHandlerExceptionResolver resolver;
	
	@Test
	public void answersARefusedStudyNameWithABadRequest() throws Exception {
		MockHttpServletResponse response = dispatch(post("/rest/v1/actcore/studies", "{\"name\":\" \"}"));
		
		assertEquals(400, response.getStatus());
	}
	
	@Test
	public void answersAMissingPrivilegeWithForbidden() throws Exception {
		authenticateWith(PrivilegeConstants.GET_PROGRAMS);
		
		MockHttpServletResponse response = dispatch(post("/rest/v1/actcore/studies", "{\"name\":\"ARC\"}"));
		
		assertEquals(403, response.getStatus());
	}
	
	private static MockHttpServletRequest post(String uri, String json) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
		request.addHeader("Accept", "application/json");
		request.setContentType("application/json");
		request.setContent(json.getBytes(StandardCharsets.UTF_8));
		return request;
	}
	
	private MockHttpServletResponse dispatch(MockHttpServletRequest request) throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		HandlerExecutionChain chain = null;
		for (RequestMappingHandlerMapping mapping : mappings) {
			chain = chain == null ? mapping.getHandler(request) : chain;
		}
		assertNotNull(chain);
		HandlerMethod handler = (HandlerMethod) chain.getHandler();
		try {
			adapter.handle(request, response, handler);
		}
		catch (Exception e) {
			resolver.resolveException(request, response, handler, e);
		}
		return response;
	}
	
	private void authenticateWith(String... privileges) {
		UserService users = Context.getUserService();
		Role role = new Role("error status caller " + UUID.randomUUID());
		for (String name : privileges) {
			Privilege privilege = users.getPrivilege(name);
			role.addPrivilege(privilege != null ? privilege : users.savePrivilege(new Privilege(name)));
		}
		users.saveRole(role);
		Person person = new Person();
		person.addName(new PersonName("Error", null, "Caller"));
		person.setGender("F");
		User user = new User(person);
		user.setUsername("errorcaller");
		user.addRole(role);
		users.createUser(user, "Errors123");
		Context.logout();
		Context.authenticate("errorcaller", "Errors123");
	}
}
