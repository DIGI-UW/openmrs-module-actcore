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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
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
	
	/**
	 * The regimen started last by a date, its injection interval, and the latest injection (or, on an
	 * oral regimen, estimate) by then.
	 */
	private static final class Regimen {
		
		final LocalDate latest;
		
		final Integer interval;
		
		LocalDate lastGiven;
		
		Regimen(Prescribed p, TreeSet<LocalDate> given, TreeMap<LocalDate, AdherenceCalculation.OralEntry> estimates,
		    LocalDate onDate) {
			SortedMap<LocalDate, Integer> started = p.intervals.headMap(onDate.plusDays(1));
			latest = started.isEmpty() ? null : started.lastKey();
			interval = latest == null ? null : started.get(latest);
			if (interval != null && interval > 0) {
				SortedSet<LocalDate> past = given.headSet(onDate.plusDays(1));
				lastGiven = past.isEmpty() ? null : past.last();
			} else if (interval != null) {
				SortedMap<LocalDate, AdherenceCalculation.OralEntry> past = estimates.headMap(onDate.plusDays(1));
				lastGiven = past.isEmpty() ? null : past.lastKey();
			}
		}
	}
	
	/** A patient's prescriptions, from their latest encounter that records any. */
	private static final class Prescribed {
		
		int encounterId;
		
		final TreeMap<LocalDate, Integer> intervals = new TreeMap<LocalDate, Integer>();
		
		final Map<LocalDate, Integer> regimens = new HashMap<LocalDate, Integer>();
	}
	
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
		Map<String, Integer> injectionIntervals = daysByUuid(admin, GP_INJECTION_INTERVALS);
		Map<String, Integer> durations = daysByUuid(admin, GP_DURATIONS);
		Map<Integer, Prescribed> prescribed = prescriptions(admin, injectionIntervals);
		Map<Integer, TreeSet<LocalDate>> injections = injections(admin);
		Map<Integer, TreeMap<LocalDate, AdherenceCalculation.OralEntry>> oral = oralEntries(admin, durations);
		Map<Integer, LocalDate> consulted = latestDates(admin, GP_CONSULTATION_DATE, today);
		Map<Integer, Integer> batchDays = batchEntryDays(admin, daysByUuid(admin, GP_DATA_ENTRY_INTERVALS));
		
		Timestamp computedAt = new Timestamp(System.currentTimeMillis());
		List<String> rows = new ArrayList<String>();
		for (Map.Entry<Integer, Prescribed> entry : prescribed.entrySet()) {
			Integer patientId = entry.getKey();
			Prescribed p = entry.getValue();
			TreeSet<LocalDate> given = injections.containsKey(patientId) ? injections.get(patientId)
			        : new TreeSet<LocalDate>();
			TreeMap<LocalDate, AdherenceCalculation.OralEntry> estimates = oral.containsKey(patientId) ? oral.get(patientId)
			        : new TreeMap<LocalDate, AdherenceCalculation.OralEntry>();
			Regimen regimen = new Regimen(p, given, estimates, today);
			LocalDate asOf = today;
			if (regimen.interval != null && regimen.interval > 0) {
				asOf = batchEntered(today, batchDays.get(patientId), consulted.get(patientId), regimen.lastGiven);
			} else if (regimen.interval != null) {
				asOf = lastSaved(today, consulted.get(patientId), regimen.lastGiven);
			}
			// The row, and the no-record rule, describe the regimen its adherence and due date are computed for.
			if (!asOf.equals(today)) {
				regimen = new Regimen(p, given, estimates, asOf);
				// Oral today, but an injection regimen when last saved: ACT 2.0 stored the injection regimen then
				// and went on recomputing it nightly, so the injection rule decides the date.
				if (regimen.interval != null && regimen.interval > 0) {
					asOf = batchEntered(today, batchDays.get(patientId), consulted.get(patientId), regimen.lastGiven);
					regimen = new Regimen(p, given, estimates, asOf);
				}
			}
			AdherenceCalculation.Result result;
			if (regimen.interval != null && (regimen.interval > 0 ? given.isEmpty() : estimates.isEmpty())) {
				// ACT 2.0 gave no adherence or due date to a regimen with no injection, or no estimate, recorded.
				result = new AdherenceCalculation.Result(null, null);
			} else {
				result = AdherenceCalculation.calculate(p.intervals, given, estimates, asOf);
			}
			rows.add("(" + patientId + ", " + sqlValue(regimen.latest == null ? null : p.regimens.get(regimen.latest)) + ", "
			        + sqlValue(regimen.interval) + ", " + sqlValue(result.getAdherence()) + ", "
			        + sqlValue(regimen.lastGiven) + ", " + sqlValue(result.getNextDue()) + ", " + sqlValue(computedAt)
			        + ")");
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
	
	/**
	 * ACT 2.0's nightly run held a batch-entered patient at their last batch until the interval passed;
	 * this computes them as of their latest consultation or injection until then.
	 */
	static LocalDate batchEntered(LocalDate today, Integer batchDays, LocalDate consulted, LocalDate lastGiven) {
		LocalDate seen = later(consulted, lastGiven);
		if (batchDays == null || seen == null || ChronoUnit.DAYS.between(seen, today) >= batchDays) {
			return today;
		}
		return seen;
	}
	
	/**
	 * ACT 2.0 recomputed an oral patient only when a consultation or estimate was saved, so as of the
	 * later.
	 */
	static LocalDate lastSaved(LocalDate today, LocalDate consulted, LocalDate lastEstimate) {
		LocalDate saved = later(consulted, lastEstimate);
		return saved == null ? today : saved;
	}
	
	private static LocalDate later(LocalDate a, LocalDate b) {
		return a == null || b != null && b.isAfter(a) ? b : a;
	}
	
	/**
	 * The prescriptions on each patient's latest encounter that records any, leaving out those with a
	 * date stopped, and keeping the first of each start date, as ACT 2.0 kept them.
	 */
	private Map<Integer, Prescribed> prescriptions(AdministrationService admin, Map<String, Integer> intervals) {
		String prescription = concept(admin, GP_PRESCRIPTION);
		List<List<Object>> rows = admin.executeSQL("select g.person_id, e.encounter_datetime, e.encounter_id,"
		        + " rc.uuid, rc.concept_id, s.value_datetime, x.value_datetime"
		        + " from obs g join encounter e on e.encounter_id = g.encounter_id and e.voided = false"
		        + " left join obs r on r.obs_group_id = g.obs_id and r.voided = false and r.concept_id = g.concept_id"
		        + " left join concept rc on rc.concept_id = r.value_coded"
		        + " left join obs s on s.obs_group_id = g.obs_id and s.voided = false and s.concept_id = "
		        + concept(admin, GP_DATE_STARTED)
		        + " left join obs x on x.obs_group_id = g.obs_id and x.voided = false and x.concept_id = "
		        + concept(admin, GP_DATE_STOPPED) + " where g.voided = false and g.obs_group_id is null and g.concept_id = "
		        + prescription + " order by g.person_id, e.encounter_datetime, e.encounter_id, g.obs_id, r.obs_id",
		    true);
		
		Map<Integer, Prescribed> byPatient = new LinkedHashMap<Integer, Prescribed>();
		for (List<Object> row : rows) {
			Integer patientId = ((Number) row.get(0)).intValue();
			int encounterId = ((Number) row.get(2)).intValue();
			Prescribed p = byPatient.get(patientId);
			if (p == null || p.encounterId != encounterId) {
				// Rows come in encounter order, so a new encounter is a later one.
				p = new Prescribed();
				p.encounterId = encounterId;
				byPatient.put(patientId, p);
			}
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
		// A latest encounter whose prescriptions were all stopped, or had no start date, prescribes none.
		Map<Integer, Prescribed> withPrescriptions = new LinkedHashMap<Integer, Prescribed>();
		for (Map.Entry<Integer, Prescribed> entry : byPatient.entrySet()) {
			if (!entry.getValue().intervals.isEmpty()) {
				withPrescriptions.put(entry.getKey(), entry.getValue());
			}
		}
		return withPrescriptions;
	}
	
	private Map<Integer, TreeSet<LocalDate>> injections(AdministrationService admin) {
		List<List<Object>> rows = admin.executeSQL("select o.person_id, o.value_datetime from obs o"
		        + " join encounter e on e.encounter_id = o.encounter_id and e.voided = false"
		        + " where o.voided = false and o.value_datetime is not null and o.concept_id = "
		        + concept(admin, GP_INJECTION_DATE),
		    true);
		Map<Integer, TreeSet<LocalDate>> byPatient = new HashMap<Integer, TreeSet<LocalDate>>();
		for (List<Object> row : rows) {
			Integer patientId = ((Number) row.get(0)).intValue();
			if (!byPatient.containsKey(patientId)) {
				byPatient.put(patientId, new TreeSet<LocalDate>());
			}
			byPatient.get(patientId).add(localDate(row.get(1)));
		}
		return byPatient;
	}
	
	/** The oral form has no date of its own, so an estimate counts from its encounter's date. */
	private Map<Integer, TreeMap<LocalDate, AdherenceCalculation.OralEntry>> oralEntries(AdministrationService admin,
	        Map<String, Integer> durations) {
		List<List<Object>> rows = admin.executeSQL("select o.person_id, e.encounter_datetime, o.value_numeric, dc.uuid"
		        + " from obs o join encounter e on e.encounter_id = o.encounter_id and e.voided = false"
		        + " left join obs d on d.encounter_id = o.encounter_id and d.voided = false and d.concept_id = "
		        + concept(admin, GP_DURATION) + " left join concept dc on dc.concept_id = d.value_coded"
		        + " where o.voided = false and o.concept_id = " + concept(admin, GP_ESTIMATE)
		        + " order by o.person_id, e.encounter_datetime, e.encounter_id, o.obs_id",
		    true);
		Map<Integer, TreeMap<LocalDate, AdherenceCalculation.OralEntry>> byPatient = new HashMap<Integer, TreeMap<LocalDate, AdherenceCalculation.OralEntry>>();
		for (List<Object> row : rows) {
			Integer patientId = ((Number) row.get(0)).intValue();
			if (!byPatient.containsKey(patientId)) {
				byPatient.put(patientId, new TreeMap<LocalDate, AdherenceCalculation.OralEntry>());
			}
			LocalDate day = localDate(row.get(1));
			if (!byPatient.get(patientId).containsKey(day)) {
				Double estimate = row.get(2) == null ? null : ((Number) row.get(2)).doubleValue();
				Integer days = row.get(3) == null ? null : durations.get(row.get(3).toString());
				byPatient.get(patientId).put(day, new AdherenceCalculation.OralEntry(estimate, days));
			}
		}
		return byPatient;
	}
	
	/**
	 * Each patient's latest value, up to today, of the date the global property names; a future date
	 * does not count.
	 */
	private Map<Integer, LocalDate> latestDates(AdministrationService admin, String property, LocalDate today) {
		List<List<Object>> rows = admin.executeSQL("select o.person_id, o.value_datetime from obs o"
		        + " join encounter e on e.encounter_id = o.encounter_id and e.voided = false"
		        + " where o.voided = false and o.value_datetime is not null and o.concept_id = " + concept(admin, property),
		    true);
		Map<Integer, LocalDate> byPatient = new HashMap<Integer, LocalDate>();
		for (List<Object> row : rows) {
			Integer patientId = ((Number) row.get(0)).intValue();
			LocalDate day = localDate(row.get(1));
			if (!day.isAfter(today) && (!byPatient.containsKey(patientId) || day.isAfter(byPatient.get(patientId)))) {
				byPatient.put(patientId, day);
			}
		}
		return byPatient;
	}
	
	/**
	 * Each patient's batch data entry interval, from the latest encounter that records how they are
	 * entered.
	 */
	private Map<Integer, Integer> batchEntryDays(AdministrationService admin, Map<String, Integer> intervals) {
		List<List<Object>> rows = admin.executeSQL("select o.person_id, c.uuid from obs o"
		        + " join encounter e on e.encounter_id = o.encounter_id and e.voided = false"
		        + " join concept c on c.concept_id = o.value_coded where o.voided = false and o.concept_id = "
		        + concept(admin, GP_DATA_ENTRY) + " order by o.person_id, e.encounter_datetime, e.encounter_id, o.obs_id",
		    true);
		Map<Integer, Integer> byPatient = new HashMap<Integer, Integer>();
		for (List<Object> row : rows) {
			// Later rows replace earlier ones, so each patient ends with their latest; continuous entry has none.
			Integer days = intervals.get(row.get(1).toString());
			if (days == null) {
				byPatient.remove(((Number) row.get(0)).intValue());
			} else {
				byPatient.put(((Number) row.get(0)).intValue(), days);
			}
		}
		return byPatient;
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
