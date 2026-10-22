/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.task;

import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openmrs.Patient;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.context.Context;
import org.openmrs.module.patientflags.Flag;
import org.openmrs.module.patientflags.PatientFlagsConstants;
import org.openmrs.module.patientflags.Tag;
import org.openmrs.util.PrivilegeConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Each raised flag's date and dismissal, kept here as patientflags recreates its own rows on every
 * save, so neither its date_created nor a voided row outlives the next save.
 */
public class RaisedFlags {
	
	public static final String TABLE = "actcore_raised_flag";
	
	/** Each dismissal, never deleted, as the refresh deletes a TABLE row once its flag lowers. */
	public static final String DISMISSAL_TABLE = "actcore_flag_dismissal";
	
	/**
	 * The distribution creates it; a user without it, other than a superuser, cannot dismiss a flag.
	 */
	public static final String DISMISS_PRIVILEGE = "Task: act.dismissFlags";
	
	public static final String DISMISS_TAG_PROPERTY = "actcore.dismissFlagTag";
	
	private static final Logger log = LoggerFactory.getLogger(RaisedFlags.class);
	
	private static final String[] DISMISS_PRIVILEGES = { PatientFlagsConstants.PRIV_TEST_FLAGS,
	        PrivilegeConstants.SQL_LEVEL_ACCESS, PrivilegeConstants.GET_PATIENTS, PrivilegeConstants.GET_GLOBAL_PROPERTIES,
	        "View Cohorts In Cohort Module", "Edit Cohorts in Cohort Module" };
	
	public enum Dismissal {
		DISMISSED,
		NOT_RAISED,
		NOT_DISMISSIBLE,
		REFRESH_RUNNING
	}
	
	/** Records the flag as raised from now, unless it already is. */
	static void raise(int flagId, int patientId) {
		execute("insert into " + TABLE + " (patient_id, flag_id, raised_since) select p.patient_id, " + flagId + ", " + now()
		        + " from patient p where p.patient_id = " + patientId + " and not exists (select 1 from " + TABLE
		        + " r where r.patient_id = " + patientId + " and r.flag_id = " + flagId + ")");
	}
	
	static void lower(int flagId, int patientId) {
		execute("delete from " + TABLE + " where flag_id = " + flagId + " and patient_id = " + patientId);
	}
	
	static void lowerAll(int flagId) {
		execute("delete from " + TABLE + " where flag_id = " + flagId);
	}
	
	static Set<Integer> raisedPatients(int flagId) {
		return ids("select patient_id from " + TABLE + " where flag_id = " + flagId);
	}
	
	static Set<Integer> dismissedPatients(int flagId) {
		return ids("select patient_id from " + TABLE + " where flag_id = " + flagId + " and date_dismissed is not null");
	}
	
	static boolean isDismissed(int flagId, int patientId) {
		return !ids("select patient_id from " + TABLE + " where flag_id = " + flagId + " and patient_id = " + patientId
		        + " and date_dismissed is not null").isEmpty();
	}
	
	/** The flags dismissed for the patient, by flag id. */
	public static Set<Integer> dismissedFlags(int patientId) {
		return ids("select flag_id from " + TABLE + " where patient_id = " + patientId + " and date_dismissed is not null");
	}
	
	/** Since when each of the flag's patients has had it raised, by patient id. */
	static Map<Integer, Date> raisedSinceByPatient(int flagId) {
		return dates("select patient_id, raised_since from " + TABLE + " where flag_id = " + flagId);
	}
	
	/** Since when the patient has had each of their raised flags, by flag id. */
	public static Map<Integer, Date> raisedSinceByFlag(int patientId) {
		return dates("select flag_id, raised_since from " + TABLE + " where patient_id = " + patientId);
	}
	
	/**
	 * Whether the signed-in user may dismiss this flag: it carries actcore.dismissFlagTag, or that is
	 * empty.
	 */
	public static boolean mayDismiss(Flag flag) {
		return Context.hasPrivilege(DISMISS_PRIVILEGE) && Context.hasPrivilege(PatientFlagsConstants.PRIV_VIEW_PATIENT_FLAGS)
		        && carriesDismissTag(flag);
	}
	
