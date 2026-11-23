/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.logs;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.LocalDate;
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
import org.openmrs.module.actcore.clinics.UserClinics;
import org.openmrs.module.actcore.download.FormDownload;

/**
 * The read log behind the Logs page's Patients opened tab, which lists who opened which patient's
 * record. It reads the audit log module's read audit, and folds a user's reads of one patient that
 * fall within an hour of the first of them into one row.
 */
public class ReadLog {
	
	/**
	 * The privilege needed to read the read log, which is the audit log module's privilege for reading
	 * its read audit.
	 */
	public static final String VIEW_PRIVILEGE = "View Read Audit Logs";
	
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
	
	/**
	 * The filters that choose which reads of patient data to list, and which page of the matching rows
	 * to return.
	 * <p>
	 * A filter left null is not applied. {@code patient} matches a patient by uuid, or else matches the
	 * patients with an unvoided identifier equal to it, such as an ACT ID or a National ID, or with a
	 * given, middle or family name containing it. {@code from} and {@code to} are both included, each
	 * as a whole day. {@code startIndex} and {@code limit} pick the page, the limit defaulting to
	 * {@link Rows#DEFAULT_LIMIT}.
	 */
	public static class Filter {
		
		/** The username, as the read audit recorded it, of the user whose reads to list. */
		public String username;
		
		/**
		 * The patient whose reads to list: a patient uuid, or else an identifier to match exactly or part
		 * of a name to search for.
		 */
		public String patient;
		
		/** The first day whose reads to list. */
		public LocalDate from;
		
		/** The last day whose reads to list. */
		public LocalDate to;
		
		/** The index, from 0, of the first matching row to return. */
		public int startIndex;
		
		/** The most rows to return. */
		public int limit = Rows.DEFAULT_LIMIT;
	}
	
	/** One page of the rows a filter matches, with how many rows it matches in all. */
	public static class Page {
		
		/** The rows on the page, the row with the latest first read first. */
		public final List<Row> results = new ArrayList<Row>();
		
		/** How many rows the filter matches across every page. */
		public int totalCount;
	}
	
	/** One read as the audit log module recorded it. */
	public static class Read {
		
		/** When the read happened. */
		public Date time;
		
		/** The name of the entity read, such as Patient or Encounter. */
		public String entity;
		
		/**
		 * What was read, as the page shows it, such as "the patient record", "2 encounters" or "a search: 4
		 * patients", prefixed with "failed: " for a read that failed.
		 */
		public String description;
	}
	
	/**
	 * One row of the read log: a user's reads of one patient, or of one entity when the reads found no
	 * single patient, that fall within {@link ReadLog#WINDOW_MILLIS} of the first of them.
	 */
	public static class Row {
		
		/** An id for the row, made of the user, the patient and the id of the row's first read. */
		public String id;
		
		/** When the row's first read happened. */
		public Date from;
		
		/** When the row's last read happened. */
		public Date to;
		
		/** The user who read. */
		public Names.Who user;
		
		/** The patient read, or null when the reads found no single patient. */
		public Subject patient;
		
		/** The row's reads, oldest first. */
		public final List<Read> reads = new ArrayList<Read>();
		
		/**
		 * A short label for the session the first read was made in, derived from the session id rather than
		 * the id itself, or null when no session was recorded.
		 */
		public String sessionId;
		
		/** The IP address the first read came from. */
		public String ip;
		
		/** The user agent of the browser that made the first read. */
		public String userAgent;
		
		/**
		 * Whether the user and the patient share a clinic. It is null when the reads found no single
		 * patient, when the patient is purged, when the user has no clinics on their account, or when the
		 * patient has none recorded.
		 */
		public Boolean sameClinicAsUser;
		
		String username;
		
		String userUuid;
		
		String patientUuid;
		
		String patientName;
	}
	
	/** The patient a row's reads are of, as the Logs page names them. */
	public static class Subject {
		
		/** The patient's uuid. */
		public String uuid;
		
		/** The patient's name, or the name the read audit recorded once the patient is purged. */
		public String display;
		
		/** The patient's ACT ID, or null when they have none or are purged. */
		public String actId;
		
		/** The name of the patient's primary care clinic, or null when none is recorded. */
		public String primaryCareClinic;
		
		/** The name of the patient's cardiac clinic, or null when none is recorded. */
		public String cardiacClinic;
	}
	
	private final Names names = new Names();
	
