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

import org.hibernate.FlushMode;
import org.openmrs.Program;
import org.openmrs.api.db.hibernate.DbSessionFactory;
import org.openmrs.module.actcore.api.db.StudyDAO;

/** Hibernate implementation of {@link org.openmrs.module.actcore.api.db.StudyDAO}. */
public class HibernateStudyDAO implements StudyDAO {
	
	private DbSessionFactory sessionFactory;
	
	public void setSessionFactory(DbSessionFactory sessionFactory) {
		this.sessionFactory = sessionFactory;
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.db.StudyDAO#getSavedProgram(Integer)
	 */
	@Override
	public Program getSavedProgram(Integer patientProgramId) {
		return (Program) sessionFactory.getCurrentSession()
		        .createQuery("select p.program from PatientProgram p where p.patientProgramId = :id")
		        .setParameter("id", patientProgramId).setHibernateFlushMode(FlushMode.MANUAL).uniqueResult();
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.db.StudyDAO#isSavedOpenWithoutAttribute(Integer, String)
	 */
	@Override
	public boolean isSavedOpenWithoutAttribute(Integer patientProgramId, String attributeTypeUuid) {
		List<?> attributes = sessionFactory.getCurrentSession()
		        .createQuery("select (select count(*) from PatientProgramAttribute a where a.patientProgram = p"
		                + " and a.attributeType.uuid = :type and a.voided = false and trim(a.valueReference) <> '')"
		                + " from PatientProgram p where p.patientProgramId = :id and p.dateCompleted is null")
		        .setParameter("type", attributeTypeUuid).setParameter("id", patientProgramId)
		        .setHibernateFlushMode(FlushMode.MANUAL).list();
		return !attributes.isEmpty() && ((Number) attributes.get(0)).intValue() == 0;
	}
}
