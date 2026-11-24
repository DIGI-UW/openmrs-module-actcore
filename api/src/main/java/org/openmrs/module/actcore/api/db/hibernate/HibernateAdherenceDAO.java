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

import org.apache.commons.lang3.StringUtils;
import org.openmrs.api.db.hibernate.DbSessionFactory;
import org.openmrs.module.actcore.ActCoreConstants;
import org.openmrs.module.actcore.api.db.AdherenceDAO;
import org.openmrs.util.DatabaseUtil;

/** Hibernate implementation of {@link org.openmrs.module.actcore.api.db.AdherenceDAO}. */
public class HibernateAdherenceDAO implements AdherenceDAO {
	
	private DbSessionFactory sessionFactory;
	
	public void setSessionFactory(DbSessionFactory sessionFactory) {
		this.sessionFactory = sessionFactory;
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.db.AdherenceDAO#getPrescriptions(String, String, String,
	 *      String, Collection)
	 */
	@Override
	public List<List<Object>> getPrescriptions(String prescription, String consultationDate, String dateStarted,
	        String dateStopped, Collection<Integer> patientIds) {
		return sql("select g.person_id, coalesce(cd.value_datetime, e.encounter_datetime),"
		        + " e.encounter_id, rc.uuid, rc.concept_id, s.value_datetime, x.value_datetime"
		        + " from obs g join encounter e on e.encounter_id = g.encounter_id and e.voided = false"
		        + " left join obs cd on cd.encounter_id = g.encounter_id and cd.voided = false and cd.concept_id = "
		        + concept(consultationDate)
		        + " left join obs r on r.obs_group_id = g.obs_id and r.voided = false and r.concept_id = g.concept_id"
		        + " left join concept rc on rc.concept_id = r.value_coded"
		        + " left join obs s on s.obs_group_id = g.obs_id and s.voided = false and s.concept_id = "
		        + concept(dateStarted) + " left join obs x on x.obs_group_id = g.obs_id and x.voided = false"
		        + " and x.concept_id = " + concept(dateStopped)
		        + " where g.voided = false and g.obs_group_id is null and g.concept_id = " + concept(prescription)
		        + only("g.person_id", patientIds)
		        + " order by g.person_id, coalesce(cd.value_datetime, e.encounter_datetime), e.encounter_id, g.obs_id,"
		        + " r.obs_id",
		    true);
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.db.AdherenceDAO#getInjections(String, Collection)
	 */
	@Override
	public List<List<Object>> getInjections(String injectionDate, Collection<Integer> patientIds) {
		return sql("select o.person_id, o.value_datetime from obs o"
		        + " join encounter e on e.encounter_id = o.encounter_id and e.voided = false"
		        + " where o.voided = false and o.value_datetime is not null and o.concept_id = " + concept(injectionDate)
		        + only("o.person_id", patientIds),
		    true);
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.db.AdherenceDAO#getEstimates(String, String, String,
	 *      Collection)
	 */
	@Override
	public List<List<Object>> getEstimates(String estimateDate, String estimate, String duration,
	        Collection<Integer> patientIds) {
		boolean dated = estimateDate != null;
		String dayColumn = dated ? "coalesce(dt.value_datetime, e.encounter_datetime)" : "e.encounter_datetime";
		return sql("select e.patient_id, " + dayColumn + ", est.value_numeric, dc.uuid from encounter e"
		        + (dated ? " left join obs dt on dt.encounter_id = e.encounter_id and dt.voided = false and dt.concept_id = "
		                + concept(estimateDate) : "")
		        + " left join obs est on est.encounter_id = e.encounter_id and est.voided = false and est.concept_id = "
		        + concept(estimate)
		        + " left join obs d on d.encounter_id = e.encounter_id and d.voided = false and d.concept_id = "
		        + concept(duration) + " left join concept dc on dc.concept_id = d.value_coded"
		        + " where e.voided = false and (est.obs_id is not null or d.obs_id is not null)"
		        + only("e.patient_id", patientIds) + " order by e.patient_id, " + dayColumn
		        + ", e.encounter_id, est.obs_id, d.obs_id",
		    true);
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.db.AdherenceDAO#getConsultations(String, String, Collection)
	 */
	@Override
	public List<List<Object>> getConsultations(String consultationDate, String dataEntry, Collection<Integer> patientIds) {
		return sql("select o.person_id, o.value_datetime, c.uuid from obs o"
		        + " join encounter e on e.encounter_id = o.encounter_id and e.voided = false"
		        + " left join obs d on d.encounter_id = o.encounter_id and d.voided = false and d.concept_id = "
		        + concept(dataEntry) + " left join concept c on c.concept_id = d.value_coded"
		        + " where o.voided = false and o.value_datetime is not null and o.concept_id = " + concept(consultationDate)
		        + only("o.person_id", patientIds) + " order by o.person_id, o.value_datetime, e.encounter_id, o.obs_id,"
		        + " d.obs_id",
		    true);
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.db.AdherenceDAO#replaceRows(Integer, List, List, int)
	 */
	@Override
	public void replaceRows(Integer patientId, List<Object[]> rows, List<Object[]> timings, int rowsPerInsert) {
		String where = patientId == null ? "" : " where patient_id = " + patientId;
		sql("delete from " + ActCoreConstants.PROPHYLAXIS_ADHERENCE_TABLE + where, false);
		insert(ActCoreConstants.PROPHYLAXIS_ADHERENCE_TABLE
		        + " (patient_id, regimen_concept_id, injection_interval_days, adherence,"
		        + " last_given, next_due, date_computed)",
		    rows, rowsPerInsert);
		sql("delete from " + ActCoreConstants.INJECTION_TIMING_TABLE + where, false);
		insert(ActCoreConstants.INJECTION_TIMING_TABLE + " (patient_id, injection_date, on_time)", timings, rowsPerInsert);
	}
	
	private void insert(String into, List<Object[]> rows, int rowsPerInsert) {
		for (int i = 0; i < rows.size(); i += rowsPerInsert) {
			List<String> values = new ArrayList<String>();
			for (Object[] row : rows.subList(i, Math.min(rows.size(), i + rowsPerInsert))) {
				List<String> columns = new ArrayList<String>();
				for (Object value : row) {
					columns.add(sqlValue(value));
				}
				values.add("(" + StringUtils.join(columns, ", ") + ")");
			}
			sql("insert into " + into + " values " + StringUtils.join(values, ", "), false);
		}
	}
	
	/**
	 * Runs the SQL on the current session's connection, as AdministrationService.executeSQL does,
	 * without the SQL Level Access privilege that service asks for.
	 */
	private List<List<Object>> sql(String sql, boolean selectOnly) {
		return sessionFactory.getCurrentSession()
		        .doReturningWork(connection -> DatabaseUtil.executeSQL(connection, sql, selectOnly));
	}
	
	private static String concept(String uuid) {
		return "(select concept_id from concept where uuid = '" + uuid + "')";
	}
	
	private static String only(String column, Collection<Integer> patientIds) {
		return patientIds == null ? "" : " and " + column + " in (" + StringUtils.join(patientIds, ", ") + ")";
	}
	
	/**
	 * Writes a value as SQL: a number or boolean as it is, null as null, and anything else, such as a
	 * date or a timestamp, as its text in quotes.
	 */
	static String sqlValue(Object value) {
		if (value == null) {
			return "null";
		}
		if (value instanceof Number) {
			return value.toString();
		}
		if (value instanceof Boolean) {
			return (Boolean) value ? "true" : "false";
		}
		return "'" + value + "'";
	}
}
