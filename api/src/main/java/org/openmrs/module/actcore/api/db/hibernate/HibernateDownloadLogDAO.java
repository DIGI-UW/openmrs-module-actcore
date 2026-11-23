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
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.Query;
import org.openmrs.api.db.hibernate.DbSessionFactory;
import org.openmrs.module.actcore.api.db.DownloadLogDAO;
import org.openmrs.module.actcore.download.DownloadLog;
import org.openmrs.module.actcore.download.DownloadRecord;
import org.openmrs.module.actcore.logs.Names;

/** Hibernate implementation of {@link org.openmrs.module.actcore.api.db.DownloadLogDAO}. */
public class HibernateDownloadLogDAO implements DownloadLogDAO {
	
	private DbSessionFactory sessionFactory;
	
	public void setSessionFactory(DbSessionFactory sessionFactory) {
		this.sessionFactory = sessionFactory;
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.db.DownloadLogDAO#saveDownloadRecord(DownloadRecord)
	 */
	@Override
	public void saveDownloadRecord(DownloadRecord record) {
		sessionFactory.getCurrentSession().save(record);
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.db.DownloadLogDAO#getDownloads(Integer, Date, Date)
	 */
	@Override
	public List<DownloadLog.Entry> getDownloads(Integer userId, Date from, Date before) {
		StringBuilder where = new StringBuilder();
		Map<String, Object> params = new LinkedHashMap<String, Object>();
		if (userId != null) {
			where.append(" and d.userId = :user");
			params.put("user", userId);
		}
		if (from != null) {
			where.append(" and d.dateCreated >= :from");
			params.put("from", from);
		}
		if (before != null) {
			where.append(" and d.dateCreated < :to");
			params.put("to", before);
		}
		Query query = sessionFactory.getCurrentSession()
		        .createQuery("select d.dateCreated, u.uuid, f.uuid, f.name, d.detail from DownloadRecord d"
		                + " join User u on u.userId = d.userId left join Form f on f.formId = d.formId where 1 = 1" + where
		                + " order by d.dateCreated desc, d.downloadId desc");
		for (Map.Entry<String, Object> param : params.entrySet()) {
			query.setParameter(param.getKey(), param.getValue());
		}
		List<DownloadLog.Entry> entries = new ArrayList<DownloadLog.Entry>();
		for (Object row : query.list()) {
			Object[] columns = (Object[]) row;
			DownloadLog.Entry entry = new DownloadLog.Entry();
			entry.date = (Date) columns[0];
			entry.userUuid = (String) columns[1];
			entry.formUuid = (String) columns[2];
			entry.formName = Names.shown("Form", entry.formUuid, (String) columns[3]);
			entry.detail = (String) columns[4];
			entries.add(entry);
		}
		return entries;
	}
}
