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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.hibernate.FlushMode;
import org.hibernate.Query;
import org.openmrs.api.db.hibernate.DbSessionFactory;
import org.openmrs.module.actcore.api.db.AuditLogDAO;

/** Hibernate implementation of {@link org.openmrs.module.actcore.api.db.AuditLogDAO}. */
public class HibernateAuditLogDAO implements AuditLogDAO {
	
	private DbSessionFactory sessionFactory;
	
	public void setSessionFactory(DbSessionFactory sessionFactory) {
		this.sessionFactory = sessionFactory;
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.db.AuditLogDAO#getUserIdsByUsername(String)
	 */
	@Override
	public List<Map<String, Object>> getUserIdsByUsername(String username) {
		return select(
		    "select user_id from users where username = ?"
		            + " or ((username is null or username = '') and system_id = ?) order by username desc",
		    username, username);
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.db.AuditLogDAO#getUserById(Integer)
	 */
	@Override
	public List<Map<String, Object>> getUserById(Integer userId) {
		return select("select u.user_id, u.uuid, u.username, u.system_id, u.person_id from users u where u.user_id = ?",
		    userId);
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.db.AuditLogDAO#getUserByUuid(String)
	 */
	@Override
	public List<Map<String, Object>> getUserByUuid(String uuid) {
		return select("select u.user_id, u.uuid, u.username, u.system_id, u.person_id from users u where u.uuid = ?", uuid);
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.db.AuditLogDAO#getUserIdByUuid(String)
	 */
	@Override
	public List<Map<String, Object>> getUserIdByUuid(String uuid) {
		return select("select user_id from users where uuid = ?", uuid);
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.db.AuditLogDAO#getUserIdsByPerson(Integer)
	 */
	@Override
	public List<Map<String, Object>> getUserIdsByPerson(Integer personId) {
		return select("select user_id from users where person_id = ?", personId);
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.db.AuditLogDAO#getUserRoles(Integer)
	 */
	@Override
	public List<Map<String, Object>> getUserRoles(Integer userId) {
		return select("select role from user_role where user_id = ?", userId);
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.db.AuditLogDAO#getInheritedRoles(Integer)
	 */
	@Override
	public List<Map<String, Object>> getInheritedRoles(Integer userId) {
		return select("select rr.parent_role as role from role_role rr"
		        + " join user_role ur on ur.role = rr.child_role where ur.user_id = ?",
		    userId);
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.db.AuditLogDAO#getPersonUuid(Integer)
	 */
	@Override
	public List<Map<String, Object>> getPersonUuid(Integer personId) {
		return select("select uuid from person where person_id = ?", personId);
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.db.AuditLogDAO#getPersonId(String)
	 */
	@Override
	public List<Map<String, Object>> getPersonId(String uuid) {
		return select("select person_id from person where uuid = ?", uuid);
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.db.AuditLogDAO#getUnvoidedPersonNames(Integer)
	 */
	@Override
	public List<Map<String, Object>> getUnvoidedPersonNames(Integer personId) {
		return select("select given_name, middle_name, family_name from person_name where person_id = ? and voided = false"
		        + " order by preferred desc, person_name_id",
		    personId);
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.db.AuditLogDAO#getPersonNames(Integer)
	 */
	@Override
	public List<Map<String, Object>> getPersonNames(Integer personId) {
		return select("select given_name, middle_name, family_name from person_name where person_id = ?"
		        + " order by voided, preferred desc, person_name_id",
		    personId);
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.db.AuditLogDAO#getPatientIdentifiers(Integer)
	 */
	@Override
	public List<Map<String, Object>> getPatientIdentifiers(Integer patientId) {
		return select("select i.identifier, t.uuid type_uuid from patient_identifier i"
		        + " join patient_identifier_type t on t.patient_identifier_type_id = i.identifier_type"
		        + " where i.patient_id = ? order by i.voided, i.preferred desc, i.patient_identifier_id",
		    patientId);
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.db.AuditLogDAO#getLocationAttributes(Integer)
	 */
	@Override
	public List<Map<String, Object>> getLocationAttributes(Integer personId) {
		return select("select a.value, t.uuid as type_uuid from person_attribute a"
		        + " join person_attribute_type t on t.person_attribute_type_id = a.person_attribute_type_id"
		        + " where a.person_id = ? and a.voided = false and t.format = 'org.openmrs.Location'",
		    personId);
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.db.AuditLogDAO#getUserProperty(String, String)
	 */
	@Override
	public List<Map<String, Object>> getUserProperty(String userUuid, String property) {
		return select("select p.property_value from user_property p join users u on u.user_id = p.user_id"
		        + " where u.uuid = ? and p.property = ?",
		    userUuid, property);
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.db.AuditLogDAO#getFormId(String)
	 */
	@Override
	public List<Map<String, Object>> getFormId(String uuid) {
		return select("select form_id from form where uuid = ?", uuid);
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.db.AuditLogDAO#getRow(String, String, Integer)
	 */
	@Override
	public List<Map<String, Object>> getRow(String table, String idColumn, Integer id) {
		return select("select * from " + table + " where " + idColumn + " = ?", id);
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.db.AuditLogDAO#findPatientUuids(String, int)
	 */
	@Override
	public List<Map<String, Object>> findPatientUuids(String query, int limit) {
		String part = "%" + query.toLowerCase().replaceAll("[!%_]", "!$0") + "%";
		return select(
		    "select distinct p.uuid from person p join patient t on t.patient_id = p.person_id and t.voided = false"
		            + " left join patient_identifier i on i.patient_id = p.person_id and i.voided = false"
		            + " left join person_name n on n.person_id = p.person_id and n.voided = false"
		            + " where p.voided = false and (i.identifier = ? or lower(n.given_name) like ? escape '!'"
		            + " or lower(n.middle_name) like ? escape '!' or lower(n.family_name) like ? escape '!') limit " + limit,
		    query, part, part, part);
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.db.AuditLogDAO#getRevisions(Map, Integer, Integer, LocalDate,
	 *      LocalDate, Integer, Integer)
	 */
	@Override
	public List<Map<String, Object>> getRevisions(Map<String, String> patientColumns, Integer userId, Integer excludedUserId,
	        LocalDate from, LocalDate to, Integer patientId, Integer formId) {
		StringBuilder sql = new StringBuilder(
		        "select r.id, r.changedOn as rev_time, r.changedBy as rev_user from revision_entity r where 1 = 1");
		List<Object> params = new ArrayList<Object>();
		if (userId != null) {
			sql.append(" and r.changedBy = ?");
			params.add(userId);
		}
		if (excludedUserId != null) {
			sql.append(" and (r.changedBy is null or r.changedBy <> ?)");
			params.add(excludedUserId);
		}
		if (from != null) {
			sql.append(" and r.changedOn >= ?");
			params.add(Timestamp.valueOf(from.atStartOfDay()));
		}
		if (to != null) {
			sql.append(" and r.changedOn < ?");
			params.add(Timestamp.valueOf(to.plusDays(1).atStartOfDay()));
		}
		sql.append(" and (");
		boolean first = true;
		for (Map.Entry<String, String> table : patientColumns.entrySet()) {
			sql.append(first ? "" : " or ").append("exists (select 1 from ").append(table.getKey())
			        .append(" a where a.REV = r.id");
			first = false;
			if (patientId != null) {
				sql.append(table.getKey().equals("patient_state_AUD")
				        ? " and a.patient_program_id in (select patient_program_id from patient_program where patient_id = ?)"
				        : " and a." + table.getValue() + " = ?");
				params.add(patientId);
			}
			if (formId != null) {
				sql.append(table.getKey().equals("obs_AUD")
				        ? " and a.encounter_id in (select encounter_id from encounter where form_id = ?)"
				        : " and a.form_id = ?");
				params.add(formId);
			}
			sql.append(")");
		}
		sql.append(") order by r.changedOn desc, r.id desc");
		return select(sql.toString(), params.toArray());
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.db.AuditLogDAO#getRevisionRows(String, List)
	 */
	@Override
	public List<Map<String, Object>> getRevisionRows(String auditTable, List<Integer> revisionIds) {
		String select;
		String groupBy;
		if (auditTable.equals("obs_AUD")) {
			select = "select a.REV, a.REVTYPE, a.encounter_id, a.person_id, count(*) as n, r.changedOn as rev_time,"
			        + " r.changedBy as rev_user from obs_AUD a join revision_entity r on r.id = a.REV";
			groupBy = " group by a.REV, a.REVTYPE, a.encounter_id, a.person_id, r.changedOn, r.changedBy";
		} else {
			select = "select a.*, r.changedOn as rev_time, r.changedBy as rev_user from " + auditTable
			        + " a join revision_entity r on r.id = a.REV";
			groupBy = "";
		}
		return select(select + " where a.REV in (" + placeholders(revisionIds) + ")" + groupBy, revisionIds.toArray());
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.db.AuditLogDAO#getPreviousAuditRows(String, String, Object,
	 *      int)
	 */
	@Override
	public List<Map<String, Object>> getPreviousAuditRows(String auditTable, String idColumn, Object id, int revisionId) {
		return select("select * from " + auditTable + " where " + idColumn + " = ? and REV < ? order by REV desc", id,
		    revisionId);
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.db.AuditLogDAO#getAuditedObs(Integer, Integer)
	 */
	@Override
	public List<Map<String, Object>> getAuditedObs(Integer revisionId, Integer encounterId) {
		return select("select * from obs_AUD where REV = ? and encounter_id = ? order by obs_id", revisionId, encounterId);
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.db.AuditLogDAO#getAuditedPersonNames(Integer, int)
	 */
	@Override
	public List<Map<String, Object>> getAuditedPersonNames(Integer personId, int revisionId) {
		return select("select given_name, middle_name, family_name from person_name_AUD where person_id = ? and REV <= ?"
		        + " order by REV desc",
		    personId, revisionId);
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.db.AuditLogDAO#getAuditedIdentifiers(Integer, int)
	 */
	@Override
	public List<Map<String, Object>> getAuditedIdentifiers(Integer patientId, int revisionId) {
		return select(
		    "select identifier from patient_identifier_AUD where patient_id = ? and REV <= ? order by preferred desc, REV desc",
		    patientId, revisionId);
	}
	
	/**
	 * Reads the dismissals through their mapped entity, joined to the patientflags module's mapped flag
	 * for its name, and leaves the session unflushed, as RaisedFlagDAO's reads of the dismissals do.
	 *
	 * @see org.openmrs.module.actcore.api.db.AuditLogDAO#getFlagDismissals(Integer, LocalDate,
	 *      LocalDate, Integer)
	 */
	@Override
	public List<Map<String, Object>> getFlagDismissals(Integer userId, LocalDate from, LocalDate to, Integer patientId) {
		StringBuilder hql = new StringBuilder("select new map(f.patientId as patient_id, f.dismissedBy as dismissed_by,"
		        + " f.dateDismissed as date_dismissed, g.name as name) from FlagDismissal f"
		        + " left join org.openmrs.module.patientflags.Flag g on g.flagId = f.flagId where 1 = 1");
		if (userId != null) {
			hql.append(" and f.dismissedBy = :user");
		}
		if (from != null) {
			hql.append(" and f.dateDismissed >= :from");
		}
		if (to != null) {
			hql.append(" and f.dateDismissed < :to");
		}
		if (patientId != null) {
			hql.append(" and f.patientId = :patient");
		}
		hql.append(" order by f.dateDismissed desc, f.dismissalId desc");
		Query<?> query = sessionFactory.getCurrentSession().createQuery(hql.toString())
		        .setHibernateFlushMode(FlushMode.MANUAL);
		if (userId != null) {
			query.setParameter("user", userId);
		}
		if (from != null) {
			query.setParameter("from", Timestamp.valueOf(from.atStartOfDay()));
		}
		if (to != null) {
			query.setParameter("to", Timestamp.valueOf(to.plusDays(1).atStartOfDay()));
		}
		if (patientId != null) {
			query.setParameter("patient", patientId);
		}
		List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
		for (Object found : query.list()) {
			Map<String, Object> row = new TreeMap<String, Object>(String.CASE_INSENSITIVE_ORDER);
			for (Map.Entry<?, ?> column : ((Map<?, ?>) found).entrySet()) {
				row.put((String) column.getKey(), column.getValue());
			}
			rows.add(row);
		}
		return rows;
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.db.AuditLogDAO#getReadIdRange(LocalDate, LocalDate)
	 */
	@Override
	public List<Map<String, Object>> getReadIdRange(LocalDate from, LocalDate to) {
		List<Object> params = new ArrayList<Object>();
		return select("select min(l.id) as lowest, max(l.id) as highest from read_audit_log l where 1 = 1"
		        + readDates(from, to, params),
		    params.toArray());
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.db.AuditLogDAO#getReads(Collection, String, LocalDate,
	 *      LocalDate, List, long, long)
	 */
	@Override
	public List<Map<String, Object>> getReads(Collection<String> entityNames, String username, LocalDate from, LocalDate to,
	        List<String> patientUuids, long after, long upTo) {
		StringBuilder where = new StringBuilder(" where (l.entity_name in (" + placeholders(entityNames)
		        + ") or exists (select 1 from read_audit_entity_metadata p"
		        + " where p.read_audit_id = l.id and p.patient_uuid is not null))");
		List<Object> params = new ArrayList<Object>(entityNames);
		if (username != null) {
			where.append(" and l.username = ?");
			params.add(username);
		}
		where.append(readDates(from, to, params));
		if (patientUuids != null) {
			where.append(" and l.id in (select read_audit_id from read_audit_entity_metadata where patient_uuid in (")
			        .append(placeholders(patientUuids)).append("))");
			params.addAll(patientUuids);
		}
		params.add(after);
		params.add(upTo);
		return select("select l.id, l.username, l.user_uuid, l.event_time, l.entity_name, l.session_id, l.ip_address,"
		        + " l.user_agent, l.read_success, count(m.id) as targets, count(distinct m.patient_uuid) as patients,"
		        + " min(m.patient_uuid) as patient_uuid, min(m.patient_name) as patient_name from read_audit_log l"
		        + " left join read_audit_entity_metadata m on m.read_audit_id = l.id" + where + " and l.id > ? and l.id <= ?"
		        + " group by l.id, l.username, l.user_uuid, l.event_time, l.entity_name, l.session_id, l.ip_address,"
		        + " l.user_agent, l.read_success",
		    params.toArray());
	}
	
	/** Builds the read audit's conditions on the days and adds their parameters. */
	private static String readDates(LocalDate from, LocalDate to, List<Object> params) {
		StringBuilder dates = new StringBuilder();
		if (from != null) {
			dates.append(" and l.event_time >= ?");
			params.add(Timestamp.valueOf(from.atStartOfDay()));
		}
		if (to != null) {
			dates.append(" and l.event_time < ?");
			params.add(Timestamp.valueOf(to.plusDays(1).atStartOfDay()));
		}
		return dates.toString();
	}
	
	/** Builds the placeholders of an in-list for the values, such as "?, ?, ?" for three of them. */
	private static String placeholders(Collection<?> values) {
		StringBuilder list = new StringBuilder();
		for (int i = 0; i < values.size(); i++) {
			list.append(i == 0 ? "?" : ", ?");
		}
		return list.toString();
	}
	
	/**
	 * Runs a query over JDBC on the current session's connection and returns its rows, each a map that
	 * looks columns up by label ignoring case, with MariaDB's date and datetime values given as the
	 * java.sql types H2 gives.
	 */
	private List<Map<String, Object>> select(String sql, Object... params) {
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
