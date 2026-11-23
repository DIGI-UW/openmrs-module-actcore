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

import java.util.Collection;
import java.util.Date;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.hibernate.LockOptions;
import org.hibernate.Query;
import org.openmrs.Location;
import org.openmrs.api.db.hibernate.DbSessionFactory;
import org.openmrs.module.actcore.api.db.ScreeningCountDAO;
import org.openmrs.module.actcore.screening.ScreeningCount;
import org.openmrs.module.actcore.screening.ScreeningCounts;

/** Hibernate implementation of {@link org.openmrs.module.actcore.api.db.ScreeningCountDAO}. */
public class HibernateScreeningCountDAO implements ScreeningCountDAO {
	
	private DbSessionFactory sessionFactory;
	
	public void setSessionFactory(DbSessionFactory sessionFactory) {
		this.sessionFactory = sessionFactory;
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.db.ScreeningCountDAO#getCurrentCounts(Integer, Date)
	 */
	@Override
	@SuppressWarnings("unchecked")
	public List<ScreeningCount> getCurrentCounts(Integer locationId, Date month) {
		return sessionFactory.getCurrentSession()
		        .createQuery("from ScreeningCount where locationId = :clinic and month = :month and voided = false")
		        .setParameter("clinic", locationId).setParameter("month", month).list();
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.db.ScreeningCountDAO#lockLocation(Integer)
	 */
	@Override
	public void lockLocation(Integer locationId) {
		sessionFactory.getCurrentSession().get(Location.class, locationId, LockOptions.UPGRADE);
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.db.ScreeningCountDAO#saveScreeningCount(ScreeningCount)
	 */
	@Override
	public void saveScreeningCount(ScreeningCount count) {
		sessionFactory.getCurrentSession().save(count);
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.db.ScreeningCountDAO#getSummary(Collection, Date, Date)
	 */
	@Override
	public ScreeningCounts.Summary getSummary(Collection<Integer> locationIds, Date start, Date end) {
		StringBuilder select = new StringBuilder("select count(*)");
		for (String column : ScreeningCounts.COLUMNS) {
			select.append(", coalesce(sum(").append(property(column)).append("), 0)");
		}
		select.append(" from ScreeningCount where voided = false and month >= :start and month <= :end");
		if (locationIds != null) {
			select.append(" and locationId in (:clinics)");
		}
		Query query = sessionFactory.getCurrentSession().createQuery(select.toString());
		query.setParameter("start", start).setParameter("end", end);
		if (locationIds != null) {
			query.setParameterList("clinics", locationIds);
		}
		Object[] row = (Object[]) query.uniqueResult();
		ScreeningCounts.Summary summary = new ScreeningCounts.Summary();
		summary.months = ((Number) row[0]).intValue();
		for (int i = 0; i < ScreeningCounts.COLUMNS.size(); i++) {
			summary.sums.put(ScreeningCounts.COLUMNS.get(i), ((Number) row[i + 1]).longValue());
		}
		return summary;
	}
	
	/** The entity property of a count column: school_screened is schoolScreened. */
	private static String property(String column) {
		String[] parts = column.split("_");
		return parts[0] + StringUtils.capitalize(parts[1]);
	}
}
