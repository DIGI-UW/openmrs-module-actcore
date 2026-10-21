/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.logs;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.hibernate.SessionFactory;
import org.openmrs.api.context.Context;

/** A query's rows over JDBC on the current session, each as a map of column name to value. */
public final class Rows {
	
	/** A log page's size when the request gives none, and the most it may ask for. */
	public static final int DEFAULT_LIMIT = 20;
	
	public static final int MAX_LIMIT = 100;
	
	private Rows() {
	}
	
	/** The slice of a list a page shows: startIndex from 0, limit within the bounds. */
	static <T> List<T> page(List<T> all, int startIndex, int limit) {
		int from = Math.min(Math.max(startIndex, 0), all.size());
		int size = limit < 1 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
		return all.subList(from, Math.min(from + size, all.size()));
	}
	
	/**
	 * Column names as the database returns them, so H2's upper case and MariaDB's declared case both
	 * read.
	 */
	static List<Map<String, Object>> select(final String sql, final Object... params) {
		SessionFactory sessionFactory = Context.getRegisteredComponents(SessionFactory.class).get(0);
		return sessionFactory.getCurrentSession().doReturningWork(connection -> {
			List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
			PreparedStatement statement = connection.prepareStatement(sql);
			try {
				for (int i = 0; i < params.length; i++) {
					statement.setObject(i + 1, params[i]);
				}
				ResultSet results = statement.executeQuery();
				ResultSetMetaData columns = results.getMetaData();
				while (results.next()) {
					Map<String, Object> row = new TreeMap<String, Object>(String.CASE_INSENSITIVE_ORDER);
					for (int i = 1; i <= columns.getColumnCount(); i++) {
						Object value = results.getObject(i);
						// MariaDB's driver gives datetime columns as LocalDateTime, H2 as Timestamp.
						if (value instanceof LocalDateTime) {
							value = Timestamp.valueOf((LocalDateTime) value);
						} else if (value instanceof LocalDate) {
							value = java.sql.Date.valueOf((LocalDate) value);
						}
						row.put(columns.getColumnLabel(i), value);
					}
					rows.add(row);
				}
				return rows;
			}
			finally {
				statement.close();
			}
		});
	}
	
	/** "?, ?, ?" for an in-list of this many values. */
	static String placeholders(Collection<?> values) {
		StringBuilder list = new StringBuilder();
		for (int i = 0; i < values.size(); i++) {
			list.append(i == 0 ? "?" : ", ?");
		}
		return list.toString();
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
