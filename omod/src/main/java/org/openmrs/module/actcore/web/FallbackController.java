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

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Controller;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Answers a request under ACT Core's path that none of its endpoints takes: 405 when the path is an
 * endpoint's but the method is not, 404 when the path is no endpoint's, and an OPTIONS request with
 * the methods the path takes. Without it Spring refuses a wrong method while it is still choosing a
 * handler, so no ACT Core exception handler sees the refusal and core's exception resolver answers
 * it with 200 and the exception's stack trace, while a request that matches one of
 * webservices.rest's resource patterns, such as a POST to a path that takes only GET, reaches
 * webservices.rest as a request for a resource named v1/actcore. Spring prefers the pattern with
 * the fewest wildcards and path variables, which it counts alike, and between equal counts the
 * longer, counting a variable as one character. These patterns have more than any ACT Core
 * endpoint's that match the same paths, and fewer than webservices.rest 3.5.0's, except
 * {@code /{resource}/search/{searchHandlerId}}, which has as many as the two-segment pattern here
 * and loses to it only on length, so shortening these patterns hands such a path back to
 * webservices.rest. The one ending in {@code /**}, which Spring ranks below any pattern without
 * one, takes only the paths too deep for the others.
 */
@Controller
@RequestMapping("/rest/" + RestConstants.VERSION_1 + "/actcore")
public class FallbackController extends ActCoreRestController {
	
	@Autowired
	private List<RequestMappingHandlerMapping> mappings;
	
	/**
	 * Answers a wrong method or an unknown path before a refused {@code Accept}, as Spring does when it
	 * refuses them while choosing a handler.
	 *
	 * @see ActCoreRestController#refuseAnAcceptWithoutJsonOrXml(HttpServletRequest)
	 */
	@Override
	public void refuseAnAcceptWithoutJsonOrXml(HttpServletRequest request) {
	}
	
	/**
	 * Refuses a request that no ACT Core endpoint takes, or answers an OPTIONS request with the methods
	 * its path takes. The methods are listed rather than left open because Spring compares a HEAD
	 * request's matches by method before pattern and prefers a listed method to none, so an open
	 * mapping would lose a HEAD request to webservices.rest's GET patterns. GET, which also matches
	 * HEAD, ties them so that the pattern decides; HEAD is not listed, since Spring would prefer it to
	 * an endpoint's GET. OPTIONS is listed because Spring would otherwise answer it with every method a
	 * pattern matching the path takes, these included.
	 *
	 * @param request the request
	 * @param response the response, whose {@code Allow} header names the methods an OPTIONS request's
	 *            path takes
	 * @throws HttpRequestMethodNotSupportedException when an ACT Core endpoint has the path but not the
	 *             method
	 * @throws NoHandlerFoundException when no ACT Core endpoint has the path
	 */
	@RequestMapping(value = { "", "/*", "/*/*", "/*/*/*", "/**" }, method = { RequestMethod.GET, RequestMethod.POST,
	        RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE, RequestMethod.OPTIONS })
	public void refuse(HttpServletRequest request, HttpServletResponse response)
	        throws HttpRequestMethodNotSupportedException, NoHandlerFoundException {
		List<String> allowed = allowedMethods(request);
		if (allowed.isEmpty()) {
			throw new NoHandlerFoundException(request.getMethod(), request.getRequestURI(), new HttpHeaders());
		}
		if (RequestMethod.OPTIONS.name().equals(request.getMethod())) {
			response.setHeader(HttpHeaders.ALLOW, StringUtils.join(allowed, ","));
			return;
		}
		throw new HttpRequestMethodNotSupportedException(request.getMethod(), allowed);
	}
	
	/**
	 * Answers a method the path does not take with 405, an {@code Allow} header naming the methods it
	 * takes, and the exception's message in the JSON or XML the request accepts, and in JSON when it
	 * accepts neither.
	 *
	 * @param e the HttpRequestMethodNotSupportedException
	 * @param request the request
	 * @param response the response, whose status is set to 405
	 * @return an {@code error} holding the {@code message}, without a stack trace
	 */
	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	@ResponseBody
	public SimpleObject methodNotAllowedHandler(HttpRequestMethodNotSupportedException e, HttpServletRequest request,
	        HttpServletResponse response) {
		response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
		response.setHeader(HttpHeaders.ALLOW, StringUtils.join(e.getSupportedMethods(), ","));
		jsonUnlessJsonOrXmlIsAccepted(request, response);
		return new SimpleObject().add("error", new SimpleObject().add("message", e.getMessage()));
	}
	
	/**
	 * Answers a path no ACT Core endpoint has with 404 and the exception's message in the JSON or XML
	 * the request accepts, and in JSON when it accepts neither.
	 *
	 * @param e the NoHandlerFoundException
	 * @param request the request
	 * @param response the response, whose status is set to 404
	 * @return an {@code error} holding the {@code message}, without a stack trace
	 */
	@ExceptionHandler(NoHandlerFoundException.class)
	@ResponseBody
	public SimpleObject notFoundHandler(NoHandlerFoundException e, HttpServletRequest request,
	        HttpServletResponse response) {
		response.setStatus(HttpServletResponse.SC_NOT_FOUND);
		jsonUnlessJsonOrXmlIsAccepted(request, response);
		return new SimpleObject().add("error", new SimpleObject().add("message", e.getMessage()));
	}
	
	/**
	 * Lists the methods the ACT Core endpoints with a request's path take, as Spring's own answer to
	 * OPTIONS names them: HEAD with GET, and OPTIONS with any.
	 *
	 * @param request the request, whose path Spring has looked up
	 * @return the methods in the order of {@link RequestMethod}; empty when no endpoint has the path
	 */
	private List<String> allowedMethods(HttpServletRequest request) {
		Set<RequestMethod> methods = EnumSet.noneOf(RequestMethod.class);
		for (RequestMappingHandlerMapping mapping : mappings) {
			for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mapping.getHandlerMethods().entrySet()) {
				Class<?> type = entry.getValue().getBeanType();
				if (ActCoreRestController.class.isAssignableFrom(type) && type != FallbackController.class
				        && entry.getKey().getPatternsCondition().getMatchingCondition(request) != null) {
					methods.addAll(entry.getKey().getMethodsCondition().getMethods());
				}
			}
		}
		if (methods.isEmpty()) {
			return new ArrayList<>();
		}
		if (methods.contains(RequestMethod.GET)) {
			methods.add(RequestMethod.HEAD);
		}
		methods.add(RequestMethod.OPTIONS);
		List<String> names = new ArrayList<>();
		for (RequestMethod method : methods) {
			names.add(method.name());
		}
		return names;
	}
}
