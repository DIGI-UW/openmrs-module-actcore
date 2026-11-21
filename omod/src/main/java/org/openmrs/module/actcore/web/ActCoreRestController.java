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

import javax.servlet.http.HttpServletResponse;

import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.v1_0.controller.BaseRestController;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * The module's REST controllers: a parameter that is missing or of the wrong type is the caller's
 * error, which BaseRestController would answer as a server error.
 */
public abstract class ActCoreRestController extends BaseRestController {
	
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
}
