/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.rhdflags.adherence;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

/** Recomputes every patient's adherence and next due date into rhdflags_prophylaxis_adherence. */
public class AdherenceRefresh {
	
	public static final String TABLE = "rhdflags_prophylaxis_adherence";
	
	static final String GP_PRESCRIPTION = "rhdflags.adherence.prescriptionConcept";
	
	static final String GP_INJECTION_INTERVALS = "rhdflags.adherence.injectionIntervals";
	
	static final String GP_DATE_STARTED = "rhdflags.adherence.dateStartedConcept";
	
	static final String GP_DATE_STOPPED = "rhdflags.adherence.dateStoppedConcept";
	
	static final String GP_INJECTION_DATE = "rhdflags.adherence.injectionDateConcept";
	
	static final String GP_ESTIMATE = "rhdflags.adherence.estimateConcept";
	
	static final String GP_DURATION = "rhdflags.adherence.prescriptionDurationConcept";
	
	static final String GP_DURATIONS = "rhdflags.adherence.prescriptionDurations";
	
	static final String GP_CONSULTATION_DATE = "rhdflags.adherence.consultationDateConcept";
	
	static final String GP_DATA_ENTRY = "rhdflags.adherence.dataEntryConcept";
	
	static final String GP_DATA_ENTRY_INTERVALS = "rhdflags.adherence.dataEntryIntervals";
	
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
	
	private int refresh(AdministrationService admin, LocalDate today) {
		Map<Integer, AdherenceReplay.History> histories = new LinkedHashMap<Integer, AdherenceReplay.History>();
		readPrescriptions(admin, daysByUuid(admin, GP_INJECTION_INTERVALS), histories);
		readInjections(admin, histories);
		readEstimates(admin, daysByUuid(admin, GP_DURATIONS), histories);
		readConsultations(admin, daysByUuid(admin, GP_DATA_ENTRY_INTERVALS), histories);
		
		Timestamp computedAt = new Timestamp(System.currentTimeMillis());
		List<String> rows = new ArrayList<String>();
		for (Map.Entry<Integer, AdherenceReplay.History> entry : histories.entrySet()) {
			if (entry.getValue().prescriptions.isEmpty()) {
				continue;
			}
			AdherenceReplay.Row row = AdherenceReplay.replay(entry.getValue(), today);
			if (row != null) {
				rows.add("(" + entry.getKey() + ", " + sqlValue(row.regimen) + ", " + sqlValue(row.interval) + ", "
				        + sqlValue(row.result.getAdherence()) + ", " + sqlValue(row.lastGiven) + ", "
				        + sqlValue(row.result.getNextDue()) + ", " + sqlValue(computedAt) + ")");
			}
		}
		
		rewrite(admin, rows);
		return rows.size();
	}
	