	/**
	 * Lists the reads of patients' data that the filter matches, folded into rows, the row with the
	 * latest first read first. This method does not check a privilege, so the caller must check
	 * {@link #VIEW_PRIVILEGE} first.
	 *
	 * @param filter the filters that choose the reads and the page of rows to return
	 * @return the page of rows; empty when no patient matches the filter's patient or no read falls in
	 *         its days
	 * @throws Rows.TooMany when more than {@value #PATIENT_MATCHES} patients match the filter's patient
	 */
	public Page list(Filter filter) {
		Page page = new Page();
		StringBuilder sql = new StringBuilder(" where (l.entity_name in (" + Rows.placeholders(PATIENT_DATA)
		        + ") or exists (select 1 from read_audit_entity_metadata p"
		        + " where p.read_audit_id = l.id and p.patient_uuid is not null))");
		List<Object> params = new ArrayList<Object>(PATIENT_DATA);
		if (filter.username != null) {
			sql.append(" and l.username = ?");
			params.add(filter.username);
		}
		StringBuilder dates = new StringBuilder();
		List<Object> dateParams = new ArrayList<Object>();
		if (filter.from != null) {
			dates.append(" and l.event_time >= ?");
			dateParams.add(Timestamp.valueOf(filter.from.atStartOfDay()));
		}
		if (filter.to != null) {
			dates.append(" and l.event_time < ?");
			dateParams.add(Timestamp.valueOf(filter.to.plusDays(1).atStartOfDay()));
		}
		sql.append(dates);
		params.addAll(dateParams);
		if (StringUtils.isNotBlank(filter.patient)) {
			List<String> uuids = patientUuids(filter.patient.trim());
			if (uuids.isEmpty()) {
				return page;
			}
			sql.append(" and l.id in (select read_audit_id from read_audit_entity_metadata where patient_uuid in (")
			        .append(Rows.placeholders(uuids)).append("))");
			params.addAll(uuids);
		}
		Map<String, Object> ids = Rows
		        .select("select min(l.id) as lowest, max(l.id) as highest from read_audit_log l where 1 = 1" + dates,
		            dateParams.toArray())
		        .get(0);
		if (ids.get("lowest") == null) {
			return page;
		}
		long highest = Rows.integer(ids, "highest");
		String fetch = "select l.id, l.username, l.user_uuid, l.event_time, l.entity_name, l.session_id, l.ip_address,"
		        + " l.user_agent, l.read_success, count(m.id) as targets, count(distinct m.patient_uuid) as patients,"
		        + " min(m.patient_uuid) as patient_uuid, min(m.patient_name) as patient_name from read_audit_log l"
		        + " left join read_audit_entity_metadata m on m.read_audit_id = l.id" + sql + " and l.id > ? and l.id <= ?"
		        + " group by l.id, l.username, l.user_uuid, l.event_time, l.entity_name, l.session_id, l.ip_address,"
		        + " l.user_agent, l.read_success";
		
		List<Raw> reads = new ArrayList<Raw>();
		for (long after = Rows.integer(ids, "lowest") - 1L; after < highest; after += READ_SPAN) {
			List<Object> fetchParams = new ArrayList<Object>(params);
			fetchParams.add(after);
			fetchParams.add(after + READ_SPAN);
			for (Map<String, Object> read : Rows.select(fetch, fetchParams.toArray())) {
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
			Read entry = new Read();
			entry.time = read.time;
			entry.entity = read.entity;
			entry.description = describe(read.entity, read.targets, read.patients, read.success);
			row.reads.add(entry);
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
			row.user = new Names.Who();
			row.user.uuid = row.userUuid;
			row.user.username = row.username;
			row.user.display = row.username;
		}
		if (row.patientUuid == null) {
			return;
		}
		Names.Subject found = names.patient(row.patientUuid);
		row.patient = new Subject();
		row.patient.uuid = row.patientUuid;
		row.patient.display = found == null ? row.patientName : found.display;
		row.patient.actId = found == null ? null : found.actId;
		if (found == null) {
			return;
		}
		List<String> clinicTypes = FormDownload.uuids(ActivityLog.CLINIC_ATTRIBUTE_TYPES_PROPERTY);
		Set<String> patientClinics = new LinkedHashSet<String>();
		for (Map<String, Object> attribute : Rows.select("select a.value, t.uuid as type_uuid from person_attribute a"
		        + " join person_attribute_type t on t.person_attribute_type_id = a.person_attribute_type_id"
		        + " where a.person_id = ? and a.voided = false and t.format = 'org.openmrs.Location'",
		    found.id)) {
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
		List<Map<String, Object>> property = Rows
		        .select("select p.property_value from user_property p join users u on u.user_id = p.user_id"
		                + " where u.uuid = ? and p.property = ?",
		            row.userUuid, UserClinics.PROPERTY);
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
	private static List<String> patientUuids(String query) {
		List<String> uuids = new ArrayList<String>();
		if (query.matches("[0-9a-fA-F-]{36}")) {
			uuids.add(query);
			return uuids;
		}
		String part = "%" + query.toLowerCase().replaceAll("[!%_]", "!$0") + "%";
		for (Map<String, Object> row : Rows.select(
		    "select distinct p.uuid from person p join patient t on t.patient_id = p.person_id and t.voided = false"
		            + " left join patient_identifier i on i.patient_id = p.person_id and i.voided = false"
		            + " left join person_name n on n.person_id = p.person_id and n.voided = false"
		            + " where p.voided = false and (i.identifier = ? or lower(n.given_name) like ? escape '!'"
		            + " or lower(n.middle_name) like ? escape '!' or lower(n.family_name) like ? escape '!') limit "
		            + (PATIENT_MATCHES + 1),
		    query, part, part, part)) {
			uuids.add(Rows.string(row, "uuid"));
		}
		if (uuids.size() > PATIENT_MATCHES) {
			throw new Rows.TooMany("More than " + PATIENT_MATCHES + " patients match; narrow the patient search");
		}
		return uuids;
	}
}
