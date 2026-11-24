/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.tasks;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

import org.openmrs.Cohort;
import org.openmrs.CohortMembership;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.ActCoreConstants;
import org.openmrs.module.actcore.api.RaisedFlagService;
import org.openmrs.module.actcore.api.impl.FlagListSync;
import org.openmrs.module.actcore.utils.Transactions;
import org.openmrs.module.patientflags.Flag;
import org.openmrs.module.patientflags.PatientFlag;
import org.openmrs.module.patientflags.PatientFlagsConstants;
import org.openmrs.module.patientflags.api.FlagService;
import org.openmrs.scheduler.tasks.AbstractTask;
import org.openmrs.util.PrivilegeConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The scheduled task that refreshes the patient flags. It first refreshes adherence, then
 * re-evaluates every enabled, unretired flag, writing only the flag rows that changed, and then
 * syncs the flag lists. The patientflags module evaluates a flag only when a patient's data is
 * written, so without this task a criterion that becomes true with the passing of time would never
 * raise its flag.
 */
public class PatientFlagRefreshTask extends AbstractTask {
	
	private static final Logger log = LoggerFactory.getLogger(PatientFlagRefreshTask.class);
	
	/**
	 * The lock held while a full refresh runs. It is static because the REST taskaction resource and
	 * the refresh endpoint each run a new instance of the task alongside the scheduler's.
	 */
	private static final ReentrantLock RUNNING = new ReentrantLock();
	
	/**
	 * The lock that keeps a full refresh from writing a flag's rows or a list's members at the same
	 * time as a patient's refresh or a dismissal does. Each patient's refresh and each dismissal hold
	 * it shared, and the full refresh holds it alone.
	 */
	public static final ReentrantReadWriteLock ROWS = new ReentrantReadWriteLock();
	
	// Patients saved while the full refresh held the rows, refreshed once it lets go.
	private static final Set<Integer> DEFERRED = ConcurrentHashMap.newKeySet();
	
	// What one patient's refresh needs from a user who saved an encounter, the lists included.
	private static final String[] PATIENT_PRIVILEGES = { PatientFlagsConstants.PRIV_TEST_FLAGS,
	        PrivilegeConstants.SQL_LEVEL_ACCESS, PrivilegeConstants.GET_PATIENTS, PrivilegeConstants.GET_GLOBAL_PROPERTIES,
	        ActCoreConstants.COHORT_VIEW_COHORTS, ActCoreConstants.COHORT_EDIT_COHORTS };
	
	// Counted per row raised or cleared, not per flag, so a flag that fails partway still counts.
	int raised;
	
	int cleared;
	
	/** What failed in a run of the refresh, which the refresh endpoint reports. */
	public static class Result {
		
		/** How many flags failed to be evaluated or written. */
		public final int flagsFailed;
		
		/** How many flag lists failed to sync. */
		public final int listsFailed;
		
		/** Whether the adherence refresh that runs first failed. */
		public final boolean adherenceFailed;
		
		Result(int flagsFailed, int listsFailed, boolean adherenceFailed) {
			this.flagsFailed = flagsFailed;
			this.listsFailed = listsFailed;
			this.adherenceFailed = adherenceFailed;
		}
	}
	
	/** @see org.openmrs.scheduler.tasks.AbstractTask#execute() */
	@Override
	public void execute() {
		runIfIdle();
	}
	
	/**
	 * Runs the refresh now unless another run is in progress, and reports what failed in it. When the
	 * run finishes, it refreshes the patients whose own refresh was left to it.
	 *
	 * @return the flags and lists that failed and whether the adherence refresh failed, or null when
	 *         another run was in progress
	 */
	public Result runIfIdle() {
		if (!RUNNING.tryLock()) {
			log.warn("Patient flag refresh skipped: another run is in progress");
			return null;
		}
		try {
			// Waits for the patients' refreshes already going.
			ROWS.writeLock().lock();
			try {
				return refresh();
			}
			finally {
				ROWS.writeLock().unlock();
				refreshDeferred();
			}
		}
		finally {
			RUNNING.unlock();
		}
	}
	
