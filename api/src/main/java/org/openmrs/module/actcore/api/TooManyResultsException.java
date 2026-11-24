/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.api;

import org.openmrs.api.ValidationException;
import org.springframework.validation.BindException;
import org.springframework.validation.Errors;

/**
 * Thrown when a log's filter is too broad for one request to read. It is a validation error with
 * its message as the one global error, so the REST layer answers it with 400 and that message.
 */
public class TooManyResultsException extends ValidationException {
	
	private static final long serialVersionUID = 1L;
	
	/**
	 * Creates the exception.
	 *
	 * @param message what is too broad and how to narrow it
	 */
	public TooManyResultsException(String message) {
		super(message, errors(message));
	}
	
	private static Errors errors(String message) {
		Errors errors = new BindException(new Object(), "logFilter");
		errors.reject("actcore.logFilter", message);
		return errors;
	}
}
