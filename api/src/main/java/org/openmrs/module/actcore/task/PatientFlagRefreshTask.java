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

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import org.openmrs.Cohort;
import org.openmrs.CohortMembership;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.adherence.AdherenceRefreshTask;
import org.openmrs.module.patientflags.Flag;
import org.openmrs.module.patientflags.PatientFlag;
import org.openmrs.module.patientflags.PatientFlagsConstants;
import org.openmrs.module.patientflags.api.FlagService;
import org.openmrs.scheduler.tasks.AbstractTask;
import org.openmrs.util.PrivilegeConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Re-evaluates every enabled flag, writing only the rows that changed, then syncs the flag lists.
 * patientflags evaluates only on writes, so without this a criterion that time makes true never
 * fires.
 */
public class PatientFlagRefreshTask extends AbstractTask {
	
	private static final Logger log = LoggerFactory.getLogger(PatientFlagRefreshTask.class);
	
	/**
	 * When the last refresh finished, as an ISO-8601 instant, for the scheduled and on-demand runs
	 * alike. Written even when some flags failed, or one broken flag would hide every run after it.
	 */
	public static final String LAST_FINISHED_PROPERTY = "actcore.refresh.lastFinished";
	
	/**
	 * Static because the REST taskaction resource and the refresh endpoint each run a new instance of
	 * the task alongside the scheduler's.
	 */
	private static final ReentrantLock RUNNING = new ReentrantLock();
	
	// What one patient's refresh needs from a user who saved an encounter, the lists included.
	private static final String[] PATIENT_PRIVILEGES = { PatientFlagsConstants.PRIV_TEST_FLAGS,
	        PrivilegeConstants.SQL_LEVEL_ACCESS, PrivilegeConstants.GET_PATIENTS, PrivilegeConstants.GET_GLOBAL_PROPERTIES,
	        "View Cohorts In Cohort Module", "Edit Cohorts in Cohort Module" };
	
	// Counted per row raised or cleared, not per flag, so a flag that fails partway still counts.
	int raised;
	
	int cleared;
	
	public static class Result {
		
		public final int flagsFailed;
		
		public final int listsFailed;
		
		public final boolean adherenceFailed;
		
		Result(int flagsFailed, int listsFailed, boolean adherenceFailed) {
			this.flagsFailed = flagsFailed;
			this.listsFailed = listsFailed;
			this.adherenceFailed = adherenceFailed;
		}
	}
	
	@Override
	public void execute() {
		runIfIdle();
	}
	
	/**
	 * Runs the refresh unless another run is in progress, returning what failed in it, or null when
	 * this call did not run it.
	 */
	public Result runIfIdle() {
		if (!RUNNING.tryLock()) {
			log.warn("Patient flag refresh skipped: another run is in progress");
			return null;
		}
		try {
			return refresh();
		}
		finally {
			RUNNING.unlock();
		}
	}
	
	public static boolean isRunning() {
		return RUNNING.isLocked();
	}
	
	/**
	 * Re-evaluates every enabled flag for one patient, then puts them on or takes them off the lists.
	 * patientflags evaluates a saved encounter's patient before the save commits, so a flag on what is
	 * written after it, as the overdue flag reads the adherence row, stays as it was until this runs.
	 */
	public static void refreshPatient(int patientId) {
		for (String privilege : PATIENT_PRIVILEGES) {
			Context.addProxyPrivilege(privilege);
		}
		try {
			Patient patient = Context.getPatientService().getPatient(patientId);
			if (patient == null) {
				return;
			}
			FlagService flagService = Context.getService(FlagService.class);
			for (Flag flag : flagService.getAllFlags()) {
				if (!Boolean.TRUE.equals(flag.getEnabled()) || Boolean.TRUE.equals(flag.getRetired())) {
					continue;
				}
				try {
					reconcile(flagService, flag, patient);
				}
				catch (Exception e) {
					log.error("Could not refresh flag '{}' for patient {}", flag.getName(), patientId, e);
				}
			}
			Context.flushSession();
			new FlagListSync().syncPatient(patient);
		}
		finally {
			for (String privilege : PATIENT_PRIVILEGES) {
				Context.removeProxyPrivilege(privilege);
			}
		}
	}
	