	/**
	 * Refreshes each patient saved while this run held the rows, since the run may have evaluated their
	 * flags before the save.
	 */
	private void refreshDeferred() {
		for (Integer patientId : new ArrayList<Integer>(DEFERRED)) {
			if (DEFERRED.remove(patientId)) {
				try {
					refreshDeferred(patientId);
				}
				catch (RuntimeException e) {
					log.error("Could not refresh the flags of patient {}, saved during the refresh", patientId, e);
				}
			}
		}
	}
	
	void refreshDeferred(int patientId) {
		refreshPatient(patientId);
	}
	
	/**
	 * Tells whether a full refresh is running now.
	 *
	 * @return true while a run holds the refresh, whichever instance started it
	 */
	public static boolean isRunning() {
		return RUNNING.isLocked();
	}
	
	/**
	 * Re-evaluates one patient's flags and updates the patient's place on the flag lists, after a save
	 * that the patientflags module's own pass may have evaluated before it committed. While a full
	 * refresh is writing the rows, the patient is left to that run, which refreshes them when it ends.
	 *
	 * @param patientId the patient whose flags to refresh
	 */
	public static void refreshPatient(int patientId) {
		refreshPatient(patientId, new Consumer<Runnable>() {
			
			@Override
			public void accept(Runnable work) {
				Transactions.inNewTransaction(work);
			}
		});
	}
	
	/**
	 * Refreshes one patient's flags, running each flag, and each list, as a unit of its own. A
	 * criterion that fails marks the transaction it ran in as rollback-only, which would otherwise take
	 * every other flag's change with it.
	 */
	static void refreshPatient(final int patientId, Consumer<Runnable> unit) {
		if (!ROWS.readLock().tryLock()) {
			DEFERRED.add(patientId);
			// The full refresh may have let go, and refreshed the ones it was left, since the first try.
			if (!ROWS.readLock().tryLock()) {
				log.info("Flags of patient {} left to the patient flag refresh running now", patientId);
				return;
			}
			if (!DEFERRED.remove(patientId)) {
				// That refresh took the patient.
				ROWS.readLock().unlock();
				return;
			}
		}
		for (String privilege : PATIENT_PRIVILEGES) {
			Context.addProxyPrivilege(privilege);
		}
		try {
			final List<Integer> flagIds = new ArrayList<Integer>();
			unit.accept(new Runnable() {
				
				@Override
				public void run() {
					for (Flag flag : Context.getService(FlagService.class).getAllFlags()) {
						flagIds.add(flag.getFlagId());
					}
				}
			});
			for (final Integer flagId : flagIds) {
				try {
					unit.accept(new Runnable() {
						
						@Override
						public void run() {
							reconcile(flagId, patientId);
						}
					});
				}
				catch (RuntimeException e) {
					log.error("Could not refresh flag {} for patient {}", flagId, patientId, e);
				}
			}
			for (final Integer flagId : flagIds) {
				try {
					unit.accept(new Runnable() {
						
						@Override
						public void run() {
							new FlagListSync().syncPatient(flagId, patientId);
						}
					});
				}
				catch (RuntimeException e) {
					log.error("Could not sync the list of flag {} for patient {}", flagId, patientId, e);
				}
			}
		}
		finally {
			for (String privilege : PATIENT_PRIVILEGES) {
				Context.removeProxyPrivilege(privilege);
			}
			ROWS.readLock().unlock();
		}
	}
	
