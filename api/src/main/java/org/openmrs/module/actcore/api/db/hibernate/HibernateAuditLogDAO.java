/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.api.db.hibernate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.openmrs.api.db.hibernate.DbSessionFactory;
import org.openmrs.module.actcore.api.db.AuditLogDAO;

/** Hibernate implementation of {@link org.openmrs.module.actcore.api.db.AuditLogDAO}. */
public class HibernateAuditLogDAO implements AuditLogDAO {
	
	private DbSessionFactory sessionFactory;
	
	public void setSessionFactory(DbSessionFactory sessionFactory) {
		this.sessionFactory = sessionFactory;
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.db.AuditLogDAO#select(String, Object...)
	 */
	@Override
	public List<Map<String, Object>> select(String sql, Object... params) {
		return sessionFactory.getCurrentSession().doReturningWork(connection -> {
			List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
			try (PreparedStatement statement = connection.prepareStatement(sql)) {
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
			}
			return rows;
		});
	}
}