	/** Replaces every row in one transaction, so no run leaves the table empty or part filled. */
	private void rewrite(final AdministrationService admin, final List<String> rows) {
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
	 * The prescriptions each encounter records, leaving out those with a date stopped, and keeping the
	 * first of each start date, as ACT 2.0 kept them.
	 */
	private void readPrescriptions(AdministrationService admin, Map<String, Integer> intervals,
	        Map<Integer, AdherenceReplay.History> histories) {
		List<List<Object>> rows = admin.executeSQL("select g.person_id, e.encounter_datetime, e.encounter_id,"
		        + " rc.uuid, rc.concept_id, s.value_datetime, x.value_datetime"
		        + " from obs g join encounter e on e.encounter_id = g.encounter_id and e.voided = false"
		        + " left join obs r on r.obs_group_id = g.obs_id and r.voided = false and r.concept_id = g.concept_id"
		        + " left join concept rc on rc.concept_id = r.value_coded"
		        + " left join obs s on s.obs_group_id = g.obs_id and s.voided = false and s.concept_id = "
		        + concept(admin, GP_DATE_STARTED)
		        + " left join obs x on x.obs_group_id = g.obs_id and x.voided = false and x.concept_id = "
		        + concept(admin, GP_DATE_STOPPED) + " where g.voided = false and g.obs_group_id is null and g.concept_id = "
		        + concept(admin, GP_PRESCRIPTION)
		        + " order by g.person_id, e.encounter_datetime, e.encounter_id, g.obs_id, r.obs_id",
		    true);
		Map<Integer, Integer> encounterOf = new HashMap<Integer, Integer>();
		for (List<Object> row : rows) {
			AdherenceReplay.History h = history(histories, row.get(0));
			Integer patientId = ((Number) row.get(0)).intValue();
			int encounterId = ((Number) row.get(2)).intValue();
			LocalDate day = localDate(row.get(1));
			// Rows come in encounter order, so a new encounter on a day replaces an earlier one that day.
			if (!Integer.valueOf(encounterId).equals(encounterOf.get(patientId))) {
				encounterOf.put(patientId, encounterId);
				h.prescriptions.put(day, new AdherenceReplay.Prescriptions());
			}
			AdherenceReplay.Prescriptions p = h.prescriptions.get(day);
			if (row.get(5) == null || row.get(6) != null) {
				continue;
			}
			LocalDate started = localDate(row.get(5));
			if (!p.intervals.containsKey(started)) {
				Integer days = row.get(3) == null ? null : intervals.get(row.get(3).toString());
				p.intervals.put(started, days == null ? 0 : days);
				p.regimens.put(started, row.get(4) == null ? null : ((Number) row.get(4)).intValue());
			}
		}
	}
	
	private void readInjections(AdministrationService admin, Map<Integer, AdherenceReplay.History> histories) {
		List<List<Object>> rows = admin.executeSQL("select o.person_id, o.value_datetime from obs o"
		        + " join encounter e on e.encounter_id = o.encounter_id and e.voided = false"
		        + " where o.voided = false and o.value_datetime is not null and o.concept_id = "
		        + concept(admin, GP_INJECTION_DATE),
		    true);
		for (List<Object> row : rows) {
			history(histories, row.get(0)).injections.add(localDate(row.get(1)));
		}
	}
	
	/** The oral form has no date of its own, so an estimate counts from its encounter's date. */
	private void readEstimates(AdministrationService admin, Map<String, Integer> durations,
	        Map<Integer, AdherenceReplay.History> histories) {
		List<List<Object>> rows = admin.executeSQL("select o.person_id, e.encounter_datetime, o.value_numeric, dc.uuid"
		        + " from obs o join encounter e on e.encounter_id = o.encounter_id and e.voided = false"
		        + " left join obs d on d.encounter_id = o.encounter_id and d.voided = false and d.concept_id = "
		        + concept(admin, GP_DURATION) + " left join concept dc on dc.concept_id = d.value_coded"
		        + " where o.voided = false and o.concept_id = " + concept(admin, GP_ESTIMATE)
		        + " order by o.person_id, e.encounter_datetime, e.encounter_id, o.obs_id",
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
	private void readConsultations(AdministrationService admin, Map<String, Integer> batchIntervals,
	        Map<Integer, AdherenceReplay.History> histories) {
		List<List<Object>> rows = admin.executeSQL(
		    "select o.person_id, o.value_datetime, c.uuid from obs o"
		            + " join encounter e on e.encounter_id = o.encounter_id and e.voided = false"
		            + " left join obs d on d.encounter_id = o.encounter_id and d.voided = false and d.concept_id = "
		            + concept(admin, GP_DATA_ENTRY) + " left join concept c on c.concept_id = d.value_coded"
		            + " where o.voided = false and o.value_datetime is not null and o.concept_id = "
		            + concept(admin, GP_CONSULTATION_DATE) + " order by o.person_id, o.value_datetime, o.obs_id, d.obs_id",
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
	
	private static String sqlValue(Object value) {
		if (value == null) {
			return "null";
		}
		if (value instanceof Number) {
			return value.toString();
		}
		return "'" + value + "'";
	}
}