	/** As reconcile does for a flag's every patient, for one; a voided patient matches no flag. */
	private static void reconcile(FlagService flagService, Flag flag, Patient patient) {
		Integer patientId = patient.getPatientId();
		Map<Object, Object> evaluationContext = new HashMap<Object, Object>();
		boolean matches = !Boolean.TRUE.equals(patient.getVoided())
		        && Boolean.TRUE.equals(flag.eval(patient, evaluationContext));
		Map<Integer, String> alreadyFlagged = flaggedMessages(flag, patientId);
		
		if (!matches) {
			if (alreadyFlagged.containsKey(patientId)) {
				flagService.deletePatientFlagForPatient(patient, flag);
			}
			return;
		}
		String message = message(flag, patientId, evaluationContext);
		if (!alreadyFlagged.containsKey(patientId)) {
			flagService.savePatientFlag(new PatientFlag(patient, flag, message));
		} else if (!message.equals(alreadyFlagged.get(patientId))) {
			flagService.deletePatientFlagForPatient(patient, flag);
			flagService.savePatientFlag(new PatientFlag(patient, flag, message));
		}
	}
	
	private Result refresh() {
		long startedAt = System.currentTimeMillis();
		log.info("Patient flag refresh starting");
		
		// The overdue flag reads the adherence table, so recompute it first.
		boolean adherenceFailed = false;
		try {
			refreshAdherence();
		}
		catch (RuntimeException e) {
			adherenceFailed = true;
			log.error("Prophylaxis adherence refresh before the flags failed; the flags read its last run", e);
		}
		
		FlagService flagService = Context.getService(FlagService.class);
		int evaluated = 0;
		int failed = 0;
		raised = 0;
		cleared = 0;
		
		for (Flag flag : flagService.getAllFlags()) {
			if (!Boolean.TRUE.equals(flag.getEnabled()) || Boolean.TRUE.equals(flag.getRetired())) {
				continue;
			}
			evaluated++;
			try {
				reconcile(flagService, flag);
			}
			catch (Exception e) {
				// One bad criterion must not stop the flags behind it from being refreshed.
				failed++;
				log.error("Could not refresh flag '{}'", flag.getName(), e);
			}
		}
		
		// Clear the refresh's rows first, or every commit the sync makes dirty-checks them all.
		Context.flushSession();
		Context.clearSession();
		FlagListSync.Result lists = syncLists();
		
		report(evaluated, failed, raised, cleared, lists, System.currentTimeMillis() - startedAt);
		recordFinished();
		return new Result(failed, lists.failures, adherenceFailed);
	}
	
	void refreshAdherence() {
		AdherenceRefreshTask.refreshBeforeFlags();
	}
	
	FlagListSync.Result syncLists() {
		return new FlagListSync().syncAll();
	}
	
	private static void recordFinished() {
		try {
			Context.getAdministrationService().setGlobalProperty(LAST_FINISHED_PROPERTY, Instant.now().toString());
		}
		catch (RuntimeException e) {
			log.error("Could not record when the patient flag refresh finished", e);
		}
	}
	
	/**
	 * Reports both halves of the run in one line, raised to warn when any of it failed: the packaged
	 * log4j2.xml leaves this module at warn, where a half-working run would otherwise look like
	 * silence.
	 */
	private void report(int evaluated, int failed, int added, int removed, FlagListSync.Result lists, long elapsedMillis) {
		String summary = String.format(Locale.ROOT,
		    "Patient flag refresh finished in %.1fs: %d flags evaluated, %d rows raised, %d cleared;"
		            + " lists %d created, %d restored, %d retired, %d members added, %d members ended",
		    elapsedMillis / 1000.0, evaluated, added, removed, lists.created, lists.restored, lists.retired,
		    lists.membersAdded, lists.membersEnded);
		
		if (failed > 0 || lists.failures > 0) {
			log.warn("{}; {} flags and {} lists failed, see the errors logged above", summary, failed, lists.failures);
		} else {
			log.info(summary);
		}
	}
	
