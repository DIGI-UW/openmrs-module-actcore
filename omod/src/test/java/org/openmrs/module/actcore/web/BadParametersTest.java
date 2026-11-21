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

import java.util.Map;

import java.util.List;

import org.junit.Test;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.web.test.BaseModuleWebContextSensitiveTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Requests as Spring MVC dispatches them, including the exception handling a direct call skips. */
public class BadParametersTest extends BaseModuleWebContextSensitiveTest {
	
	@Autowired
	private List<RequestMappingHandlerMapping> mappings;
	
	@Autowired
	private RequestMappingHandlerAdapter adapter;
	
	@Autowired
	private ExceptionHandlerExceptionResolver resolver;
	
	@Autowired
	private ActivityController activity;
	
	@Test
	public void answersANonNumericPagingParameterWithABadRequest() throws Exception {
		MockHttpServletRequest request = get("/rest/v1/actcore/activity");
		request.setParameter("startIndex", "abc");
		
		MockHttpServletResponse response = dispatch(request);
		
		assertEquals(400, response.getStatus());
	}
	
	@Test
	public void answersANonNumericPathVariableWithABadRequest() throws Exception {
		MockHttpServletResponse response = dispatch(get("/rest/v1/actcore/activity/abc"));
		
		assertEquals(400, response.getStatus());
	}
	
	@Test
	public void answersAMissingRequiredParameterWithABadRequestNamingIt() throws Exception {
		MockHttpServletRequest request = get("/rest/v1/actcore/screeningcounts");
		request.setParameter("clinic", "x");
		
		MockHttpServletResponse response = dispatch(request);
		
		assertEquals(400, response.getStatus());
	}
	
	@Test
	public void namesTheBadParameterWithoutAStackTrace() {
		MockHttpServletResponse response = new MockHttpServletResponse();
		
		SimpleObject body = activity.badParameterHandler(new MissingServletRequestParameterException("month", "String"),
		    response);
		
		assertEquals(400, response.getStatus());
		assertEquals("{error={message=month is required}}", body.toString());
	}
	
	private static MockHttpServletRequest get(String uri) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
		request.addHeader("Accept", "application/json");
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
		assertEquals(getClass().getPackage(), handler.getBeanType().getPackage());
		try {
			adapter.handle(request, response, handler);
		}
		catch (Exception e) {
			resolver.resolveException(request, response, handler, e);
		}
		return response;
	}
}
