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
import java.util.ArrayList;
import java.util.List;

import org.openmrs.api.db.hibernate.DbSessionFactory;
import org.openmrs.module.actcore.api.db.UserClinicsDAO;

/** Hibernate implementation of {@link org.openmrs.module.actcore.api.db.UserClinicsDAO}. */
public class HibernateUserClinicsDAO implements UserClinicsDAO {
	
	private DbSessionFactory sessionFactory;
	
	public void setSessionFactory(DbSessionFactory sessionFactory) {
		this.sessionFactory = sessionFactory;
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.db.UserClinicsDAO#getUserProperty(Integer, String)
	 */
	@Override
	public String getUserProperty(Integer userId, String property) {
		return first(
		    column("select property_value from user_property where user_id = ? and property = ?", userId, property));
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.db.UserClinicsDAO#getGlobalProperty(String)
	 */
	@Override
	public String getGlobalProperty(String property) {
		return first(column("select property_value from global_property where property = ?", property));
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.db.UserClinicsDAO#getRoleUuids(Integer)
	 */
	@Override
	public List<String> getRoleUuids(Integer userId) {
		return column("select r.uuid from user_role ur join role r on r.role = ur.role where ur.user_id = ?", userId);
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.db.UserClinicsDAO#getRoleName(String)
	 */
	@Override
	public String getRoleName(String uuid) {
		return first(column("select role from role where uuid = ?", uuid));
	}
	
	private static String first(List<String> values) {
		return values.isEmpty() ? null : values.get(0);
	}
	
	/** Runs a query over JDBC, which Hibernate does not flush for, and returns its first column. */
	private List<String> column(String query, Object... params) {
		return sessionFactory.getCurrentSession().doReturningWork(connection -> {
			List<String> values = new ArrayList<String>();
			try (PreparedStatement statement = connection.prepareStatement(query)) {
				for (int i = 0; i < params.length; i++) {
					statement.setObject(i + 1, params[i]);
				}
				ResultSet rows = statement.executeQuery();
				while (rows.next()) {
					values.add(rows.getString(1));
				}
			}
			return values;
		});
	}
}
