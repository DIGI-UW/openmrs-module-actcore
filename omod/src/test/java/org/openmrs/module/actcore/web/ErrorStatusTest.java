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
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.junit.Test;
import org.openmrs.Person;
import org.openmrs.PersonName;
import org.openmrs.Privilege;
import org.openmrs.Role;
import org.openmrs.User;
import org.openmrs.api.UserService;
import org.openmrs.api.ValidationException;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.ActCoreConstants;
import org.openmrs.module.actcore.ActCorePrivileges;
import org.openmrs.module.actcore.ModuleTables;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.response.IllegalRequestException;
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
	
	@Autowired
	private ScreeningCountController screeningCounts;
	
	@Autowired
	private DownloadController downloads;
	
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
	
	@Test
	public void answersScreeningCountsThatDoNotAddUpWithABadRequest() throws Exception {
		ModuleTables.create();
		MockHttpServletRequest request = post("/rest/v1/actcore/screeningcounts", "{\"counts\":{\"school_screened\":5}}");
		request.setParameter("clinic", "8d6c993e-c2cc-11de-8d13-0010c6dffd0f");
		request.setParameter("month", "2026-09");
		
		assertEquals(400, dispatch(request).getStatus());
	}
	
	@Test
	public void givesTheServicesRefusalAsTheGlobalErrorOfTheBadRequest() {
		ModuleTables.create();
		try {
			screeningCounts.save("8d6c993e-c2cc-11de-8d13-0010c6dffd0f", "2026-09",
			    Collections.singletonMap("counts", Collections.singletonMap("school_screened", 5)));
			fail("expected the counts to be refused");
		}
		catch (ValidationException e) {
			MockHttpServletResponse response = new MockHttpServletResponse();
			SimpleObject body = screeningCounts.validationExceptionHandler(e, new MockHttpServletRequest(), response);
			
			assertEquals(400, response.getStatus());
			List<?> globalErrors = (List<?>) ((SimpleObject) body.get("error")).get("globalErrors");
			assertEquals("The school age groups add up to 0, not the 5 screened",
			    ((SimpleObject) globalErrors.get(0)).get("message"));
		}
	}
	
	@Test
	public void answersDailyRunChartPointsOverTooLongARangeWithABadRequest() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/rest/v1/actcore/runchart");
		request.addHeader("Accept", "application/json");
		request.setParameter("points", "day");
		request.setParameter("startDate", "2026-01-01");
		request.setParameter("endDate", "2026-10-21");
		
		assertEquals(400, dispatch(request).getStatus());
	}
	
	@Test
	public void answersADownloadRecordTooLongToKeepWithABadRequestNamingIt() throws Exception {
		ModuleTables.create();
		authenticateWith(ActCorePrivileges.DOWNLOAD_DATA);
		String name = StringUtils.repeat("x", ActCoreConstants.MAX_DOWNLOAD_DETAIL_BYTES);
		
		assertEquals(400, dispatch(post("/rest/v1/actcore/download/record",
		    "{\"list\":\"registry\",\"columns\":[\"ACT ID\"],\"filters\":{\"name\":\"" + name + "\"}}")).getStatus());
		Map<String, Object> body = new HashMap<String, Object>();
		body.put("list", "registry");
		body.put("columns", Collections.singletonList("ACT ID"));
		body.put("filters", Collections.singletonMap("name", name));
		try {
			downloads.record(body);
			fail("expected the record to be refused");
		}
		catch (IllegalRequestException e) {
			MockHttpServletResponse response = new MockHttpServletResponse();
			SimpleObject answer = downloads.handleException(e, new MockHttpServletRequest(), response);
			
			assertEquals(400, response.getStatus());
			assertEquals("[Too many or too long columns and filters to record]",
			    ((Map<?, ?>) answer.get("error")).get("message"));
		}
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
