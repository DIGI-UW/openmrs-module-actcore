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

import java.util.List;

import org.openmrs.api.db.hibernate.DbSessionFactory;
import org.openmrs.module.actcore.api.db.FlagGapDAO;
import org.openmrs.util.DatabaseUtil;

/** Hibernate implementation of {@link org.openmrs.module.actcore.api.db.FlagGapDAO}. */
public class HibernateFlagGapDAO implements FlagGapDAO {
	
	private DbSessionFactory sessionFactory;
	
	public void setSessionFactory(DbSessionFactory sessionFactory) {
		this.sessionFactory = sessionFactory;
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.db.FlagGapDAO#getCriteriaRows(String)
	 */
	@Override
	public List<List<Object>> getCriteriaRows(String query) {
		return sessionFactory.getCurrentSession()
		        .doReturningWork(connection -> DatabaseUtil.executeSQL(connection, query, true));
	}
}
