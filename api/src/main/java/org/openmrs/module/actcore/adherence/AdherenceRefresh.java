/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.adherence;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.openmrs.Patient;
import org.openmrs.api.APIException;
import org.openmrs.api.AdministrationService;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.ActCoreConstants;
import org.openmrs.module.actcore.Transactions;
import org.openmrs.module.actcore.api.db.AdherenceDAO;
import org.openmrs.module.actcore.task.PatientFlagRefreshTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The adherence refresh, which recomputes patients' prophylaxis adherence and next due date into
 * actcore_prophylaxis_adherence, and whether each of their injections was on time into
 * actcore_injection_timing. It runs for every patient on a schedule, and for one patient when a
 * form of theirs is saved. {@link org.openmrs.module.actcore.api.AdherenceService} runs it, reading
 * and writing through the DAO it is given.
 */
public class AdherenceRefresh {
	
	private static final Logger log = LoggerFactory.getLogger(AdherenceRefresh.class);
	
	/**
	 * The lock that keeps a refresh of one patient from writing while the full refresh runs. Each
	 * patient's refresh holds it shared, and the full refresh holds it alone, because the full refresh
	 * reads every patient before it rewrites them all, so a row written in between would be put back as
	 * it was.
	 */
	private static final ReentrantReadWriteLock ROWS = new ReentrantReadWriteLock();
	
	// The handshake PatientFlagRefreshTask uses for flags: a fix to one belongs in both.
	private static final Set<Integer> DEFERRED = ConcurrentHashMap.newKeySet();
	
	// Uuids are written into the queries, so a global property holding anything else is refused.
	private static final Pattern UUID = Pattern.compile("[A-Za-z0-9-]{1,38}");
	
	private final AdherenceDAO dao;
	
	private final int rowsPerInsert;
	
	/**
	 * Creates a refresh that reads and writes through the DAO.
	 *
	 * @param dao the DAO
	 */
	public AdherenceRefresh(AdherenceDAO dao) {
		this(dao, 200);
	}
	
	AdherenceRefresh(AdherenceDAO dao, int rowsPerInsert) {
		this.dao = dao;
		this.rowsPerInsert = rowsPerInsert;
	}
	
	/**
	 * Recomputes the rows of every patient and returns how many patients now have one, then refreshes
	 * the patients whose own refresh was left to this run because it was running.
	 * <p>
	 * Call it outside a transaction. Inside one, the rewrite would commit only after the lock is
	 * released, and the deferred patients would be refreshed from a snapshot that does not yet hold it.
	 *
	 * @param today the day to compute adherence and due dates as of
	 * @return the number of patients written a row, 0 when none has a prescription
	 */
	public int refreshAll(LocalDate today) {
		ROWS.writeLock().lock();
		try {
			return refresh(today);
		}
		finally {
			ROWS.writeLock().unlock();
			refreshDeferred(today);
		}
	}
	
	private void refreshDeferred(LocalDate today) {
		for (Integer patientId : new ArrayList<Integer>(DEFERRED)) {
			if (DEFERRED.remove(patientId)) {
				try {
					refreshPatient(patientId, today);
					PatientFlagRefreshTask.refreshPatient(patientId);
				}
				catch (RuntimeException e) {
					log.error("Could not refresh patient {}, saved during the adherence refresh", patientId, e);
				}
			}
		}
	}
	
