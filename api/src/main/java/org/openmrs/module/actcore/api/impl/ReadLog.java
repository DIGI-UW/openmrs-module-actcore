/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.api.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.openmrs.module.actcore.ActCoreConstants;
import org.openmrs.module.actcore.api.TooManyResultsException;
import org.openmrs.module.actcore.api.db.AuditLogDAO;
import org.openmrs.module.actcore.api.search.ReadSearchCriteria;
import org.openmrs.module.actcore.model.LogPatient;
import org.openmrs.module.actcore.model.LogUser;
import org.openmrs.module.actcore.model.ReadEvent;
import org.openmrs.module.actcore.model.ReadPage;
import org.openmrs.module.actcore.model.ReadPatient;
import org.openmrs.module.actcore.model.ReadRow;
import org.openmrs.module.actcore.utils.UserClinics;

/**
 * The read log behind the Logs page's Patients opened tab, which lists who opened which patient's
 * record. It reads the audit log module's read audit, and folds a user's reads of one patient that
 * fall within an hour of the first of them into one row.
 */
class ReadLog {
	
	/**
	 * How long after a row's first read, in milliseconds, a later read by the same user of the same
	 * patient still joins that row.
	 */
	public static final long WINDOW_MILLIS = 60 * 60 * 1000L;
	
	static final int PATIENT_MATCHES = 100;
	
	/**
	 * The span of read ids that one query fetches. Past about 200,000 ids, MariaDB scans the whole of
	 * read_audit_entity_metadata for the query's exists clause.
	 */
	static final int READ_SPAN = 10000;
	
	/**
	 * The entities whose reads count as reads of patients' data even when no patient was found, as for
	 * a search. The query leaves out reads of other entities that found no patient, such as reads of
	 * encounter types.
	 */
	private static final Set<String> PATIENT_DATA = new HashSet<String>(Arrays.asList("Patient", "Person", "Encounter",
	    "Obs", "Visit", "PatientProgram", "PatientState", "Condition", "Diagnosis", "Order", "DrugOrder", "TestOrder",
	    "Allergy", "Relationship", "Cohort", "CohortMembership", "MedicationDispense", "PersonName", "PersonAddress",
	    "PersonAttribute", "PatientIdentifier", "UnknownEntity"));
	
	/** A row with what the log reads to name its user and patient, which the page never shows. */
	static class Row extends ReadRow {
		
		String username;
		
		String userUuid;
		
		String patientUuid;
		
		String patientName;
	}
	
	private final AuditLogDAO dao;
	
	private final Names names;
	
	/**
	 * Creates a log that reads through the DAO.
	 *
	 * @param dao the DAO
	 */
	public ReadLog(AuditLogDAO dao) {
		this.dao = dao;
		this.names = new Names(dao);
	}
	
	/**
	 * Lists the reads of patients' data that the filter matches, folded into rows, the row with the
	 * latest first read first. This method does not check a privilege:
	 * {@link org.openmrs.module.actcore.api.AuditLogService} checks
	 * {@link ActCoreConstants#AUDITLOGWEB_VIEW_READ_AUDIT_LOGS} before it calls it.
	 *
	 * @param filter the filters that choose the reads and the page of rows to return
	 * @return the page of rows; empty when no patient matches the filter's patient or no read falls in
	 *         its days
	 * @throws TooManyResultsException when more than {@value #PATIENT_MATCHES} patients match the
	 *             filter's patient
	 */
	public ReadPage list(ReadSearchCriteria filter) {
		ReadPage page = new ReadPage();
		List<String> uuids = null;
		if (StringUtils.isNotBlank(filter.patient)) {
			uuids = patientUuids(filter.patient.trim());
			if (uuids.isEmpty()) {
				return page;
			}
		}
		Map<String, Object> ids = dao.getReadIdRange(filter.from, filter.to).get(0);
		if (ids.get("lowest") == null) {
			return page;
		}
		long highest = Rows.integer(ids, "highest");
		
		List<Raw> reads = new ArrayList<Raw>();
		for (long after = Rows.integer(ids, "lowest") - 1L; after < highest; after += READ_SPAN) {
			for (Map<String, Object> read : dao.getReads(PATIENT_DATA, filter.username, filter.from, filter.to, uuids, after,
			    after + READ_SPAN)) {
				reads.add(new Raw(read));
			}
		}
		reads.sort(
		    Comparator.comparing((Raw read) -> read.username, Comparator.nullsFirst(Comparator.<String> naturalOrder()))
		            .thenComparing(read -> read.time).thenComparingLong(read -> read.id));
		List<Row> rows = fold(reads);
		rows.sort((a, b) -> b.from.compareTo(a.from));
		page.totalCount = rows.size();
		for (Row row : Rows.page(rows, filter.startIndex, filter.limit)) {
			name(row);
			page.results.add(row);
		}
		return page;
	}
	