	/**
	 * Reconciles one flag's row for one patient, as the full refresh reconciles a flag's rows for every
	 * patient. A voided patient matches no flag.
	 */
	private static void reconcile(int flagId, int patientId) {
		FlagService flagService = Context.getService(FlagService.class);
		Flag flag = flagService.getFlag(flagId);
		Patient patient = Context.getPatientService().getPatient(patientId);
		if (flag == null || patient == null || !Boolean.TRUE.equals(flag.getEnabled())
		        || Boolean.TRUE.equals(flag.getRetired())) {
			return;
		}
		Map<Object, Object> evaluationContext = new HashMap<Object, Object>();
		boolean matches = !Boolean.TRUE.equals(patient.getVoided())
		        && Boolean.TRUE.equals(flag.eval(patient, evaluationContext));
		Map<Integer, String> alreadyFlagged = flaggedMessages(flag, patientId);
		
		RaisedFlagService raisedFlags = Context.getService(RaisedFlagService.class);
		if (!matches) {
			if (alreadyFlagged.containsKey(patientId)) {
				flagService.deletePatientFlagForPatient(patient, flag);
			}
			raisedFlags.lower(flagId, patientId);
			return;
		}
		raisedFlags.raise(flagId, patientId);
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
				lowerAll(flag);
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
	
	/**
	 * Clears the raised dates and dismissals kept for a flag that is no longer evaluated because it is
	 * disabled or retired.
	 */
	private static void lowerAll(Flag flag) {
		try {
			Context.getService(RaisedFlagService.class).lowerAll(flag.getFlagId());
		}
		catch (RuntimeException e) {
			log.error("Could not clear when flag '{}' was raised", flag.getName(), e);
		}
	}
	
	void refreshAdherence() {
		AdherenceRefreshTask.refreshBeforeFlags();
	}
	
	FlagListSync.Result syncLists() {
		return new FlagListSync().syncAll();
	}
	
	private static void recordFinished() {
		try {
			Context.getAdministrationService().setGlobalProperty(ActCoreConstants.GP_REFRESH_LAST_FINISHED,
			    Instant.now().toString());
		}
		catch (RuntimeException e) {
			log.error("Could not record when the patient flag refresh finished", e);
		}
	}
	
	/**
	 * Logs both halves of the run, the flags and the lists, in one line, at warn level when any of it
	 * failed. The packaged log4j2.xml leaves this module at warn, where a run that half worked would
	 * otherwise look like silence.
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
		
		trackRaised(flag, matching);
		
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
	
	void trackRaised(Flag flag, Set<Integer> matching) {
		RaisedFlagService raisedFlags = Context.getService(RaisedFlagService.class);
		Set<Integer> alreadyRaised = raisedFlags.getRaisedPatients(flag.getFlagId());
		for (Integer patientId : alreadyRaised) {
			if (!matching.contains(patientId)) {
				raisedFlags.lower(flag.getFlagId(), patientId);
			}
		}
		for (Integer patientId : matching) {
			if (!alreadyRaised.contains(patientId)) {
				raisedFlags.raise(flag.getFlagId(), patientId);
			}
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
	 * Reads a flag's unvoided rows by SQL, since FlagService cannot list a flag's patients, as a map of
	 * patient id to message. A patient with more than one row maps to null, so that the rows are
	 * rewritten as one. A voided row must not count, or the flag would never be raised again for that
	 * patient.
	 *
	 * @param flag the flag whose rows to read
	 * @return each flagged patient's id, mapped to the row's message
	 */
	public static Map<Integer, String> flaggedMessages(Flag flag) {
		return flaggedMessages(flag, "");
	}
	
	/**
	 * Reads the flag's unvoided row for one patient, as {@link #flaggedMessages(Flag)} reads every
	 * patient's. The map is empty when the patient has no row.
	 *
	 * @param flag the flag whose row to read
	 * @param patientId the patient whose row to read
	 * @return the patient's id mapped to the row's message, or an empty map
	 */
	public static Map<Integer, String> flaggedMessages(Flag flag, int patientId) {
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
					Integer patientId = ((Number) row.get(0)).intValue();
					// A second row, as patientflags' own thread can write beside a refresh, is rewritten as one.
					messages.put(patientId, messages.containsKey(patientId) ? null : (String) row.get(1));
				}
			}
		}
		return messages;
	}
	
	/**
	 * Gives the message for the patient's flag row: the first message a custom evaluator supplied, or
	 * else the flag's own message. Only one is kept because reconcile tracks one row per patient, while
	 * the patientflags module's PatientFlagTask writes a row for each message it is given.
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
