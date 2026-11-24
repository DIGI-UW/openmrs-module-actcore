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
import org.springframework.validation.BindException;
import org.springframework.validation.Errors;

/**
 * Helpers the logs use to read the rows that {@link org.openmrs.module.actcore.api.db.AuditLogDAO}
 * gives, each a map of column name to value, and to page the results, and the factory of the
 * validation errors that ACT Core's services and calculations refuse a request with.
 */
public final class Rows {
	
	/** The number of entries a log page holds when the request does not say how many it wants. */
	public static final int DEFAULT_LIMIT = 20;
	
	/** The most entries a log page holds, whatever the request asks for. */
	public static final int MAX_LIMIT = 100;
	
	private Rows() {
	}
	
	/**
	 * Thrown when a log's filter is too broad for one request to read. It is a validation error with
	 * its message as the one global error, so the REST layer answers it with 400 and that message.
	 */
	public static class TooMany extends ValidationException {
		
		private static final long serialVersionUID = 1L;
		
		/**
		 * Creates the exception.
		 *
		 * @param message what is too broad and how to narrow it
		 */
		public TooMany(String message) {
			super(message, refusal("logFilter", message).getErrors());
		}
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
	 * falls back to {@link #DEFAULT_LIMIT} below 1 and is capped at {@link #MAX_LIMIT}.
	 */
	static <T> List<T> page(List<T> all, int startIndex, int limit) {
		int from = Math.min(Math.max(startIndex, 0), all.size());
		int size = limit < 1 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
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