	/**
	 * Recomputes one patient's rows, so that the registry reflects a saved form at once rather than
	 * after the next full refresh. While a full refresh is running, the patient is left to that run,
	 * which refreshes the patient and their flags once it has finished.
	 *
	 * @param patientId the patient to recompute
	 * @param today the day to compute adherence and due dates as of
	 */
	public void refreshPatient(int patientId, LocalDate today) {
		if (!ROWS.readLock().tryLock()) {
			DEFERRED.add(patientId);
			// The full refresh may have let go, and refreshed the ones it was left, since the first try.
			if (!ROWS.readLock().tryLock()) {
				log.info("Adherence of patient {} left to the adherence refresh running now", patientId);
				return;
			}
			if (!DEFERRED.remove(patientId)) {
				// That refresh took the patient.
				ROWS.readLock().unlock();
				return;
			}
		}
		try {
			List<Object[]> rows = new ArrayList<Object[]>();
			List<Object[]> timings = new ArrayList<Object[]>();
			compute(read(patientId), today, rows, timings);
			rewrite(patientId, rows, timings);
		}
		finally {
			ROWS.readLock().unlock();
		}
	}
	
	/**
	 * Computes the patient's prophylaxis summary from their history as it stands now, read through the
	 * same queries the full refresh runs.
	 *
	 * @param patient the patient
	 * @param today the day to compute the summary as of
	 * @return the summary, as {@link ProphylaxisSummary} describes it
	 */
	public ProphylaxisSummary summaryOf(Patient patient, LocalDate today) {
		AdherenceReplay.History history = read(patient.getPatientId()).get(patient.getPatientId());
		return ProphylaxisSummary.of(patient, history == null ? new AdherenceReplay.History() : history, today);
	}
	
	/**
	 * Gets the adherence each patient held on each of the days, reading each patient's history once.
	 *
	 * @param patientIds the patients to replay
	 * @param days the days to replay each patient on
	 * @return each patient's adherence by day, as {@link AdherenceSeries} gives it
	 */
	public Map<Integer, Map<LocalDate, Double>> adherenceOn(Collection<Integer> patientIds, SortedSet<LocalDate> days) {
		if (patientIds.isEmpty() || days.isEmpty()) {
			return new HashMap<Integer, Map<LocalDate, Double>>();
		}
		return AdherenceSeries.adherenceOn(read(patientIds), days);
	}
	
	private int refresh(LocalDate today) {
		List<Object[]> rows = new ArrayList<Object[]>();
		List<Object[]> timings = new ArrayList<Object[]>();
		compute(read((Integer) null), today, rows, timings);
		rewrite(null, rows, timings);
		return rows.size();
	}
	
	/**
	 * Computes each history's adherence row and injection timings and adds them to the lists given,
	 * ready to insert. A patient without a prescription gets neither.
	 */
	private void compute(Map<Integer, AdherenceReplay.History> histories, LocalDate today, List<Object[]> rows,
	        List<Object[]> timings) {
		Timestamp computedAt = new Timestamp(System.currentTimeMillis());
		Integer oralFirstDueDays = oralFirstDueDays();
		for (Map.Entry<Integer, AdherenceReplay.History> entry : histories.entrySet()) {
			if (entry.getValue().prescriptions.isEmpty()) {
				continue;
			}
			for (ProphylaxisSummary.Injection injection : ProphylaxisSummary.timings(entry.getValue().prescriptions,
			    entry.getValue().injections, today)) {
				if (injection.getOnTime() != null) {
					timings.add(new Object[] { entry.getKey(), injection.getDate(), injection.getOnTime() });
				}
			}
			AdherenceReplay.Row row = AdherenceReplay.replay(entry.getValue(), today, oralFirstDueDays);
			if (row != null) {
				rows.add(new Object[] { entry.getKey(), row.regimen, row.interval, row.adherence, row.lastGiven, row.nextDue,
				        computedAt });
			}
		}
	}
	
	/**
	 * Reads the history of the patient given, or of every patient when the id is null, keyed by patient
	 * id.
	 */
	Map<Integer, AdherenceReplay.History> read(Integer patientId) {
		return read(patientId == null ? null : Collections.singletonList(patientId));
	}
	
