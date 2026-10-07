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
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.openmrs.api.AdministrationService;
import org.openmrs.api.context.Context;
import org.openmrs.util.PrivilegeConstants;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Recomputes every patient's adherence and next due date into actcore_prophylaxis_adherence, and
 * each injection's timing into actcore_injection_timing.
 */
public class AdherenceRefresh {
	
	public static final String TABLE = "actcore_prophylaxis_adherence";
	
	/**
	 * Each injection timed as the chart times it, for reports that count on-time injections over a
	 * period.
	 */
	public static final String TIMING_TABLE = "actcore_injection_timing";
	
	static final String GP_PRESCRIPTION = "actcore.adherence.prescriptionConcept";
	
	static final String GP_INJECTION_INTERVALS = "actcore.adherence.injectionIntervals";
	
	static final String GP_DATE_STARTED = "actcore.adherence.dateStartedConcept";
	
	static final String GP_DATE_STOPPED = "actcore.adherence.dateStoppedConcept";
	
	static final String GP_INJECTION_DATE = "actcore.adherence.injectionDateConcept";
	
	static final String GP_ESTIMATE = "actcore.adherence.estimateConcept";
	
	static final String GP_DURATION = "actcore.adherence.prescriptionDurationConcept";
	
	static final String GP_DURATIONS = "actcore.adherence.prescriptionDurations";
	
	static final String GP_CONSULTATION_DATE = "actcore.adherence.consultationDateConcept";
	
	static final String GP_DATA_ENTRY = "actcore.adherence.dataEntryConcept";
	
	static final String GP_DATA_ENTRY_INTERVALS = "actcore.adherence.dataEntryIntervals";
	
	static final String GP_NO_PROPHYLAXIS = "actcore.adherence.noProphylaxisAnswers";
	
	// Uuids are written into the queries, so a global property holding anything else is refused.
	private static final Pattern UUID = Pattern.compile("[A-Za-z0-9-]{1,38}");
	
	private final int rowsPerInsert;
	
	public AdherenceRefresh() {
		this(200);
	}
	
	AdherenceRefresh(int rowsPerInsert) {
		this.rowsPerInsert = rowsPerInsert;
	}
	
	/** Recomputes every row and returns how many patients now have one. */
	public int refreshAll(LocalDate today) {
		try {
			Context.addProxyPrivilege(PrivilegeConstants.SQL_LEVEL_ACCESS);
			return refresh(Context.getAdministrationService(), today);
		}
		finally {
			Context.removeProxyPrivilege(PrivilegeConstants.SQL_LEVEL_ACCESS);
		}
	}
	
	/** One patient's history, read now through the same queries the nightly refresh runs. */
	AdherenceReplay.History historyOf(int patientId) {
		try {
			Context.addProxyPrivilege(PrivilegeConstants.SQL_LEVEL_ACCESS);
			AdherenceReplay.History h = read(Context.getAdministrationService(), patientId).get(patientId);
			return h == null ? new AdherenceReplay.History() : h;
		}
		finally {
			Context.removeProxyPrivilege(PrivilegeConstants.SQL_LEVEL_ACCESS);
		}
	}
	
	private int refresh(AdministrationService admin, LocalDate today) {
		Map<Integer, AdherenceReplay.History> histories = read(admin, null);
		
		Timestamp computedAt = new Timestamp(System.currentTimeMillis());
		List<String> rows = new ArrayList<String>();
		List<String> timings = new ArrayList<String>();
		for (Map.Entry<Integer, AdherenceReplay.History> entry : histories.entrySet()) {
			if (entry.getValue().prescriptions.isEmpty()) {
				continue;
			}
			for (ProphylaxisSummary.Injection injection : ProphylaxisSummary.timings(entry.getValue().prescriptions,
			    entry.getValue().injections, today)) {
				if (injection.getOnTime() != null) {
					timings.add("(" + entry.getKey() + ", " + sqlValue(injection.getDate()) + ", "
					        + sqlValue(injection.getOnTime()) + ")");
				}
			}
			AdherenceReplay.Row row = AdherenceReplay.replay(entry.getValue(), today);
			if (row != null) {
				rows.add("(" + entry.getKey() + ", " + sqlValue(row.regimen) + ", " + sqlValue(row.interval) + ", "
				        + sqlValue(row.adherence) + ", " + sqlValue(row.lastGiven) + ", " + sqlValue(row.nextDue) + ", "
				        + sqlValue(computedAt) + ")");
			}
		}
		
		rewrite(admin, rows, timings);
		return rows.size();
	}
	
