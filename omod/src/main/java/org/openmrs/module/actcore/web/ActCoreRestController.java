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

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.context.ContextAuthenticationException;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.v1_0.controller.BaseRestController;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * The base class of the module's REST controllers. It answers a request parameter that is missing
 * or of the wrong type with 400, and a privilege that
 * {@link org.openmrs.api.context.Context#requirePrivilege(String)} refuses with 401 or 403, as for
 * an {@link APIAuthenticationException}. BaseRestController would answer both with 500.
 */
public abstract class ActCoreRestController extends BaseRestController {
	
	/**
	 * Answers a request parameter that is missing or of the wrong type with 400 and a message naming
	 * it.
	 *
	 * @param e the MethodArgumentTypeMismatchException or MissingServletRequestParameterException
	 * @param response the response, whose status is set to 400
	 * @return an {@code error} holding the {@code message}, without a stack trace
	 */
	@ExceptionHandler({ MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class })
	@ResponseBody
	public SimpleObject badParameterHandler(Exception e, HttpServletResponse response) {
		response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
		String message;
		if (e instanceof MethodArgumentTypeMismatchException) {
			MethodArgumentTypeMismatchException mismatch = (MethodArgumentTypeMismatchException) e;
			message = mismatch.getName() + " cannot be " + mismatch.getValue();
		} else {
			message = ((MissingServletRequestParameterException) e).getParameterName() + " is required";
		}
		// No stack trace or code: the caller sent the request wrong, and the server has nothing to show.
		return new SimpleObject().add("error", new SimpleObject().add("message", message));
	}
	
	/**
	 * Answers the {@link ContextAuthenticationException} that
	 * {@link org.openmrs.api.context.Context#requirePrivilege(String)} throws as BaseRestController
	 * answers an {@link APIAuthenticationException}: 401 when no user is logged in, else 403.
	 *
	 * @param e the ContextAuthenticationException
	 * @param request the request
	 * @param response the response, whose status is set to 401 or 403
	 * @return an {@code error} holding the {@code message} and the exception's detail
	 * @throws Exception if BaseRestController's handler fails
	 */
	@ExceptionHandler(ContextAuthenticationException.class)
	@ResponseBody
	public SimpleObject privilegeRequiredHandler(ContextAuthenticationException e, HttpServletRequest request,
	        HttpServletResponse response) throws Exception {
		return apiAuthenticationExceptionHandler(e, request, response);
	}
}