	/**
	 * Reads the histories of the patients given, or of every patient when the collection is null, keyed
	 * by patient id. An empty collection reads nothing.
	 */
	Map<Integer, AdherenceReplay.History> read(Collection<Integer> patientIds) {
		Map<Integer, AdherenceReplay.History> histories = new LinkedHashMap<Integer, AdherenceReplay.History>();
		if (patientIds != null && patientIds.isEmpty()) {
			return histories;
		}
		// One transaction gives the four reads one snapshot, and a session on a thread with none bound.
		return inTransaction(() -> {
			AdministrationService admin = Context.getAdministrationService();
			readPrescriptions(admin, daysByUuid(admin, ActCoreConstants.GP_ADHERENCE_INJECTION_INTERVALS),
			    uuids(admin, ActCoreConstants.GP_ADHERENCE_NO_PROPHYLAXIS_ANSWERS), patientIds, histories);
			readInjections(admin, patientIds, histories);
			readEstimates(admin, daysByUuid(admin, ActCoreConstants.GP_ADHERENCE_PRESCRIPTION_DURATIONS), patientIds,
			    histories);
			readConsultations(admin, daysByUuid(admin, ActCoreConstants.GP_ADHERENCE_DATA_ENTRY_INTERVALS), patientIds,
			    histories);
			return histories;
		});
	}
	
	/**
	 * Replaces the rows of both tables of the patient, or of every patient when the id is null, with
	 * the rows given, in one transaction, so that a run cannot leave either table empty or partly
	 * filled.
	 */
	private void rewrite(Integer patientId, List<Object[]> rows, List<Object[]> timings) {
		inTransaction(() -> {
			dao.replaceRows(patientId, rows, timings, rowsPerInsert);
			return null;
		});
	}
	
	/** Runs the work in the current transaction, or in a new one when none is in progress. */
	private static <T> T inTransaction(Transactions.Work<T> work) {
		try {
			return Transactions.inTransaction(work);
		}
		catch (RuntimeException | Error e) {
			throw e;
		}
		catch (Throwable e) {
			throw new APIException(e);
		}
	}
	
	private static AdherenceReplay.History history(Map<Integer, AdherenceReplay.History> histories, Object patientId) {
		Integer id = ((Number) patientId).intValue();
		if (!histories.containsKey(id)) {
			histories.put(id, new AdherenceReplay.History());
		}
		return histories.get(id);
	}
	
	/**
	 * Reads each consultation's prescriptions into the histories, keyed by the consultation's date.
	 * When two consultations share a date, the first one is kept, as ACT 2.0 kept it. A consultation's
	 * prescriptions leave out the stopped ones, keeping the first of each start date, while its courses
	 * include the stopped ones too.
	 */
	private void readPrescriptions(AdministrationService admin, Map<String, Integer> intervals, Set<String> noProphylaxis,
	        Collection<Integer> patientIds, Map<Integer, AdherenceReplay.History> histories) {
		String consultationDate = concept(admin, ActCoreConstants.GP_ADHERENCE_CONSULTATION_DATE_CONCEPT);
		String dateStarted = concept(admin, ActCoreConstants.GP_ADHERENCE_DATE_STARTED_CONCEPT);
		String dateStopped = concept(admin, ActCoreConstants.GP_ADHERENCE_DATE_STOPPED_CONCEPT);
		List<List<Object>> rows = dao.getPrescriptions(concept(admin, ActCoreConstants.GP_ADHERENCE_PRESCRIPTION_CONCEPT),
		    consultationDate, dateStarted, dateStopped, patientIds);
		// ACT 2.0 kept the first consultation of a day.
		Map<Integer, Map<LocalDate, Integer>> encounterOfDay = new HashMap<Integer, Map<LocalDate, Integer>>();
		for (List<Object> row : rows) {
			AdherenceReplay.History h = history(histories, row.get(0));
			Integer patientId = ((Number) row.get(0)).intValue();
			Integer encounterId = ((Number) row.get(2)).intValue();
			LocalDate day = localDate(row.get(1));
			if (!encounterOfDay.containsKey(patientId)) {
				encounterOfDay.put(patientId, new HashMap<LocalDate, Integer>());
			}
			Integer first = encounterOfDay.get(patientId).get(day);
			if (first == null) {
				encounterOfDay.get(patientId).put(day, encounterId);
				h.prescriptions.put(day, new AdherenceReplay.Prescriptions());
			} else if (!first.equals(encounterId)) {
				continue;
			}
			AdherenceReplay.Prescriptions p = h.prescriptions.get(day);
			// No start, or an answer such as None that prescribes nothing: the consultation records no course.
			if (row.get(5) == null || (row.get(3) != null && noProphylaxis.contains(row.get(3).toString()))) {
				continue;
			}
			LocalDate started = localDate(row.get(5));
			Integer days = row.get(3) == null ? null : intervals.get(row.get(3).toString());
			int interval = days == null ? 0 : days;
			if (row.get(6) != null) {
				if (!p.courses.containsKey(started)) {
					p.courses.put(started, new AdherenceReplay.Course(interval, localDate(row.get(6))));
				}
			} else if (!p.intervals.containsKey(started)) {
				p.intervals.put(started, interval);
				p.regimens.put(started, row.get(4) == null ? null : ((Number) row.get(4)).intValue());
				p.courses.put(started, new AdherenceReplay.Course(interval, null));
			}
		}
	}
	
