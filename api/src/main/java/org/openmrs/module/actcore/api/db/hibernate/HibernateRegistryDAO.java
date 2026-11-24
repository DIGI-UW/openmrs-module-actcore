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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import org.openmrs.api.db.hibernate.DbSessionFactory;
import org.openmrs.module.actcore.api.db.RegistryDAO;
import org.openmrs.util.DatabaseUtil;

/** Hibernate implementation of {@link org.openmrs.module.actcore.api.db.RegistryDAO}. */
public class HibernateRegistryDAO implements RegistryDAO {
	
	private DbSessionFactory sessionFactory;
	
	public void setSessionFactory(DbSessionFactory sessionFactory) {
		this.sessionFactory = sessionFactory;
	}
	
	/**
	 * Reads by SQL on the current session's connection, as AdministrationService.executeSQL does,
	 * without the SQL Level Access privilege that service asks for.
	 *
	 * @see org.openmrs.module.actcore.api.db.RegistryDAO#getDeadPatientsWithOpenEnrolments(Collection)
	 */
	@Override
	public List<Integer> getDeadPatientsWithOpenEnrolments(Collection<Integer> programIds) {
		String sql = "select distinct pp.patient_id from patient_program pp"
		        + " join person p on p.person_id = pp.patient_id where p.dead = true and p.voided = false and pp.voided = false"
		        + " and pp.date_completed is null and pp.program_id in ("
		        + programIds.stream().map(String::valueOf).collect(Collectors.joining(",")) + ")";
		List<Integer> patientIds = new ArrayList<Integer>();
		for (List<Object> row : sessionFactory.getCurrentSession()
		        .doReturningWork(connection -> DatabaseUtil.executeSQL(connection, sql, true))) {
			patientIds.add(((Number) row.get(0)).intValue());
		}
		return patientIds;
	}
}
