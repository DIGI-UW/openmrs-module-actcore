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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
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
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.condition.PatternsRequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

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
	
	/** The segments of ACT Core's path. */
	private static final String[] ACT_CORE = { "rest", "v1", "actcore" };
	
	/** The values tried for a wildcard or variable in a segment of a pattern. */
	private static final String[] FILLERS = { "probe", "1", "a", "A" };
	
	/** What a client may accept, which an error answers in JSON unless it is XML. */
	private static final String[] ACCEPTS = { "application/json", "text/plain", "text/csv" };
	
	/** What a client may accept that admits neither JSON nor XML, or that is not a media type. */
	private static final String[] UNMET_ACCEPTS = { "text/plain", "text/csv", "text/html", "not a type" };
	
	/** What a client may accept that admits JSON or XML, which ACT Core answers in. */
	private static final String[] MET_ACCEPTS = { "application/json", "application/xml", "text/xml", "*/*", "application/*",
	        "text/plain, application/json", null };
	
	/**
	 * Each ACT Core path, with an example value for a path variable, and the methods it takes, as the
	 * {@code Allow} header names them.
	 */
	private static final String[][] PATHS = { { "/activity", "GET,HEAD,OPTIONS" }, { "/activity/42", "GET,HEAD,OPTIONS" },
	        { "/cardiology", "GET,HEAD,OPTIONS" }, { "/download", "GET,HEAD,POST,OPTIONS" },
	        { "/download/record", "POST,OPTIONS" }, { "/gap", "GET,HEAD,OPTIONS" }, { "/nextsteps", "GET,HEAD,OPTIONS" },
	        { "/prophylaxis", "GET,HEAD,OPTIONS" }, { "/raisedflags", "GET,HEAD,OPTIONS" },
	        { "/raisedflags/dismiss", "POST,OPTIONS" }, { "/reads", "GET,HEAD,OPTIONS" },
	        { "/refresh", "GET,HEAD,POST,OPTIONS" }, { "/runchart", "GET,HEAD,OPTIONS" },
	        { "/screeningcounts", "GET,HEAD,POST,OPTIONS" }, { "/screeningcounts/summary", "GET,HEAD,OPTIONS" },
	        { "/studies", "GET,HEAD,POST,OPTIONS" }, { "/studies/" + UUID.randomUUID(), "POST,OPTIONS" } };
	
	/**
	 * The methods a client may send, other than OPTIONS; the servlet refuses TRACE before Spring sees
	 * it.
	 */
	private static final String[] METHODS = { "GET", "HEAD", "POST", "PUT", "PATCH", "DELETE" };
	
	/**
	 * Paths under ACT Core's that no endpoint takes, including those webservices.rest's patterns match.
	 */
	private static final String[] UNKNOWN_PATHS = { "", "/", "/nosuch", "/nosuch/x", "/search/x", "/studies/x/y",
	        "/nosuch/x/y", "/nosuch/x/y/z", "/nosuch/x/y/z/w" };
	
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
		
		SimpleObject body = screeningCounts
		        .unsupportedMediaTypeHandler(new HttpMediaTypeNotSupportedException(MediaType.TEXT_PLAIN,
		                Collections.singletonList(MediaType.APPLICATION_JSON)),
		            new MockHttpServletRequest(), response);
		
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
	public void answersARefusedRequestInJsonOrInTheXmlItAccepts() throws Exception {
		Object[][] asJson = refusedAsAdmin();
		Object[][] asXml = refusedAsAdmin();
		for (int i = 0; i < asJson.length; i++) {
			MockHttpServletRequest xml = (MockHttpServletRequest) asXml[i][1];
			accept(xml, "application/xml");
			
			assertJsonError(asJson[i][2] + "", (Integer) asJson[i][0], dispatch((MockHttpServletRequest) asJson[i][1]));
			assertXmlError(asXml[i][2] + "", (Integer) asXml[i][0], dispatch(xml));
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
	public void answersAMissingPrivilegeInJsonOrInTheXmlItAccepts() throws Exception {
		authenticateWith(PrivilegeConstants.GET_PROGRAMS);
		for (String accept : new String[] { "application/json", "application/xml" }) {
			for (MockHttpServletRequest request : new MockHttpServletRequest[] {
			        post("/rest/v1/actcore/studies", "{\"name\":\"ARC\"}"), cardiology(UUID.randomUUID().toString()),
			        get("/rest/v1/actcore/refresh") }) {
				accept(request, accept);
				MockHttpServletResponse response = dispatch(request);
				
				if (accept.endsWith("xml")) {
					assertXmlError(request.getRequestURI(), 403, response);
				} else {
					assertJsonError(request.getRequestURI(), 403, response);
				}
			}
		}
	}
	
	@Test
	public void answersARequestWithoutAUserInJsonOrInTheXmlItAccepts() throws Exception {
		Context.logout();
		MockHttpServletResponse response = dispatch(post("/rest/v1/actcore/studies", "{\"name\":\"ARC\"}"));
		assertJsonError("json", 401, response);
		assertNotNull(response.getHeader("WWW-Authenticate"));
		
		MockHttpServletRequest request = post("/rest/v1/actcore/studies", "{\"name\":\"ARC\"}");
		accept(request, "application/xml");
		response = dispatch(request);
		
		assertXmlError("xml", 401, response);
		assertNotNull(response.getHeader("WWW-Authenticate"));
	}
	
	@Test
	public void answersAFailedConversionInJsonUnlessTheRequestAcceptsXml() throws Exception {
		for (String accept : new String[] { "application/json", "text/plain", "text/csv", "not a type",
		        "application/xml" }) {
			MockHttpServletRequest request = cardiology(null);
			accept(request, accept);
			MockHttpServletResponse response = new MockHttpServletResponse();
			
			assertNotNull(accept,
			    resolver.resolveException(request, response, handler(request), new ConversionException("unconvertible")));
			if (accept.endsWith("xml")) {
				assertXmlError(accept, 400, response);
			} else {
				assertJsonError(accept, 400, response);
			}
		}
	}
	
	@Test
	public void answersABodyOfTheWrongContentTypeInTheXmlTheRequestAccepts() throws Exception {
		MockHttpServletRequest request = bodyPost("/rest/v1/actcore/studies", "name=ARC");
		request.setContentType("text/plain");
		accept(request, "application/xml");
		
		assertXmlError("text/plain", 415, dispatch(request));
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
	
	@Test
	public void checksEveryMethodOfEveryActCoreEndpoint() {
		AntPathMatcher matcher = new AntPathMatcher();
		Set<String> endpoints = new HashSet<>();
		for (RequestMappingHandlerMapping mapping : mappings) {
			for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mapping.getHandlerMethods().entrySet()) {
				if (!isActCoreEndpoint(entry.getValue())) {
					continue;
				}
				for (String pattern : entry.getKey().getPatternsCondition().getPatterns()) {
					for (RequestMethod method : entry.getKey().getMethodsCondition().getMethods()) {
						endpoints.add(method + " " + pattern);
						boolean checked = false;
						for (String[] path : PATHS) {
							checked |= matcher.match(pattern, "/rest/v1/actcore" + path[0])
							        && Arrays.asList(path[1].split(",")).contains(method.name());
						}
						assertTrue(method + " " + pattern, checked);
					}
				}
			}
		}
		assertEquals(21, endpoints.size());
	}
	
	/**
	 * Every handler with a pattern that can match a path under ACT Core's is an ACT Core endpoint or is
	 * outranked there by {@link FallbackController}, which lists the methods of ACT Core endpoints
	 * only, so that it names them. webservices.rest's resource patterns, such as
	 * {@code /rest/v1/{resource}}, match such paths; they are let through because the fallback exists
	 * to outrank them, among them {@code /{resource}/search/{searchHandlerId}}, which ties the
	 * fallback's count and loses only on length. A pattern is checked when the mapping's path matcher
	 * matches its start to {@code /rest/v1/actcore}, and the test fails when it cannot build a path
	 * under ACT Core's that the pattern matches, rather than passing the pattern by. The paths are
	 * built by {@link #actCorePathsFor(PathMatcher, String)}. At each, the handler's best matching
	 * pattern is compared with the fallback's in the same mapping by the mapping's own conditions, as
	 * Spring compares them for every method but HEAD, which it compares by method first, so a handler
	 * that lists HEAD is not checked for it.
	 */
	@Test
	public void makesEveryHandlerUnderActCoresPathAnActCoreEndpoint() {
		Set<String> outranked = new HashSet<>();
		for (RequestMappingHandlerMapping mapping : mappings) {
			for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mapping.getHandlerMethods().entrySet()) {
				if (ActCoreRestController.class.isAssignableFrom(entry.getValue().getBeanType())) {
					continue;
				}
				for (String pattern : entry.getKey().getPatternsCondition().getPatterns()) {
					if (!mapping.getPathMatcher().matchStart(pattern, "/rest/v1/actcore")) {
						continue;
					}
					List<String> paths = actCorePathsFor(mapping.getPathMatcher(), pattern);
					assertFalse(pattern + " " + entry.getValue(), paths.isEmpty());
					for (String path : paths) {
						String what = pattern + " " + entry.getValue() + " at " + path;
						MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
						PatternsRequestCondition handler = entry.getKey().getPatternsCondition()
						        .getMatchingCondition(request);
						PatternsRequestCondition fallback = fallbackPatternsFor(mapping, request);
						assertNotNull(what, handler);
						assertTrue(what, fallback != null && fallback.compareTo(handler, request) < 0);
					}
					outranked.add(pattern);
				}
			}
		}
		assertTrue(outranked.toString(), outranked.contains("/rest/v1/{resource}/search/{searchHandlerId}"));
	}
	
	/**
	 * Builds the paths under ACT Core's that a pattern matches, putting {@code rest}, {@code v1} and
	 * {@code actcore} in the first three segments and, below them, each segment of the pattern with a
	 * wildcard or variable filled by {@link #fill(PathMatcher, String)}. A {@code **} spans every
	 * number of segments that leaves the path at most four below ACT Core's, or no longer in all than
	 * the pattern has segments: from four down the fallback's only match is its {@code /**}, so a
	 * deeper path meets the same fallback pattern. A path ends with a slash when the pattern does.
	 */
	private static List<String> actCorePathsFor(PathMatcher matcher, String pattern) {
		String[] segments = pattern.substring(1).split("/");
		List<String> paths = new ArrayList<>();
		addActCorePaths(matcher, segments, 0, new ArrayList<>(), Math.max(ACT_CORE.length + 4, segments.length), paths);
		if (pattern.endsWith("/")) {
			paths.replaceAll(path -> path + "/");
		}
		return paths;
	}
	
	private static void addActCorePaths(PathMatcher matcher, String[] segments, int next, List<String> path, int deepest,
	        List<String> paths) {
		if (next == segments.length) {
			if (path.size() >= ACT_CORE.length && path.size() <= deepest) {
				paths.add("/" + String.join("/", path));
			}
			return;
		}
		String segment = segments[next];
		List<String> longer = new ArrayList<>(path);
		if (segment.equals("**")) {
			while (longer.size() <= deepest) {
				addActCorePaths(matcher, segments, next + 1, new ArrayList<>(longer), deepest, paths);
				longer.add(longer.size() < ACT_CORE.length ? ACT_CORE[longer.size()] : "probe");
			}
			return;
		}
		String value = path.size() < ACT_CORE.length ? ACT_CORE[path.size()] : fill(matcher, segment);
		if (matcher.match("/" + segment, "/" + value)) {
			longer.add(value);
			addActCorePaths(matcher, segments, next + 1, longer, deepest, paths);
		}
	}
	
	/**
	 * Fills each wildcard and variable of a pattern's segment with one of {@link #FILLERS}, keeping its
	 * literal text, and fails when no filler gives a segment the pattern matches.
	 */
	private static String fill(PathMatcher matcher, String segment) {
		for (String filler : FILLERS) {
			StringBuilder value = new StringBuilder();
			int depth = 0;
			for (char c : segment.toCharArray()) {
				if (c == '{') {
					depth++;
				} else if (c == '}') {
					if (--depth == 0) {
						value.append(filler);
					}
				} else if (depth == 0) {
					value.append(c == '*' ? filler : c == '?' ? filler.substring(0, 1) : String.valueOf(c));
				}
			}
			if (matcher.match("/" + segment, "/" + value)) {
				return value.toString();
			}
		}
		fail("no filler makes a segment that " + segment + " matches");
		return null;
	}
	
	/**
	 * The patterns of {@link FallbackController} that Spring prefers for a request, or null when none
	 * matches it.
	 */
	private static PatternsRequestCondition fallbackPatternsFor(RequestMappingHandlerMapping mapping,
	        MockHttpServletRequest request) {
		PatternsRequestCondition best = null;
		for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mapping.getHandlerMethods().entrySet()) {
			if (entry.getValue().getBeanType() == FallbackController.class) {
				PatternsRequestCondition patterns = entry.getKey().getPatternsCondition().getMatchingCondition(request);
				if (patterns != null && (best == null || patterns.compareTo(best, request) < 0)) {
					best = patterns;
				}
			}
		}
		return best;
	}
	
	/**
	 * Every ACT Core endpoint that takes a request only with a parameter, header, content type or
	 * {@code Accept} it names has a sibling with its pattern and method that names none. A request that
	 * meets an endpoint's path and method but not such a condition otherwise reaches
	 * {@link FallbackController}, which answers 405 while naming the method in {@code Allow}, rather
	 * than the 400, 415 or 406 Spring would give. A sibling counts only when it names the same pattern,
	 * not another pattern that matches the same paths.
	 */
	@Test
	public void givesEveryConditionedActCoreEndpointASiblingWithoutConditions() {
		List<RequestMappingInfo> endpoints = new ArrayList<>();
		for (RequestMappingHandlerMapping mapping : mappings) {
			for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mapping.getHandlerMethods().entrySet()) {
				if (isActCoreEndpoint(entry.getValue())) {
					endpoints.add(entry.getKey());
				}
			}
		}
		Set<String> conditioned = new HashSet<>();
		for (RequestMappingInfo endpoint : endpoints) {
			assertFalse(endpoint.toString(), endpoint.getMethodsCondition().getMethods().isEmpty());
			if (isUnconditioned(endpoint)) {
				continue;
			}
			for (String pattern : endpoint.getPatternsCondition().getPatterns()) {
				for (RequestMethod method : endpoint.getMethodsCondition().getMethods()) {
					boolean sibling = false;
					for (RequestMappingInfo other : endpoints) {
						sibling |= isUnconditioned(other) && other.getPatternsCondition().getPatterns().contains(pattern)
						        && other.getMethodsCondition().getMethods().contains(method);
					}
					assertTrue(method + " " + pattern + " " + endpoint, sibling);
					conditioned.add(method + " " + pattern);
				}
			}
		}
		assertTrue(conditioned.toString(), conditioned.contains("POST /rest/v1/actcore/refresh"));
	}
	
	@Test
	public void sendsEveryMethodAPathTakesToItsEndpoint() throws Exception {
		for (String[] path : PATHS) {
			for (String suffix : new String[] { "", "/" }) {
				for (String method : path[1].split(",")) {
					if ("OPTIONS".equals(method)) {
						continue;
					}
					String what = method + " " + path[0] + suffix;
					MockHttpServletRequest request = new MockHttpServletRequest(method,
					        "/rest/v1/actcore" + path[0] + suffix);
					
					assertTrue(what, isActCoreEndpoint(handler(request)));
				}
			}
		}
	}
	
	@Test
	public void answersEveryMethodAPathDoesNotTakeWithMethodNotAllowed() throws Exception {
		List<LogEvent> logged = new ArrayList<>();
		int refused = 0;
		for (String[] path : PATHS) {
			for (String method : METHODS) {
				if (Arrays.asList(path[1].split(",")).contains(method)) {
					continue;
				}
				for (String contentType : new String[] { null, "application/json", "text/plain" }) {
					for (String accept : new String[] { "application/json", "application/xml" }) {
						String what = method + " " + path[0] + " " + contentType + " " + accept;
						MockHttpServletRequest request = request(method, path[0], contentType, accept);
						
						MockHttpServletResponse response = logging(logged, () -> dispatch(request));
						
						if (accept.endsWith("xml")) {
							assertXmlError(what, 405, response);
						} else {
							assertJsonError(what, 405, response);
						}
						assertEquals(what, path[1], response.getHeader("Allow"));
						assertWithoutAStackTrace(what, response);
						refused++;
					}
				}
			}
		}
		assertEquals(Collections.emptyList(), messages(logged));
		assertEquals(402, refused);
	}
	
	@Test
	public void answersAWrongMethodWhoseAcceptItCannotMeetWithMethodNotAllowedInJson() throws Exception {
		for (String accept : UNMET_ACCEPTS) {
			MockHttpServletResponse response = dispatch(request("PUT", "/refresh", "application/json", accept));
			
			assertJsonError(accept, 405, response);
			assertEquals("GET,HEAD,POST,OPTIONS", response.getHeader("Allow"));
		}
	}
	
	@Test
	public void answersOptionsWithTheMethodsAPathTakes() throws Exception {
		for (String[] path : PATHS) {
			MockHttpServletResponse response = dispatch(request("OPTIONS", path[0], null, "application/json"));
			
			assertEquals(path[0], 200, response.getStatus());
			assertEquals(path[0], path[1], response.getHeader("Allow"));
			assertEquals(path[0], "", response.getContentAsString());
		}
	}
	
	@Test
	public void answersAPathNoActCoreEndpointTakesWithNotFound() throws Exception {
		List<LogEvent> logged = new ArrayList<>();
		for (String path : UNKNOWN_PATHS) {
			for (String method : new String[] { "GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS" }) {
				for (String accept : new String[] { "application/json", "application/xml" }) {
					String what = method + " " + path + " " + accept;
					MockHttpServletRequest request = request(method, path, "application/json", accept);
					
					MockHttpServletResponse response = logging(logged, () -> dispatch(request));
					
					if (accept.endsWith("xml")) {
						assertXmlError(what, 404, response);
					} else {
						assertJsonError(what, 404, response);
					}
					assertNull(what, response.getHeader("Allow"));
					assertWithoutAStackTrace(what, response);
				}
			}
		}
		assertEquals(Collections.emptyList(), messages(logged));
	}
	
	@Test
	public void leavesPathsOutsideActCoreToTheirEndpoints() throws Exception {
		for (String uri : new String[] { "/rest/v1/patient/" + UUID.randomUUID(), "/rest/v1/actcorex/runchart",
		        "/rest/v1/location" }) {
			for (String method : new String[] { "GET", "POST" }) {
				HandlerMethod handler = handler(new MockHttpServletRequest(method, uri));
				
				assertFalse(method + " " + uri, ActCoreRestController.class.isAssignableFrom(handler.getBeanType()));
			}
		}
	}
	
	@Test
	public void refusesAJsonSuffixedBodyWhoseAcceptItCannotMeetWithoutSavingIt() throws Exception {
		ModuleTables.create();
		MockHttpServletRequest request = screeningCountsForAugust();
		request.setContentType("application/vnd.api+json");
		accept(request, "text/plain");
		
		assertNotAcceptable("text/plain", dispatch(request));
		assertEquals(Boolean.FALSE, screeningCounts.get(CLINIC, "2026-08").get("saved"));
		
		request = screeningCountsForAugust();
		request.setContentType("application/vnd.api+json");
		assertEquals(200, dispatch(request).getStatus());
		assertEquals(Boolean.TRUE, screeningCounts.get(CLINIC, "2026-08").get("saved"));
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
	
	/**
	 * Tells whether a handler is an ACT Core endpoint rather than the fallback for the paths none
	 * takes.
	 */
	private static boolean isActCoreEndpoint(HandlerMethod handler) {
		return ActCoreRestController.class.isAssignableFrom(handler.getBeanType())
		        && handler.getBeanType() != FallbackController.class;
	}
	
	/**
	 * Tells whether a mapping takes a request whatever its parameters, headers and media types, and has
	 * no custom condition.
	 */
	private static boolean isUnconditioned(RequestMappingInfo info) {
		return info.getParamsCondition().isEmpty() && info.getHeadersCondition().isEmpty()
		        && info.getConsumesCondition().isEmpty() && info.getProducesCondition().isEmpty()
		        && info.getCustomCondition() == null;
	}
	
	/** A request to an ACT Core path, with a JSON body when it has a content type. */
	private static MockHttpServletRequest request(String method, String path, String contentType, String accept) {
		MockHttpServletRequest request = new MockHttpServletRequest(method, "/rest/v1/actcore" + path);
		request.addHeader("Accept", accept);
		if (contentType != null) {
			request.setContentType(contentType);
			request.setContent("{\"name\":\"ARC\"}".getBytes(StandardCharsets.UTF_8));
		}
		return request;
	}
	
	private interface Dispatch {
		
		MockHttpServletResponse run() throws Exception;
	}
	
	/** Runs a dispatch, adding what any logger logs at INFO or above while it runs. */
	private static MockHttpServletResponse logging(List<LogEvent> logged, Dispatch dispatch) throws Exception {
		AbstractAppender appender = new AbstractAppender("capture", null, null, true, Property.EMPTY_ARRAY) {
			
			@Override
			public void append(LogEvent event) {
				logged.add(event.toImmutable());
			}
		};
		appender.start();
		LoggerContext context = (LoggerContext) LogManager.getContext(false);
		LoggerConfig root = context.getConfiguration().getRootLogger();
		Level level = root.getLevel();
		root.addAppender(appender, Level.INFO, null);
		// The test log4j2.xml turns every logger off.
		root.setLevel(Level.INFO);
		context.updateLoggers();
		try {
			return dispatch.run();
		}
		finally {
			root.removeAppender("capture");
			root.setLevel(level);
			context.updateLoggers();
		}
	}
	
	private static List<String> messages(List<LogEvent> logged) {
		List<String> messages = new ArrayList<>();
		for (LogEvent event : logged) {
			messages.add(event.getLevel() + " " + event.getLoggerName() + " " + event.getMessage().getFormattedMessage());
		}
		return messages;
	}
	
	/** Asserts that a body gives the error's message alone, without a stack trace or a code. */
	private static void assertWithoutAStackTrace(String what, MockHttpServletResponse response) throws Exception {
		String body = response.getContentAsString();
		for (String trace : new String[] { "stackTrace", "detail", "code", "at org." }) {
			assertFalse(what + " " + body, body.contains(trace));
		}
	}
	
	/** Asserts 406 with an empty body. */
	private static void assertNotAcceptable(String what, MockHttpServletResponse response) throws Exception {
		assertEquals(what, 406, response.getStatus());
		assertEquals(what, "", response.getContentAsString());
	}
	
	/** Asserts the status, and an {@code error} body in XML that holds a {@code message}. */
	private static void assertXmlError(String what, int status, MockHttpServletResponse response) throws Exception {
		assertEquals(what, status, response.getStatus());
		assertTrue(what + " " + response.getContentType(),
		    MediaType.APPLICATION_XML.equalsTypeAndSubtype(MediaType.parseMediaType(response.getContentType())));
		Element object = DocumentBuilderFactory.newInstance().newDocumentBuilder()
		        .parse(new InputSource(new StringReader(response.getContentAsString()))).getDocumentElement();
		assertEquals(what, "object", object.getTagName());
		Element error = child(object, "error");
		assertNotNull(what + " " + response.getContentAsString(), error);
		assertNotNull(what + " " + response.getContentAsString(), child(error, "message"));
	}
	
	/** The first child element of an element with a name, or null. */
	private static Element child(Element parent, String name) {
		for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
			if (node instanceof Element && name.equals(node.getNodeName())) {
				return (Element) node;
			}
		}
		return null;
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