	/** Every patient's history, or only that of the patient given. */
	private Map<Integer, AdherenceReplay.History> read(AdministrationService admin, Integer patientId) {
		Map<Integer, AdherenceReplay.History> histories = new LinkedHashMap<Integer, AdherenceReplay.History>();
		readPrescriptions(admin, daysByUuid(admin, GP_INJECTION_INTERVALS), uuids(admin, GP_NO_PROPHYLAXIS),
		    only("g.person_id", patientId), histories);
		readInjections(admin, only("o.person_id", patientId), histories);
		readEstimates(admin, daysByUuid(admin, GP_DURATIONS), only("e.patient_id", patientId), histories);
		readConsultations(admin, daysByUuid(admin, GP_DATA_ENTRY_INTERVALS), only("o.person_id", patientId), histories);
		return histories;
	}
	
	private static String only(String column, Integer patientId) {
		return patientId == null ? "" : " and " + column + " = " + patientId;
	}
	
	/**
	 * Replaces every row of both tables in one transaction, so no run leaves them empty or part filled.
	 */
	private void rewrite(final AdministrationService admin, final List<String> rows, final List<String> timings) {
		PlatformTransactionManager transactions = Context.getRegisteredComponent("transactionManager",
		    PlatformTransactionManager.class);
		new TransactionTemplate(transactions).execute(new TransactionCallbackWithoutResult() {
			
			@Override
			protected void doInTransactionWithoutResult(TransactionStatus status) {
				admin.executeSQL("delete from " + TABLE, false);
				for (int i = 0; i < rows.size(); i += rowsPerInsert) {
					admin.executeSQL("insert into " + TABLE + " (patient_id, regimen_concept_id, injection_interval_days,"
					        + " adherence, last_given, next_due, date_computed) values "
					        + StringUtils.join(rows.subList(i, Math.min(rows.size(), i + rowsPerInsert)), ", "),
					    false);
				}
				admin.executeSQL("delete from " + TIMING_TABLE, false);
				for (int i = 0; i < timings.size(); i += rowsPerInsert) {
					admin.executeSQL(
					    "insert into " + TIMING_TABLE + " (patient_id, injection_date, on_time) values "
					            + StringUtils.join(timings.subList(i, Math.min(timings.size(), i + rowsPerInsert)), ", "),
					    false);
				}
			}
		});
	}
	
	private static AdherenceReplay.History history(Map<Integer, AdherenceReplay.History> histories, Object patientId) {
		Integer id = ((Number) patientId).intValue();
		if (!histories.containsKey(id)) {
			histories.put(id, new AdherenceReplay.History());
		}
		return histories.get(id);
	}
	