	private void readInjections(AdministrationService admin, Collection<Integer> patientIds,
	        Map<Integer, AdherenceReplay.History> histories) {
		List<List<Object>> rows = dao.getInjections(concept(admin, ActCoreConstants.GP_ADHERENCE_INJECTION_DATE_CONCEPT),
		    patientIds);
		for (List<Object> row : rows) {
			history(histories, row.get(0)).injections.add(localDate(row.get(1)));
		}
	}
	
	/**
	 * Reads each oral adherence form into the histories, dated by its Date of Adherence Estimate, or by
	 * its encounter's date when it has none, just as a BPG form counts by its injection date. A form
	 * saved without an estimate is still read, as ACT 2.0 counted it.
	 */
	private void readEstimates(AdministrationService admin, Map<String, Integer> durations, Collection<Integer> patientIds,
	        Map<Integer, AdherenceReplay.History> histories) {
		// With no date concept set, every form counts from its encounter's date, as before the form had one.
		String estimateDate = StringUtils
		        .isNotBlank(admin.getGlobalProperty(ActCoreConstants.GP_ADHERENCE_ESTIMATE_DATE_CONCEPT))
		                ? concept(admin, ActCoreConstants.GP_ADHERENCE_ESTIMATE_DATE_CONCEPT)
		                : null;
		String estimateConcept = concept(admin, ActCoreConstants.GP_ADHERENCE_ESTIMATE_CONCEPT);
		List<List<Object>> rows = dao.getEstimates(estimateDate, estimateConcept,
		    concept(admin, ActCoreConstants.GP_ADHERENCE_PRESCRIPTION_DURATION_CONCEPT), patientIds);
		for (List<Object> row : rows) {
			TreeMap<LocalDate, AdherenceCalculation.OralEntry> oral = history(histories, row.get(0)).oral;
			LocalDate day = localDate(row.get(1));
			if (!oral.containsKey(day)) {
				Double estimate = row.get(2) == null ? null : ((Number) row.get(2)).doubleValue();
				Integer days = row.get(3) == null ? null : durations.get(row.get(3).toString());
				oral.put(day, new AdherenceCalculation.OralEntry(estimate, days));
			}
		}
	}
	