	/**
	 * Makes a short label for a session from a hash of its id. The id itself is never shown, because
	 * anyone holding it could sign in as the session's user while the session lives.
	 */
	static String sessionLabel(String sessionId) {
		if (StringUtils.isBlank(sessionId)) {
			return null;
		}
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(sessionId.getBytes(StandardCharsets.UTF_8));
			return String.format("%02X%02X%02X%02X", hash[0], hash[1], hash[2], hash[3]);
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
	
	/**
	 * Folds reads, ordered by user and then by time, into rows. A user's reads of one patient share a
	 * row, as do their reads of one entity that found no single patient, as long as each read falls
	 * within {@link #WINDOW_MILLIS} of the row's first read.
	 */
	static List<Row> fold(List<Raw> reads) {
		List<Row> rows = new ArrayList<Row>();
		Map<String, Row> open = new HashMap<String, Row>();
		for (Raw read : reads) {
			String username = StringUtils.defaultIfBlank(read.username, read.userUuid);
			String patientUuid = read.patients == 1 ? read.patientUuid : null;
			String key = username + ":" + (patientUuid == null ? "-:" + read.entity : patientUuid);
			Row row = open.get(key);
			if (row == null || read.time.getTime() - row.from.getTime() > WINDOW_MILLIS) {
				row = new Row();
				row.id = username + ":" + (patientUuid == null ? "-" : patientUuid) + ":" + read.id;
				row.from = read.time;
				row.username = username;
				row.userUuid = read.userUuid;
				row.patientUuid = patientUuid;
				row.patientName = patientUuid == null ? null : read.patientName;
				row.sessionId = read.sessionId;
				row.ip = read.ip;
				row.userAgent = read.userAgent;
				rows.add(row);
				open.put(key, row);
			}
			row.to = read.time;
			ReadEvent event = new ReadEvent();
			event.time = read.time;
			event.entity = read.entity;
			event.description = describe(read.entity, read.targets, read.patients, read.success);
			row.reads.add(event);
		}
		return rows;
	}
	
	/**
	 * One row of the reads query, held as fields rather than as a map, to keep memory down while every
	 * span of reads is held at once.
	 */
	static final class Raw {
		
		final long id;
		
		final String username;
		
		final String userUuid;
		
		final Date time;
		
		final String entity;
		
		final String sessionId;
		
		final String ip;
		
		final String userAgent;
		
		final boolean success;
		
		final int targets;
		
		final int patients;
		
		final String patientUuid;
		
		final String patientName;
		
		Raw(Map<String, Object> read) {
			id = Rows.integer(read, "id");
			username = Rows.string(read, "username");
			userUuid = Rows.string(read, "user_uuid");
			time = Rows.date(read, "event_time");
			entity = Rows.string(read, "entity_name");
			sessionId = sessionLabel(Rows.string(read, "session_id"));
			ip = Rows.string(read, "ip_address");
			userAgent = Rows.string(read, "user_agent");
			success = Rows.truthy(read, "read_success");
			targets = Rows.integer(read, "targets");
			patients = Rows.integer(read, "patients");
			patientUuid = Rows.string(read, "patient_uuid");
			patientName = Rows.string(read, "patient_name");
		}
	}
	
	/**
	 * Describes what a read found as the page shows it, such as "the patient record", "2 encounters" or
	 * "a search: 4 patients", prefixed with "failed: " when the read failed.
	 */
	static String describe(String entity, int targets, int patients, boolean success) {
		String noun = noun(entity);
		String what;
		if (patients > 1) {
			what = "a search: " + patients + " patients";
		} else if (targets == 0) {
			what = "no " + noun + " found";
		} else if (entity.equals("Patient") || entity.equals("Person")) {
			what = targets == 1 ? "the " + noun + " record" : targets + " " + noun + " records";
		} else {
			what = targets == 1 ? "1 " + noun : targets + " " + plural(noun);
		}
		return success ? what : "failed: " + what;
	}
	
	private static String noun(String entity) {
		switch (entity) {
			case "Obs":
				return "observation";
			case "PatientProgram":
				return "enrolment";
			case "PatientState":
				return "status";
			case "Cohort":
				return "list";
			case "CohortMembership":
				return "list membership";
			case "UnknownEntity":
				return "record";
			default:
				return entity.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase();
		}
	}
	
	private static String plural(String noun) {
		if (noun.endsWith("y") && !noun.endsWith("ay")) {
			return noun.substring(0, noun.length() - 1) + "ies";
		}
		return noun.endsWith("s") ? noun : noun + "s";
	}
	
	private void name(Row row) {
		row.user = names.user(row.userUuid);
		if (row.user == null) {
			row.user = new LogUser();
			row.user.uuid = row.userUuid;
			row.user.username = row.username;
			row.user.display = row.username;
		}
		if (row.patientUuid == null) {
			return;
		}
		LogPatient found = names.patient(row.patientUuid);
		row.patient = new ReadPatient();
		row.patient.uuid = row.patientUuid;
		row.patient.display = found == null ? row.patientName : found.display;
		row.patient.actId = found == null ? null : found.actId;
		if (found == null) {
			return;
		}
		List<String> clinicTypes = FormDownload.uuids(ActCoreConstants.GP_LOGS_CLINIC_ATTRIBUTE_TYPES);
		Set<String> patientClinics = new LinkedHashSet<String>();
		for (Map<String, Object> attribute : dao.getLocationAttributes(found.id)) {
			String value = Rows.string(attribute, "value");
			int which = clinicTypes.indexOf(Rows.string(attribute, "type_uuid"));
			if (which < 0 || value == null || !value.matches("\\d+")) {
				continue;
			}
			String clinic = names.location(Integer.valueOf(value));
			patientClinics.add(names.locationUuid(Integer.valueOf(value)));
			if (which == 0) {
				row.patient.primaryCareClinic = clinic;
			} else {
				row.patient.cardiacClinic = clinic;
			}
		}
		patientClinics.remove(null);
		List<Map<String, Object>> property = dao.getUserProperty(row.userUuid, ActCoreConstants.USER_PROPERTY_CLINICS);
		Set<String> userClinics = UserClinics
		        .parse(property.isEmpty() ? null : Rows.string(property.get(0), "property_value"));
		row.sameClinicAsUser = userClinics.isEmpty() || patientClinics.isEmpty() ? null
		        : UserClinics.share(userClinics, patientClinics);
	}
	
	/**
	 * Finds the uuids of the patients a patient filter names: the query itself when it is a uuid, or
	 * else the unvoided patients with the query as an identifier or within a name. It searches by SQL,
	 * because the audit log module would record a search through the patient service as a read of every
	 * patient found.
	 */
	private List<String> patientUuids(String query) {
		List<String> uuids = new ArrayList<String>();
		if (query.matches("[0-9a-fA-F-]{36}")) {
			uuids.add(query);
			return uuids;
		}
		for (Map<String, Object> row : dao.findPatientUuids(query, PATIENT_MATCHES + 1)) {
			uuids.add(Rows.string(row, "uuid"));
		}
		if (uuids.size() > PATIENT_MATCHES) {
			throw new TooManyResultsException("More than " + PATIENT_MATCHES + " patients match; narrow the patient search");
		}
		return uuids;
	}
}
