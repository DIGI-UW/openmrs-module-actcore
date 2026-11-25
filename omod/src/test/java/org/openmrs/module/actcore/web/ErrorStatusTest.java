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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.After;
import org.junit.Test;
import org.openmrs.GlobalProperty;
import org.openmrs.Person;
import org.openmrs.PersonName;
import org.openmrs.Privilege;
import org.openmrs.Role;
import org.openmrs.User;
import org.openmrs.api.UserService;
import org.openmrs.api.ValidationException;
import org.openmrs.api.context.Context;
import org.openmrs.module.DaemonTokens;
import org.openmrs.module.actcore.ActCoreActivator;
import org.openmrs.module.actcore.ActCoreConstants;
import org.openmrs.module.actcore.ModuleTables;
import org.openmrs.module.actcore.api.impl.PatientFlagRefresh;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.response.ConversionException;
import org.openmrs.util.PrivilegeConstants;
import org.openmrs.web.test.BaseModuleWebContextSensitiveTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
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
	
	/** Unknown Location in the standard test dataset. */
	private static final String CLINIC = "8d6c993e-c2cc-11de-8d13-0010c6dffd0f";
	
	/** The endpoints that read a JSON body. */
	private static final String[] BODY_URIS = { "/rest/v1/actcore/download", "/rest/v1/actcore/download/record",
	        "/rest/v1/actcore/raisedflags/dismiss", "/rest/v1/actcore/screeningcounts", "/rest/v1/actcore/studies",
	        "/rest/v1/actcore/studies/" + UUID.randomUUID() };
	
	/** What a client may accept, which an error answers in JSON whatever it is. */
	private static final String[] ACCEPTS = { "application/json", "text/plain", "text/csv" };
	
	/** What a client may accept that admits neither JSON nor XML, or that is not a media type. */
	private static final String[] UNMET_ACCEPTS = { "text/plain", "text/csv", "text/html", "not a type" };
	
	/** What a client may accept that admits JSON or XML, which ACT Core answers in. */
	private static final String[] MET_ACCEPTS = { "application/json", "application/xml", "text/xml", "*/*", "application/*",
	        "text/plain, application/json", null };
	
	@After
	public void forgetTheDaemonToken() {
		new ActCoreActivator().setDaemonToken(null);
	}
	
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
	public void answersABodyOfTheWrongContentTypeWithUnsupportedMediaType() throws Exception {
		for (String uri : BODY_URIS) {
			for (String contentType : new String[] { "text/plain", "application/x-www-form-urlencoded" }) {
				for (String accept : ACCEPTS) {
					MockHttpServletRequest request = bodyPost(uri, "name=ARC");
					request.setContentType(contentType);
					accept(request, accept);
					
					MockHttpServletResponse response = dispatch(request);
					
					assertJsonError(uri + " " + contentType + " " + accept, 415, response);
					assertEquals(uri, "application/json", response.getHeader("Accept"));
				}
			}
		}
	}
	
	@Test
	public void namesTheUnsupportedContentTypeWithoutAStackTrace() {
		MockHttpServletResponse response = new MockHttpServletResponse();
		
		SimpleObject body = screeningCounts.unsupportedMediaTypeHandler(new HttpMediaTypeNotSupportedException(
		        MediaType.TEXT_PLAIN, Collections.singletonList(MediaType.APPLICATION_JSON)),
		    response);
		
		assertEquals(415, response.getStatus());
		assertEquals("application/json", response.getHeader("Accept"));
		assertEquals("{error={message=Content type 'text/plain' not supported}}", body.toString());
	}
	
	@Test
	public void answersAMissingOrMalformedBodyWithABadRequest() throws Exception {
		for (String uri : BODY_URIS) {
			for (String json : new String[] { "", "{\"name\":", "[]" }) {
				assertEquals(uri + " " + json, 400, dispatch(bodyPost(uri, json)).getStatus());
			}
		}
	}
	
	@Test
	public void answersARefreshThatIsNotJsonWithUnsupportedMediaTypeWithoutRunningIt() throws Exception {
		DaemonTokens.pass("actcore", new ActCoreActivator());
		String lastFinished = lastRefreshFinished();
		for (String contentType : new String[] { "text/plain", "application/x-www-form-urlencoded", "multipart/form-data",
		        null }) {
			for (String accept : ACCEPTS) {
				MockHttpServletRequest request = new MockHttpServletRequest("POST", "/rest/v1/actcore/refresh");
				accept(request, accept);
				if (contentType != null) {
					request.setContentType(contentType);
				}
				
				MockHttpServletResponse response = dispatch(request);
				
				assertJsonError(contentType + " " + accept, 415, response);
				assertEquals("application/json", response.getHeader("Accept"));
				assertEquals(contentType + " " + accept, lastFinished, lastRefreshFinished());
				assertFalse(PatientFlagRefresh.isRunning());
			}
		}
	}
	
	@Test
	public void answersARefusedRequestInJson() throws Exception {
		for (Object[] refused : refusedAsAdmin()) {
			assertJsonError(refused[2] + "", (Integer) refused[0], dispatch((MockHttpServletRequest) refused[1]));
		}
	}
	
	@Test
	public void answersARefusedRequestWhoseAcceptItCannotMeetWithNotAcceptable() throws Exception {
		for (String accept : UNMET_ACCEPTS) {
			for (Object[] refused : refusedAsAdmin()) {
				MockHttpServletRequest request = (MockHttpServletRequest) refused[1];
				accept(request, accept);
				
				assertNotAcceptable(refused[2] + " " + accept, dispatch(request));
			}
		}
	}
	
	@Test
	public void answersAMissingPrivilegeInJson() throws Exception {
		authenticateWith(PrivilegeConstants.GET_PROGRAMS);
		for (MockHttpServletRequest request : new MockHttpServletRequest[] {
		        post("/rest/v1/actcore/studies", "{\"name\":\"ARC\"}"), cardiology(UUID.randomUUID().toString()),
		        get("/rest/v1/actcore/refresh") }) {
			assertJsonError(request.getRequestURI(), 403, dispatch(request));
		}
	}
	
	@Test
	public void answersARequestWithoutAUserInJson() throws Exception {
		Context.logout();
		MockHttpServletResponse response = dispatch(post("/rest/v1/actcore/studies", "{\"name\":\"ARC\"}"));
		
		assertJsonError("json", 401, response);
		assertNotNull(response.getHeader("WWW-Authenticate"));
	}
	
	@Test
	public void answersAFailedConversionInJsonWhateverTheRequestAccepts() throws Exception {
		for (String accept : new String[] { "application/json", "text/plain", "text/csv", "not a type" }) {
			MockHttpServletRequest request = cardiology(null);
			accept(request, accept);
			MockHttpServletResponse response = new MockHttpServletResponse();
			
			assertNotNull(accept,
			    resolver.resolveException(request, response, handler(request), new ConversionException("unconvertible")));
			assertJsonError(accept, 400, response);
		}
	}
	
	@Test
	public void answersAGetWhoseAcceptItCannotMeetWithNotAcceptable() throws Exception {
		for (String accept : UNMET_ACCEPTS) {
			MockHttpServletRequest request = new MockHttpServletRequest("GET", "/rest/v1/actcore/refresh");
			accept(request, accept);
			
			assertNotAcceptable(accept, dispatch(request));
		}
	}
	
	@Test
	public void answersAGetWhoseAcceptAdmitsJsonOrXml() throws Exception {
		for (String accept : MET_ACCEPTS) {
			MockHttpServletRequest request = new MockHttpServletRequest("GET", "/rest/v1/actcore/refresh");
			if (accept != null) {
				accept(request, accept);
			}
			
			MockHttpServletResponse response = dispatch(request);
			
			assertEquals(accept + " " + response.getContentAsString(), 200, response.getStatus());
		}
	}
	
	@Test
	public void answersAJsonRefreshWhoseAcceptItCannotMeetWithNotAcceptableWithoutRunningIt() throws Exception {
		DaemonTokens.pass("actcore", new ActCoreActivator());
		String lastFinished = lastRefreshFinished();
		for (String accept : UNMET_ACCEPTS) {
			MockHttpServletRequest request = post("/rest/v1/actcore/refresh", "{}");
			accept(request, accept);
			
			assertNotAcceptable(accept, dispatch(request));
			assertEquals(accept, lastFinished, lastRefreshFinished());
			assertFalse(PatientFlagRefresh.isRunning());
		}
	}
	
	@Test
	public void answersJsonScreeningCountsWhoseAcceptItCannotMeetWithNotAcceptableWithoutSavingThem() throws Exception {
		ModuleTables.create();
		for (String accept : UNMET_ACCEPTS) {
			MockHttpServletRequest request = screeningCountsForAugust();
			accept(request, accept);
			
			assertNotAcceptable(accept, dispatch(request));
			assertEquals(accept, Boolean.FALSE, screeningCounts.get(CLINIC, "2026-08").get("saved"));
		}
		
		assertEquals(200, dispatch(screeningCountsForAugust()).getStatus());
		assertEquals(Boolean.TRUE, screeningCounts.get(CLINIC, "2026-08").get("saved"));
	}
	
	@Test
	public void runsARefreshThatIsJson() throws Exception {
		DaemonTokens.pass("actcore", new ActCoreActivator());
		String lastFinished = lastRefreshFinished();
		MockHttpServletRequest request = post("/rest/v1/actcore/refresh", "{}");
		
		MockHttpServletResponse response = dispatch(request);
		
		assertEquals(response.getContentAsString(), 200, response.getStatus());
		assertTrue(response.getContentAsString().contains("\"refreshed\":true"));
		assertNotEquals(lastFinished, lastRefreshFinished());
	}
	
	@Test
	public void answersARefreshWithAnUnreadableContentTypeWithUnsupportedMediaType() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/rest/v1/actcore/refresh");
		request.addHeader("Accept", "application/json");
		request.setContentType("json");
		
		MockHttpServletResponse response = dispatch(request);
		
		assertJsonError("json", 415, response);
		assertEquals("application/json", response.getHeader("Accept"));
	}
	
	/**
	 * Requests an administrator sends wrong, each with the status it gets and what it is, one for each
	 * of the controllers' error handlers that writes a body.
	 */
	static Object[][] refusedAsAdmin() {
		MockHttpServletRequest mistyped = get("/rest/v1/actcore/activity");
		mistyped.setParameter("startIndex", "abc");
		MockHttpServletRequest missing = get("/rest/v1/actcore/screeningcounts");
		missing.setParameter("clinic", "x");
		return new Object[][] { { 400, mistyped, "a mistyped parameter" }, { 400, missing, "a missing parameter" },
		        { 400, post("/rest/v1/actcore/studies", "{\"name\":\" \"}"), "a refused study" },
		        { 400, post("/rest/v1/actcore/studies", ""), "a missing body" },
		        { 400, cardiology(null), "a missing patient" },
		        { 404, cardiology(UUID.randomUUID().toString()), "an unknown patient" } };
	}
	
	/** A JSON POST of counts for August at {@link #CLINIC} that add up. */
	private static MockHttpServletRequest screeningCountsForAugust() {
		MockHttpServletRequest request = post("/rest/v1/actcore/screeningcounts",
		    "{\"counts\":{\"school_screened\":40,\"school_children\":40,\"school_females\":20,\"school_positive\":1}}");
		request.setParameter("clinic", CLINIC);
		request.setParameter("month", "2026-08");
		return request;
	}
	
	private static MockHttpServletRequest cardiology(String patient) {
		MockHttpServletRequest request = get("/rest/v1/actcore/cardiology");
		if (patient != null) {
			request.setParameter("patient", patient);
		}
		return request;
	}
	
	private static MockHttpServletRequest get(String uri) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
		request.addHeader("Accept", "application/json");
		return request;
	}
	
	/**
	 * A POST with the parameters the screening counts read before the body, which the others ignore.
	 */
	private static MockHttpServletRequest bodyPost(String uri, String body) {
		MockHttpServletRequest request = post(uri, body);
		request.setParameter("clinic", "x");
		request.setParameter("month", "2026-09");
		return request;
	}
	
	/** When the refresh last finished, read from the database, where the daemon's run commits it. */
	private static String lastRefreshFinished() {
		GlobalProperty finished = Context.getAdministrationService()
		        .getGlobalPropertyObject(ActCoreConstants.GP_REFRESH_LAST_FINISHED);
		if (finished == null) {
			return null;
		}
		Context.refreshEntity(finished);
		return finished.getPropertyValue();
	}
	
	private static void accept(MockHttpServletRequest request, String accept) {
		request.removeHeader("Accept");
		request.addHeader("Accept", accept);
	}
	
	/** Asserts 406 with an empty body. */
	private static void assertNotAcceptable(String what, MockHttpServletResponse response) throws Exception {
		assertEquals(what, 406, response.getStatus());
		assertEquals(what, "", response.getContentAsString());
	}
	
	/** Asserts the status, and an {@code error} body in JSON. */
	private static void assertJsonError(String what, int status, MockHttpServletResponse response) throws Exception {
		assertEquals(what, status, response.getStatus());
		assertTrue(what + " " + response.getContentType(),
		    MediaType.APPLICATION_JSON.equalsTypeAndSubtype(MediaType.parseMediaType(response.getContentType())));
		assertTrue(what + " " + response.getContentAsString(), response.getContentAsString().startsWith("{\"error\":{"));
	}
	
	private static MockHttpServletRequest post(String uri, String json) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
		request.addHeader("Accept", "application/json");
		request.setContentType("application/json");
		request.setContent(json.getBytes(StandardCharsets.UTF_8));
		return request;
	}
	
	private HandlerMethod handler(MockHttpServletRequest request) throws Exception {
		HandlerExecutionChain chain = null;
		for (RequestMappingHandlerMapping mapping : mappings) {
			chain = chain == null ? mapping.getHandler(request) : chain;
		}
		assertNotNull(chain);
		return (HandlerMethod) chain.getHandler();
	}
	
	private MockHttpServletResponse dispatch(MockHttpServletRequest request) throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		HandlerMethod handler = handler(request);
		try {
			adapter.handle(request, response, handler);
		}
		catch (Exception e) {
			assertNotNull(e.toString(), resolver.resolveException(request, response, handler, e));
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