	/**
	 * Each consultation's prescriptions, by its date, without the stopped ones, the first of a start
	 * date kept; and its courses, stopped ones too.
	 */
	private void readPrescriptions(AdministrationService admin, Map<String, Integer> intervals, Set<String> noProphylaxis,
	        String patientFilter, Map<Integer, AdherenceReplay.History> histories) {
		List<List<Object>> rows = admin.executeSQL("select g.person_id, coalesce(cd.value_datetime, e.encounter_datetime),"
		        + " e.encounter_id, rc.uuid, rc.concept_id, s.value_datetime, x.value_datetime"
		        + " from obs g join encounter e on e.encounter_id = g.encounter_id and e.voided = false"
		        + " left join obs cd on cd.encounter_id = g.encounter_id and cd.voided = false and cd.concept_id = "
		        + concept(admin, GP_CONSULTATION_DATE)
		        + " left join obs r on r.obs_group_id = g.obs_id and r.voided = false and r.concept_id = g.concept_id"
		        + " left join concept rc on rc.concept_id = r.value_coded"
		        + " left join obs s on s.obs_group_id = g.obs_id and s.voided = false and s.concept_id = "
		        + concept(admin, GP_DATE_STARTED)
		        + " left join obs x on x.obs_group_id = g.obs_id and x.voided = false and x.concept_id = "
		        + concept(admin, GP_DATE_STOPPED) + " where g.voided = false and g.obs_group_id is null and g.concept_id = "
		        + concept(admin, GP_PRESCRIPTION) + patientFilter
		        + " order by g.person_id, coalesce(cd.value_datetime, e.encounter_datetime), e.encounter_id, g.obs_id,"
		        + " r.obs_id",
		    true);
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
	
	private void readInjections(AdministrationService admin, String patientFilter,
	        Map<Integer, AdherenceReplay.History> histories) {
		List<List<Object>> rows = admin.executeSQL("select o.person_id, o.value_datetime from obs o"
		        + " join encounter e on e.encounter_id = o.encounter_id and e.voided = false"
		        + " where o.voided = false and o.value_datetime is not null and o.concept_id = "
		        + concept(admin, GP_INJECTION_DATE) + patientFilter,
		    true);
		for (List<Object> row : rows) {
			history(histories, row.get(0)).injections.add(localDate(row.get(1)));
		}
	}
	
	/**
	 * Each oral adherence form, by its encounter's date since the form has none of its own; one saved
	 * without an estimate still counts, as ACT 2.0 counted it.
	 */
	private void readEstimates(AdministrationService admin, Map<String, Integer> durations, String patientFilter,
	        Map<Integer, AdherenceReplay.History> histories) {
		List<List<Object>> rows = admin.executeSQL(
		    "select e.patient_id, e.encounter_datetime, est.value_numeric, dc.uuid" + " from encounter e"
		            + " left join obs est on est.encounter_id = e.encounter_id and est.voided = false and est.concept_id = "
		            + concept(admin, GP_ESTIMATE)
		            + " left join obs d on d.encounter_id = e.encounter_id and d.voided = false and d.concept_id = "
		            + concept(admin, GP_DURATION) + " left join concept dc on dc.concept_id = d.value_coded"
		            + " where e.voided = false and (est.obs_id is not null or d.obs_id is not null)" + patientFilter
		            + " order by e.patient_id, e.encounter_datetime, e.encounter_id, est.obs_id, d.obs_id",
		    true);
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
	 * Each consultation's date and how it says injections are entered: ACT 2.0 read that from the
	 * latest consultation alone, so one that leaves it blank means continuous entry.
	 */
	private void readConsultations(AdministrationService admin, Map<String, Integer> batchIntervals, String patientFilter,
	        Map<Integer, AdherenceReplay.History> histories) {
		List<List<Object>> rows = admin.executeSQL("select o.person_id, o.value_datetime, c.uuid from obs o"
		        + " join encounter e on e.encounter_id = o.encounter_id and e.voided = false"
		        + " left join obs d on d.encounter_id = o.encounter_id and d.voided = false and d.concept_id = "
		        + concept(admin, GP_DATA_ENTRY) + " left join concept c on c.concept_id = d.value_coded"
		        + " where o.voided = false and o.value_datetime is not null and o.concept_id = "
		        + concept(admin, GP_CONSULTATION_DATE) + patientFilter
		        + " order by o.person_id, o.value_datetime, e.encounter_id, o.obs_id, d.obs_id",
		    true);
		for (List<Object> row : rows) {
			AdherenceReplay.History h = history(histories, row.get(0));
			LocalDate day = localDate(row.get(1));
			Integer days = row.get(2) == null ? null : batchIntervals.get(row.get(2).toString());
			if (h.consultations.add(day)) {
				h.batchDays.put(day, days == null ? 0 : days);
			}
		}
	}
	
	/** The concept_id of the concept the global property names by uuid, as a subquery. */
	private String concept(AdministrationService admin, String property) {
		return "(select concept_id from concept where uuid = '" + uuid(admin.getGlobalProperty(property), property) + "')";
	}
	
	/** A global property of uuid:days pairs, separated by commas. */
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
	
	/** A global property of concept uuids, separated by commas; none when blank. */
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
	
	/** A date column's value, which the JDBC driver gives as a Date, a LocalDateTime or a LocalDate. */
	static LocalDate localDate(Object value) {
		if (value instanceof LocalDateTime) {
			return ((LocalDateTime) value).toLocalDate();
		}
		if (value instanceof LocalDate) {
			return (LocalDate) value;
		}
		return new Timestamp(((Date) value).getTime()).toLocalDateTime().toLocalDate();
	}
	
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
