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

import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openmrs.Patient;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.api.db.hibernate.DbSession;
import org.openmrs.api.db.hibernate.DbSessionFactory;
import org.openmrs.module.actcore.Transactions;
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
	
	/** Records the flag as raised from now, unless it already is or the patient does not exist. */
	static void raise(int flagId, int patientId) {
		inTransaction(() -> {
			RaisedFlag.Key key = new RaisedFlag.Key(patientId, flagId);
			if (session().get(RaisedFlag.class, key) == null && exists(patientId)) {
				RaisedFlag raised = new RaisedFlag(key, new Date());
				session().save(raised);
				// Out of the session, or every later commit of the full refresh dirty-checks it.
				session().flush();
				session().evict(raised);
			}
			return null;
		});
	}
	
	static void lower(int flagId, int patientId) {
		inTransaction(
		    () -> session().createQuery("delete from RaisedFlag where key.flagId = :flag and key.patientId = :patient")
		            .setParameter("flag", flagId).setParameter("patient", patientId).executeUpdate());
	}
	
	static void lowerAll(int flagId) {
		inTransaction(() -> session().createQuery("delete from RaisedFlag where key.flagId = :flag")
		        .setParameter("flag", flagId).executeUpdate());
	}
	
	static Set<Integer> raisedPatients(int flagId) {
		return ids("select key.patientId from RaisedFlag where key.flagId = :id", flagId);
	}
	
	static Set<Integer> dismissedPatients(int flagId) {
		return ids("select key.patientId from RaisedFlag where key.flagId = :id and dateDismissed is not null", flagId);
	}
	
	static boolean isDismissed(int flagId, int patientId) {
		return dismissedFlags(patientId).contains(flagId);
	}
	
	/** The flags dismissed for the patient, by flag id. */
	public static Set<Integer> dismissedFlags(int patientId) {
		return ids("select key.flagId from RaisedFlag where key.patientId = :id and dateDismissed is not null", patientId);
	}
	
	/** Since when each of the flag's patients has had it raised, by patient id. */
	static Map<Integer, Date> raisedSinceByPatient(int flagId) {
		return dates("select key.patientId, raisedSince from RaisedFlag where key.flagId = :id", flagId);
	}
	
	/** Since when the patient has had each of their raised flags, by flag id. */
	public static Map<Integer, Date> raisedSinceByFlag(int patientId) {
		return dates("select key.flagId, raisedSince from RaisedFlag where key.patientId = :id", patientId);
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
			recordDismissal(flag.getFlagId(), patient.getPatientId(), Context.getAuthenticatedUser().getUserId());
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
	
	// The list sync runs after this commits, so a failing sync leaves the dismissal recorded.
	private static void recordDismissal(int flagId, int patientId, Integer userId) {
		raise(flagId, patientId);
		inTransaction(() -> {
			Date now = new Date();
			int dismissed = session()
			        .createQuery("update RaisedFlag set dismissedBy = :user, dateDismissed = :now"
			                + " where key.flagId = :flag and key.patientId = :patient and dateDismissed is null")
			        .setParameter("user", userId).setParameter("now", now).setParameter("flag", flagId)
			        .setParameter("patient", patientId).executeUpdate();
			if (dismissed > 0) {
				session().save(new FlagDismissal(patientId, flagId, userId, now));
			}
			return null;
		});
	}
	
	private static boolean exists(int patientId) {
		return ((Number) session().createQuery("select count(*) from Patient where patientId = :id")
		        .setParameter("id", patientId).uniqueResult()).longValue() > 0;
	}
	
	private static Set<Integer> ids(String hql, int id) {
		Set<Integer> ids = new HashSet<Integer>();
		for (Object value : list(hql, id)) {
			ids.add((Integer) value);
		}
		return ids;
	}
	
	private static Map<Integer, Date> dates(String hql, int id) {
		Map<Integer, Date> dates = new HashMap<Integer, Date>();
		for (Object row : list(hql, id)) {
			dates.put((Integer) ((Object[]) row)[0], (Date) ((Object[]) row)[1]);
		}
		return dates;
	}
	
	private static List<?> list(String hql, int id) {
		return inTransaction(() -> session().createQuery(hql).setParameter("id", id).list());
	}
	
	private static <T> T inTransaction(Transactions.Work<T> work) {
		try {
			return Transactions.inTransaction(work);
		}
		catch (RuntimeException e) {
			throw e;
		}
		catch (Throwable e) {
			throw new APIException("Could not read or write the raised flags", e);
		}
	}
	
	private static DbSession session() {
		return Context.getRegisteredComponent("dbSessionFactory", DbSessionFactory.class).getCurrentSession();
	}
}
