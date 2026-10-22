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

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
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
 * Who opened which patient's record, from the audit log module's read audit, with a user's reads of
 * one patient within an hour of the first folded into one row.
 */
public class ReadLog {
	
	/** The audit log module's. */
	public static final String VIEW_PRIVILEGE = "View Read Audit Logs";
	
	public static final long WINDOW_MILLIS = 60 * 60 * 1000L;
	
	static final int PATIENT_MATCHES = 100;
	
	/** The most reads one request folds, so a broad filter answers 400 rather than filling the heap. */
	static final int MAX_READS = 50000;
	
	/**
	 * Entities whose reads are of patients' data even with no patient found, such as a search; the
	 * query leaves out other reads of no patient, such as of encounter types.
	 */
	private static final Set<String> PATIENT_DATA = new HashSet<String>(Arrays.asList("Patient", "Person", "Encounter",
	    "Obs", "Visit", "PatientProgram", "PatientState", "Condition", "Diagnosis", "Order", "DrugOrder", "TestOrder",
	    "Allergy", "Relationship", "Cohort", "CohortMembership", "MedicationDispense", "PersonName", "PersonAddress",
	    "PersonAttribute", "PatientIdentifier", "UnknownEntity"));
	
	public static class Filter {
		
		public String username;
		
		/** A patient uuid, or an ACT ID or name to search for. */
		public String patient;
		
		public LocalDate from;
		
		public LocalDate to;
		
		public int startIndex;
		
		public int limit = Rows.DEFAULT_LIMIT;
	}
	
	public static class Page {
		
		public final List<Row> results = new ArrayList<Row>();
		
		public int totalCount;
	}
	
	/** One read as the module recorded it. */
	public static class Read {
		
		public Date time;
		
		public String entity;
		
		public String description;
	}
	
	/** A user's reads of one patient, or of one entity of no patient, within an hour of the first. */
	public static class Row {
		
		public String id;
		
		public Date from;
		
		public Date to;
		
		public Names.Who user;
		
		public Subject patient;
		
		public final List<Read> reads = new ArrayList<Read>();
		
		public String sessionId;
		
		public String ip;
		
		public String userAgent;
		
		/** Null when the user has no clinics on their account or the patient has none. */
		public Boolean sameClinicAsUser;
		
		String username;
		
		String userUuid;
		
		String patientUuid;
		
		String patientName;
	}
	
	public static class Subject {
		
		public String uuid;
		
		public String display;
		
		public String actId;
		
		public String primaryCareClinic;
		
		public String cardiacClinic;
	}
	
	private final Names names = new Names();
	
	public Page list(Filter filter) {
		Page page = new Page();
		StringBuilder sql = new StringBuilder("select l.id, l.username, l.user_uuid, l.event_time, l.entity_name,"
		        + " l.session_id, l.ip_address, l.user_agent, l.read_success, count(m.id) as targets,"
		        + " count(distinct m.patient_uuid) as patients, min(m.patient_uuid) as patient_uuid,"
		        + " min(m.patient_name) as patient_name from read_audit_log l"
		        + " left join read_audit_entity_metadata m on m.read_audit_id = l.id where (l.entity_name in ("
		        + Rows.placeholders(PATIENT_DATA) + ") or exists (select 1 from read_audit_entity_metadata p"
		        + " where p.read_audit_id = l.id and p.patient_uuid is not null))");
		List<Object> params = new ArrayList<Object>(PATIENT_DATA);
		if (filter.username != null) {
			sql.append(" and l.username = ?");
			params.add(filter.username);
		}
		if (filter.from != null) {
			sql.append(" and l.event_time >= ?");
			params.add(Timestamp.valueOf(filter.from.atStartOfDay()));
		}
		if (filter.to != null) {
			sql.append(" and l.event_time < ?");
			params.add(Timestamp.valueOf(filter.to.plusDays(1).atStartOfDay()));
		}
		if (StringUtils.isNotBlank(filter.patient)) {
			List<String> uuids = patientUuids(filter.patient.trim());
			if (uuids.isEmpty()) {
				return page;
			}
			sql.append(" and l.id in (select read_audit_id from read_audit_entity_metadata where patient_uuid in (")
			        .append(Rows.placeholders(uuids)).append("))");
			params.addAll(uuids);
		}
		sql.append(" group by l.id, l.username, l.user_uuid, l.event_time, l.entity_name, l.session_id, l.ip_address,"
		        + " l.user_agent, l.read_success order by l.username, l.event_time, l.id limit " + (MAX_READS + 1));
		
		List<Map<String, Object>> reads = Rows.select(sql.toString(), params.toArray());
		if (reads.size() > MAX_READS) {
			throw new Rows.TooMany("More than " + MAX_READS + " reads match; narrow the dates, the user or the patient");
		}
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
	 * Reads ordered by user and time, folded per patient, or per entity for reads of no single patient,
	 * while each falls within the window of its row's first.
	 */
	static List<Row> fold(List<Map<String, Object>> reads) {
		List<Row> rows = new ArrayList<Row>();
		Map<String, Row> open = new HashMap<String, Row>();
		for (Map<String, Object> read : reads) {
			int patients = Rows.integer(read, "patients");
			String username = StringUtils.defaultIfBlank(Rows.string(read, "username"), Rows.string(read, "user_uuid"));
			String patientUuid = patients == 1 ? Rows.string(read, "patient_uuid") : null;
			Date time = Rows.date(read, "event_time");
			String key = username + ":" + (patientUuid == null ? "-:" + Rows.string(read, "entity_name") : patientUuid);
			Row row = open.get(key);
			if (row == null || time.getTime() - row.from.getTime() > WINDOW_MILLIS) {
				row = new Row();
				row.id = username + ":" + (patientUuid == null ? "-" : patientUuid) + ":" + Rows.integer(read, "id");
				row.from = time;
				row.username = username;
				row.userUuid = Rows.string(read, "user_uuid");
				row.patientUuid = patientUuid;
				row.patientName = patientUuid == null ? null : Rows.string(read, "patient_name");
				row.sessionId = Rows.string(read, "session_id");
				row.ip = Rows.string(read, "ip_address");
				row.userAgent = Rows.string(read, "user_agent");
				rows.add(row);
				open.put(key, row);
			}
			row.to = time;
			Read entry = new Read();
			entry.time = time;
			entry.entity = Rows.string(read, "entity_name");
			entry.description = describe(entry.entity, Rows.integer(read, "targets"), patients,
			    Rows.truthy(read, "read_success"));
			row.reads.add(entry);
		}
		return rows;
	}
	
	/** "the patient record", "2 encounters", "a search: 4 patients". */
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
		        + " where a.person_id = ? and a.voided = 0 and t.format = 'org.openmrs.Location'",
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
	 * The given uuid, else the patients with the query as identifier or in a name; by SQL, as the audit
	 * log module records a patient service search as reads of every patient found.
	 */
	private static List<String> patientUuids(String query) {
		List<String> uuids = new ArrayList<String>();
		if (query.matches("[0-9a-fA-F-]{36}")) {
			uuids.add(query);
			return uuids;
		}
		String part = "%" + query.toLowerCase().replaceAll("[!%_]", "!$0") + "%";
		for (Map<String, Object> row : Rows
		        .select("select distinct p.uuid from person p join patient t on t.patient_id = p.person_id and t.voided = 0"
		                + " left join patient_identifier i on i.patient_id = p.person_id and i.voided = 0"
		                + " left join person_name n on n.person_id = p.person_id and n.voided = 0"
		                + " where p.voided = 0 and (i.identifier = ? or lower(n.given_name) like ? escape '!'"
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
