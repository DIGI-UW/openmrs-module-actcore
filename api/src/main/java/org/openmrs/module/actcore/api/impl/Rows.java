/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.api.impl;

import java.util.List;
import java.util.Map;

import org.openmrs.api.ValidationException;
import org.openmrs.module.actcore.ActCoreConstants;
import org.springframework.validation.BindException;
import org.springframework.validation.Errors;

/**
 * Helpers the logs use to read the rows that {@link org.openmrs.module.actcore.api.db.AuditLogDAO}
 * gives, each a map of column name to value, and to page the results, and the factory of the
 * validation errors that ACT Core's services and calculations refuse a request with.
 */
public final class Rows {
	
	private Rows() {
	}
	
	/**
	 * Creates a validation error whose message is its one global error. The REST layer answers it with
	 * 400 and that message, and reads a {@link ValidationException}'s errors without checking that it
	 * has any, so a refusal needs them.
	 *
	 * @param objectName the name of the object refused, as "runChart", which after "actcore." is the
	 *            global error's code
	 * @param message why the request is refused, which is also the global error's default message
	 * @return the validation error, to be thrown
	 */
	public static ValidationException refusal(String objectName, String message) {
		Errors errors = new BindException(new Object(), objectName);
		errors.reject("actcore." + objectName, message);
		return new ValidationException(message, errors);
	}
	
	/**
	 * Cuts out the slice of a list that one page shows. The start index counts from 0, and the limit
	 * falls back to {@link ActCoreConstants#LOG_DEFAULT_LIMIT} below 1 and is capped at
	 * {@link ActCoreConstants#LOG_MAX_LIMIT}.
	 */
	static <T> List<T> page(List<T> all, int startIndex, int limit) {
		int from = Math.min(Math.max(startIndex, 0), all.size());
		int size = limit < 1 ? ActCoreConstants.LOG_DEFAULT_LIMIT : Math.min(limit, ActCoreConstants.LOG_MAX_LIMIT);
		return all.subList(from, Math.min(from + size, all.size()));
	}
	
	static Integer integer(Map<String, Object> row, String column) {
		Object value = row.get(column);
		return value == null ? null : ((Number) value).intValue();
	}
	
	static boolean truthy(Map<String, Object> row, String column) {
		Object value = row.get(column);
		if (value instanceof Boolean) {
			return (Boolean) value;
		}
		return value instanceof Number && ((Number) value).intValue() != 0;
	}
	
	static String string(Map<String, Object> row, String column) {
		Object value = row.get(column);
		return value == null ? null : value.toString();
	}
	
	static java.util.Date date(Map<String, Object> row, String column) {
		Object value = row.get(column);
		return value instanceof java.util.Date ? (java.util.Date) value : null;
	}
}