	void reconcile(FlagService flagService, Flag flag) {
		Map<Object, Object> evaluationContext = new HashMap<Object, Object>();
		Set<Integer> matching = evaluate(flagService, flag, evaluationContext);
		Map<Integer, String> alreadyFlagged = alreadyFlagged(flag);
		
		int raisedBefore = raised;
		int clearedBefore = cleared;
		
		// Clearing first, since evaluating a message can throw and end the flag's pass.
		for (Integer patientId : alreadyFlagged.keySet()) {
			if (!matching.contains(patientId)) {
				flagService.deletePatientFlagForPatient(new Patient(patientId), flag);
				cleared++;
			}
		}
		
		for (Integer patientId : matching) {
			String message = message(flag, patientId, evaluationContext);
			if (!alreadyFlagged.containsKey(patientId)) {
				flagService.savePatientFlag(new PatientFlag(new Patient(patientId), flag, message));
				raised++;
			} else if (!message.equals(alreadyFlagged.get(patientId))) {
				flagService.deletePatientFlagForPatient(new Patient(patientId), flag);
				flagService.savePatientFlag(new PatientFlag(new Patient(patientId), flag, message));
			}
		}
		
		if (raised > raisedBefore || cleared > clearedBefore) {
			log.debug("Flag '{}': {} raised, {} cleared", flag.getName(), raised - raisedBefore, cleared - clearedBefore);
		}
	}
	
	private Set<Integer> evaluate(FlagService flagService, Flag flag, Map<Object, Object> evaluationContext) {
		Set<Integer> patientIds = new HashSet<Integer>();
		Cohort cohort = flagService.getFlaggedPatients(flag, evaluationContext);
		if (cohort != null) {
			for (CohortMembership membership : cohort.getMemberships()) {
				patientIds.add(membership.getPatientId());
			}
		}
		return patientIds;
	}
	
	Map<Integer, String> alreadyFlagged(Flag flag) {
		return flaggedMessages(flag);
	}
	
	/**
	 * Reads a flag's unvoided rows directly, as FlagService cannot list a flag's patients. A voided row
	 * must not count, or the flag is never raised again for that patient.
	 */
	static Map<Integer, String> flaggedMessages(Flag flag) {
		return flaggedMessages(flag, "");
	}
	
	/** The flag's unvoided row for one patient, if it has one. */
	static Map<Integer, String> flaggedMessages(Flag flag, int patientId) {
		return flaggedMessages(flag, " and patient_id = " + patientId);
	}
	
	private static Map<Integer, String> flaggedMessages(Flag flag, String andWhere) {
		Map<Integer, String> messages = new HashMap<Integer, String>();
		List<List<Object>> rows = Context.getAdministrationService()
		        .executeSQL("select patient_id, message from patientflags_patient_flag where flag_id = " + flag.getFlagId()
		                + " and voided = false" + andWhere,
		            true);
		if (rows != null) {
			for (List<Object> row : rows) {
				if (row != null && !row.isEmpty() && row.get(0) != null) {
					messages.put(((Number) row.get(0)).intValue(), (String) row.get(1));
				}
			}
		}
		return messages;
	}
	
	/**
	 * Keeps one message, the first a custom evaluator supplies, since reconcile tracks one row per
	 * patient; patientflags' PatientFlagTask writes a row for each message it is given.
	 */
	@SuppressWarnings("unchecked")
	private static String message(Flag flag, Integer patientId, Map<Object, Object> evaluationContext) {
		Object supplied = evaluationContext.get(patientId);
		if (supplied instanceof List) {
			List<String> messages = (List<String>) supplied;
			if (!messages.isEmpty()) {
				return messages.get(0);
			}
		}
		return flag.evalMessage(patientId);
	}
}
