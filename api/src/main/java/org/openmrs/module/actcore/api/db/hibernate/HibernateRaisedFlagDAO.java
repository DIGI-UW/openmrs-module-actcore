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
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hibernate.FlushMode;
import org.openmrs.api.db.hibernate.DbSession;
import org.openmrs.api.db.hibernate.DbSessionFactory;
import org.openmrs.module.actcore.api.db.RaisedFlagDAO;
import org.openmrs.module.actcore.model.FlagDismissal;
import org.openmrs.module.actcore.model.RaisedFlag;
import org.openmrs.module.patientflags.PatientFlag;
import org.openmrs.util.DatabaseUtil;

/** Hibernate implementation of {@link org.openmrs.module.actcore.api.db.RaisedFlagDAO}. */
public class HibernateRaisedFlagDAO implements RaisedFlagDAO {
	
	private DbSessionFactory sessionFactory;
	
	public void setSessionFactory(DbSessionFactory sessionFactory) {
		this.sessionFactory = sessionFactory;
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.db.RaisedFlagDAO#isRaisable(Integer, Integer)
	 */
	@Override
	public boolean isRaisable(Integer flagId, Integer patientId) {
		long absent = (Long) session()
		        .createQuery("select count(*) from Patient p where p.patientId = :patient and not exists"
		                + " (select 1 from RaisedFlag r where r.key.patientId = :patient and r.key.flagId = :flag)")
		        .setParameter("patient", patientId).setParameter("flag", flagId).setHibernateFlushMode(FlushMode.MANUAL)
		        .uniqueResult();
		return absent > 0;
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.db.RaisedFlagDAO#saveRaisedFlag(RaisedFlag)
	 */
	@Override
	public void saveRaisedFlag(RaisedFlag raised) {
		session().save(raised);
		session().flush();
		session().evict(raised);
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.db.RaisedFlagDAO#deleteRaisedFlags(Integer, Integer)
	 */
	@Override
	public void deleteRaisedFlags(Integer flagId, Integer patientId) {
		if (patientId == null) {
			session().createQuery("delete from RaisedFlag where key.flagId = :flag").setParameter("flag", flagId)
			        .executeUpdate();
		} else {
			session().createQuery("delete from RaisedFlag where key.flagId = :flag and key.patientId = :patient")
			        .setParameter("flag", flagId).setParameter("patient", patientId).executeUpdate();
		}
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.db.RaisedFlagDAO#dismissRaisedFlag(Integer, Integer, Integer,
	 *      Date)
	 */
	@Override
	public boolean dismissRaisedFlag(Integer flagId, Integer patientId, Integer userId, Date date) {
		return session()
		        .createQuery("update RaisedFlag set dismissedBy = :user, dateDismissed = :now"
		                + " where key.flagId = :flag and key.patientId = :patient and dateDismissed is null")
		        .setParameter("user", userId).setParameter("now", date).setParameter("flag", flagId)
		        .setParameter("patient", patientId).executeUpdate() > 0;
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.db.RaisedFlagDAO#saveFlagDismissal(FlagDismissal)
	 */
	@Override
	public void saveFlagDismissal(FlagDismissal dismissal) {
		session().save(dismissal);
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.db.RaisedFlagDAO#getRaisedSinceByPatient(Integer)
	 */
	@Override
	public Map<Integer, Date> getRaisedSinceByPatient(Integer flagId) {
		return dates("select key.patientId, raisedSince from RaisedFlag where key.flagId = :id", flagId);
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.db.RaisedFlagDAO#getRaisedSinceByFlag(Integer)
	 */
	@Override
	public Map<Integer, Date> getRaisedSinceByFlag(Integer patientId) {
		return dates("select key.flagId, raisedSince from RaisedFlag where key.patientId = :id", patientId);
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.db.RaisedFlagDAO#getDismissedPatients(Integer)
	 */
	@Override
	public Set<Integer> getDismissedPatients(Integer flagId) {
		return ids("select key.patientId from RaisedFlag where key.flagId = :id and dateDismissed is not null", flagId);
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.db.RaisedFlagDAO#getDismissedFlags(Integer)
	 */
	@Override
	public Set<Integer> getDismissedFlags(Integer patientId) {
		return ids("select key.flagId from RaisedFlag where key.patientId = :id and dateDismissed is not null", patientId);
	}
	
	/**
	 * Reads by SQL on the current session's connection, as AdministrationService.executeSQL does,
	 * without the SQL Level Access privilege that service asks for.
	 *
	 * @see org.openmrs.module.actcore.api.db.RaisedFlagDAO#getPatientFlagRows(Integer, Integer)
	 */
	@Override
	public List<List<Object>> getPatientFlagRows(Integer flagId, Integer patientId) {
		return sql("select patient_id, message from patientflags_patient_flag where flag_id = " + flagId
		        + " and voided = false" + (patientId == null ? "" : " and patient_id = " + patientId));
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.db.RaisedFlagDAO#deletePatientFlagRows(Integer, Integer)
	 */
	@Override
	public void deletePatientFlagRows(Integer flagId, Integer patientId) {
		session()
		        .createSQLQuery("delete from patientflags_patient_flag"
		                + " where flag_id = :flag and patient_id = :patient and voided = false")
		        .addSynchronizedEntityClass(PatientFlag.class).setParameter("flag", flagId).setParameter("patient", patientId)
		        .executeUpdate();
	}
	
	/**
	 * Reads by SQL on the current session's connection, with the uuid bound as a parameter.
	 *
	 * @see org.openmrs.module.actcore.api.db.RaisedFlagDAO#hasCohort(String)
	 */
	@Override
	public boolean hasCohort(String uuid) {
		return session().doReturningWork(connection -> {
			try (PreparedStatement statement = connection.prepareStatement("select count(*) from cohort where uuid = ?")) {
				statement.setString(1, uuid);
				try (ResultSet result = statement.executeQuery()) {
					result.next();
					return result.getInt(1) > 0;
				}
			}
		});
	}
	
	private List<List<Object>> sql(String sql) {
		return session().doReturningWork(connection -> DatabaseUtil.executeSQL(connection, sql, true));
	}
	
	private Set<Integer> ids(String hql, Integer id) {
		Set<Integer> ids = new HashSet<Integer>();
		for (Object value : list(hql, id)) {
			ids.add((Integer) value);
		}
		return ids;
	}
	
	private Map<Integer, Date> dates(String hql, Integer id) {
		Map<Integer, Date> dates = new HashMap<Integer, Date>();
		for (Object row : list(hql, id)) {
			dates.put((Integer) ((Object[]) row)[0], (Date) ((Object[]) row)[1]);
		}
		return dates;
	}
	
	private List<?> list(String hql, Integer id) {
		return session().createQuery(hql).setParameter("id", id).setHibernateFlushMode(FlushMode.MANUAL).list();
	}
	
	private DbSession session() {
		return sessionFactory.getCurrentSession();
	}
}