	/**
	 * Hides the flag for the patient wherever ACT reads flags until ACT Core finds that it no longer
	 * matches them. Only a flag that matches now can be dismissed, and not during the full refresh.
	 */
	public static Dismissal dismiss(Patient patient, Flag flag) {
		for (String privilege : new String[] { DISMISS_PRIVILEGE, PatientFlagsConstants.PRIV_VIEW_PATIENT_FLAGS }) {
			if (!Context.hasPrivilege(privilege)) {
				throw new APIAuthenticationException("Privilege required: " + privilege);
			}
		}
		if (!carriesDismissTag(flag)) {
			return Dismissal.NOT_DISMISSIBLE;
		}
		// Not waited on: the full refresh holds it for minutes, and would undo the dismissal.
		if (!PatientFlagRefreshTask.ROWS.readLock().tryLock()) {
			return Dismissal.REFRESH_RUNNING;
		}
		for (String privilege : DISMISS_PRIVILEGES) {
			Context.addProxyPrivilege(privilege);
		}
		try {
			if (Boolean.TRUE.equals(patient.getVoided()) || !Boolean.TRUE.equals(flag.getEnabled())
			        || Boolean.TRUE.equals(flag.getRetired())
			        || !Boolean.TRUE.equals(flag.eval(patient, new HashMap<Object, Object>()))) {
				return Dismissal.NOT_RAISED;
			}
			raise(flag.getFlagId(), patient.getPatientId());
			Integer userId = Context.getAuthenticatedUser().getUserId();
			String now = now();
			String undismissed = " where flag_id = " + flag.getFlagId() + " and patient_id = " + patient.getPatientId()
			        + " and date_dismissed is null";
			execute("insert into " + DISMISSAL_TABLE + " (patient_id, flag_id, dismissed_by, date_dismissed)"
			        + " select patient_id, flag_id, " + userId + ", " + now + " from " + TABLE + undismissed);
			execute("update " + TABLE + " set dismissed_by = " + userId + ", date_dismissed = " + now + undismissed);
			new FlagListSync().syncPatient(flag.getFlagId(), patient.getPatientId());
			log.info("Flag '{}' of patient {} dismissed by user {}", flag.getName(), patient.getPatientId(),
			    Context.getAuthenticatedUser().getUserId());
			return Dismissal.DISMISSED;
		}
		finally {
			for (String privilege : DISMISS_PRIVILEGES) {
				Context.removeProxyPrivilege(privilege);
			}
			PatientFlagRefreshTask.ROWS.readLock().unlock();
		}
	}
	
	private static boolean carriesDismissTag(Flag flag) {
		String tag = Context.getAdministrationService().getGlobalProperty(DISMISS_TAG_PROPERTY);
		if (tag == null || tag.trim().isEmpty()) {
			return true;
		}
		if (flag.getTags() != null) {
			for (Tag candidate : flag.getTags()) {
				if (tag.trim().equalsIgnoreCase(candidate.getName())) {
					return true;
				}
			}
		}
		return false;
	}
	
	// The JVM's clock, as for the lists' start dates; current_timestamp keeps the database's zone.
	private static String now() {
		return "'" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + "'";
	}
	
	private static void execute(String sql) {
		Context.getAdministrationService().executeSQL(sql, false);
	}
	
	private static Set<Integer> ids(String sql) {
		Set<Integer> ids = new HashSet<Integer>();
		for (List<Object> row : select(sql)) {
			ids.add(((Number) row.get(0)).intValue());
		}
		return ids;
	}
	
	private static Map<Integer, Date> dates(String sql) {
		Map<Integer, Date> dates = new HashMap<Integer, Date>();
		for (List<Object> row : select(sql)) {
			dates.put(((Number) row.get(0)).intValue(), date(row.get(1)));
		}
		return dates;
	}
	
	/**
	 * A datetime column's value, which the JDBC driver gives as a Date, a LocalDateTime or a LocalDate.
	 */
	static Date date(Object value) {
		if (value instanceof LocalDateTime) {
			return Timestamp.valueOf((LocalDateTime) value);
		}
		if (value instanceof LocalDate) {
			return Timestamp.valueOf(((LocalDate) value).atStartOfDay());
		}
		return (Date) value;
	}
	
	// Proxied for the readers who see flags, such as a clinician loading the chart.
	private static List<List<Object>> select(String sql) {
		Context.addProxyPrivilege(PrivilegeConstants.SQL_LEVEL_ACCESS);
		try {
			return Context.getAdministrationService().executeSQL(sql, true);
		}
		finally {
			Context.removeProxyPrivilege(PrivilegeConstants.SQL_LEVEL_ACCESS);
		}
	}
}