	/**
	 * Reads each consultation's date into the histories, with the number of days between batches that
	 * it records for how injections are entered. ACT 2.0 read that answer from the latest consultation
	 * alone, so a consultation that leaves it blank is read as continuous entry, recorded as 0.
	 */
	private void readConsultations(AdministrationService admin, Map<String, Integer> batchIntervals,
	        Collection<Integer> patientIds, Map<Integer, AdherenceReplay.History> histories) {
		String dataEntry = concept(admin, ActCoreConstants.GP_ADHERENCE_DATA_ENTRY_CONCEPT);
		List<List<Object>> rows = dao.getConsultations(
		    concept(admin, ActCoreConstants.GP_ADHERENCE_CONSULTATION_DATE_CONCEPT), dataEntry, patientIds);
		for (List<Object> row : rows) {
			AdherenceReplay.History h = history(histories, row.get(0));
			LocalDate day = localDate(row.get(1));
			Integer days = row.get(2) == null ? null : batchIntervals.get(row.get(2).toString());
			if (h.consultations.add(day)) {
				h.batchDays.put(day, days == null ? 0 : days);
			}
		}
	}
	
	/** Gets the uuid of the concept the global property names, checked to be safe to write into SQL. */
	private static String concept(AdministrationService admin, String property) {
		return uuid(admin.getGlobalProperty(property), property);
	}
	
	/**
	 * Reads a global property that lists uuid:days pairs separated by commas, as a map of each uuid to
	 * its days. A blank property gives an empty map, and a malformed one throws.
	 */
	private Map<String, Integer> daysByUuid(AdministrationService admin, String property) {
		Map<String, Integer> days = new HashMap<String, Integer>();
		for (String pair : StringUtils.split(StringUtils.defaultString(admin.getGlobalProperty(property)), ',')) {
			String[] parts = StringUtils.split(pair.trim(), ':');
			if (parts.length != 2 || !StringUtils.isNumeric(parts[1].trim())) {
				throw new IllegalStateException(property + " must list uuid:days pairs separated by commas");
			}
			days.put(uuid(parts[0].trim(), property), Integer.valueOf(parts[1].trim()));
		}
		return days;
	}
	
	/**
	 * Gets the number of days after an oral regimen starts that its first oral visit is due, while no
	 * oral adherence entry has been recorded under it. It is null when
	 * actcore.adherence.oralFirstDueDays is blank, which gives such a regimen no first-visit due date;
	 * it then takes its due date from the latest estimate recorded under an earlier oral course in the
	 * past year, or has none.
	 */
	static Integer oralFirstDueDays() {
		return days(Context.getAdministrationService(), ActCoreConstants.GP_ADHERENCE_ORAL_FIRST_DUE_DAYS);
	}
	
	/**
	 * Reads a global property that holds a number of days. It gives null when the property is blank,
	 * and throws when it holds anything other than a number.
	 */
	private static Integer days(AdministrationService admin, String property) {
		String value = StringUtils.trimToNull(admin.getGlobalProperty(property));
		if (value == null) {
			return null;
		}
		if (!StringUtils.isNumeric(value)) {
			throw new IllegalStateException(property + " must be a number of days, or empty");
		}
		return Integer.valueOf(value);
	}
	
	/**
	 * Reads a global property that lists concept uuids separated by commas. A blank property gives an
	 * empty set.
	 */
	private Set<String> uuids(AdministrationService admin, String property) {
		Set<String> found = new HashSet<String>();
		for (String value : StringUtils.split(StringUtils.defaultString(admin.getGlobalProperty(property)), ',')) {
			found.add(uuid(value, property));
		}
		return found;
	}
	
	private static String uuid(String value, String property) {
		if (value == null || !UUID.matcher(value.trim()).matches()) {
			throw new IllegalStateException(property + " must name a concept by uuid");
		}
		return value.trim();
	}
	
	/**
	 * Converts a date column's value to a local date. The JDBC driver gives such a value as a Date, a
	 * LocalDateTime or a LocalDate, depending on the database.
	 */
	static LocalDate localDate(Object value) {
		if (value instanceof LocalDateTime) {
			return ((LocalDateTime) value).toLocalDate();
		}
		if (value instanceof LocalDate) {
			return (LocalDate) value;
		}
		return new Timestamp(((Date) value).getTime()).toLocalDateTime().toLocalDate();
	}
}
