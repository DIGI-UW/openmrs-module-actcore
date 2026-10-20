/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.download;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.hibernate.type.IntegerType;
import org.hibernate.type.StringType;
import org.hibernate.type.TimestampType;
import org.openmrs.api.context.Context;
import org.openmrs.api.db.hibernate.DbSessionFactory;
import org.openmrs.module.actcore.Transactions;

/**
 * Each download, as ACT 2.0's audit log kept it. The audit log module records reads and changes but
 * offers no way for another module to record an event of its own, so downloads are kept here.
 */
public class DownloadLog {
	
	public static final String TABLE = "actcore_download_log";
	
	/** The audit log module's, as a download is a read of patients' data. */
	public static final String VIEW_PRIVILEGE = "View Read Audit Logs";
	
	/** One download, with the form it was of, null once the form is purged. */
	public static class Entry {
		
		public Date date;
		
		public String userUuid;
		
		public String username;
		
		public String formUuid;
		
		public String formName;
		
		public String detail;
	}
	
	/** Records it for the signed-in user in a transaction of its own, so a failure fails the caller. */
	public void record(Integer formId, String detail) {
		final int userId = Context.getAuthenticatedUser().getUserId();
		Transactions.inNewTransaction(() -> write(userId, formId, detail));
	}
	
	/** Writes it in the current transaction. */
	public void write(int userId, Integer formId, String detail) {
		sessionFactory().getCurrentSession()
		        .createSQLQuery("insert into " + TABLE
		                + " (user_id, form_id, detail, date_created) values (:user, :form, :detail, :date)")
		        .setParameter("user", userId).setParameter("form", formId, IntegerType.INSTANCE)
		        .setParameter("detail", detail).setParameter("date", new Date()).executeUpdate();
	}
	
	/** The latest downloads, newest first. */
	public List<Entry> latest(int limit) {
		List<Entry> entries = new ArrayList<Entry>();
		for (Object row : sessionFactory().getCurrentSession()
		        .createSQLQuery("select d.date_created, u.uuid user_uuid, u.username, f.uuid form_uuid, f.name form_name,"
		                + " d.detail from " + TABLE
		                + " d join users u on u.user_id = d.user_id left join form f on f.form_id = d.form_id"
		                + " order by d.date_created desc, d.download_id desc")
		        .addScalar("date_created", TimestampType.INSTANCE).addScalar("user_uuid", StringType.INSTANCE)
		        .addScalar("username", StringType.INSTANCE).addScalar("form_uuid", StringType.INSTANCE)
		        .addScalar("form_name", StringType.INSTANCE).addScalar("detail", StringType.INSTANCE).setMaxResults(limit)
		        .list()) {
			Object[] columns = (Object[]) row;
			Entry entry = new Entry();
			entry.date = (Date) columns[0];
			entry.userUuid = (String) columns[1];
			entry.username = (String) columns[2];
			entry.formUuid = (String) columns[3];
			entry.formName = (String) columns[4];
			entry.detail = (String) columns[5];
			entries.add(entry);
		}
		return entries;
	}
	
	private static DbSessionFactory sessionFactory() {
		return Context.getRegisteredComponent("dbSessionFactory", DbSessionFactory.class);
	}
}
