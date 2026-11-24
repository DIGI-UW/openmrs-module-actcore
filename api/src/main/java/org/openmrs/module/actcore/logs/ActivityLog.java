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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.commons.lang3.StringUtils;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.Daemon;
import org.openmrs.module.actcore.ActCoreConstants;
import org.openmrs.module.actcore.api.db.AuditLogDAO;
import org.openmrs.module.actcore.download.FormDownload;

/**
 * The activity log behind the Logs page's Activity tab, which lists what ACT's users changed, one
 * entry per save. It reads the entries from the Envers audit tables and from ACT Core's own record
 * of flag dismissals. It leaves out the saves the daemon user makes, such as Initializer's loads
 * and the scheduled tasks, and the saves that touch only the server's setup.
 */
public class ActivityLog {
	
	/**
	 * The kinds of entry the log tells apart, which a request may filter by. Each entry has one of
	 * them.
	 */
	public static final List<String> KINDS = Collections.unmodifiableList(Arrays.asList("saved", "edited", "voided",
	    "registered", "patientStatus", "user", "role", "clinic", "study", "flag", "other"));
	
	/** The revision types Envers records: 0 for an addition, 1 for a change and 2 for a deletion. */
	static final int ADD = 0;
	
	static final int MOD = 1;
	
	static final int DEL = 2;
	
	/**
	 * The audit tables the log reads, each mapped to the kinds of entry it can give. A revision that
	 * touches none of these tables is left out of the log.
	 */
	private static final Map<String, List<String>> TABLES = new LinkedHashMap<String, List<String>>();
	
	/** The column of each audit table that names the patient, for the tables that have one. */
	private static final Map<String, String> PATIENT_COLUMNS = new HashMap<String, String>();
	
	/**
	 * The columns left out when an entry's fields are listed, because a reader of a change has no use
	 * for them: keys, revision bookkeeping and secrets.
	 */
	private static final Set<String> SKIPPED_COLUMNS = new LinkedHashSet<String>(
	        Arrays.asList("rev", "revtype", "uuid", "date_created", "date_changed", "creator", "changed_by", "voided_by",
	            "date_voided", "retired_by", "date_retired", "password", "salt", "secret_question", "secret_answer",
	            "activation_key", "form_namespace_and_path", "value_coded_name_id", "previous_version", "obs_group_id",
	            "value_modifier", "person_id", "patient_id", "patient_program_id", "encounter_id", "rev_time", "rev_user"));
	
	private static final int BATCH = 500;
	
	/**
	 * The most revisions that a request filtering by kind may classify. A broader request is refused
	 * with {@link Rows.TooMany}, which the endpoint answers with 400, rather than taking minutes.
	 */
	static final int MAX_CLASSIFIED = 10000;
	
	static {
		table("encounter_AUD", "patient_id", "saved", "edited", "voided");
		table("obs_AUD", "person_id", "saved", "edited", "voided");
		table("person_AUD", "person_id", "registered", "patientStatus", "edited", "user", "other");
		table("patient_AUD", "patient_id", "registered", "edited");
		table("person_name_AUD", "person_id", "registered", "edited", "user", "other");
		table("person_address_AUD", "person_id", "registered", "edited", "user", "other");
		table("person_attribute_AUD", "person_id", "registered", "edited", "user", "other");
		table("patient_identifier_AUD", "patient_id", "registered", "edited");
		table("patient_program_AUD", "patient_id", "registered", "study", "patientStatus");
		table("patient_state_AUD", "patient_program_id", "patientStatus", "study");
		table("users_AUD", null, "user");
		table("user_role_AUD", null, "user");
		table("role_AUD", null, "role");
		table("role_privilege_AUD", null, "role");
		table("location_AUD", null, "clinic");
		table("program_AUD", null, "study");
		table("visit_AUD", "patient_id", "other");
	}
	
	private static void table(String name, String patientColumn, String... kinds) {
		TABLES.put(name, Arrays.asList(kinds));
		if (patientColumn != null) {
			PATIENT_COLUMNS.put(name, patientColumn);
		}
	}
	
	/**
	 * The filters that choose which changes to list, and which page of the matching entries to return.
	 * <p>
	 * A filter left null is not applied. {@code from} and {@code to} are both included, each as a whole
	 * day. {@code startIndex} and {@code limit} pick the page, the limit defaulting to
	 * {@link Rows#DEFAULT_LIMIT}.
	 */
	public static class Filter {
		
		/** The username, or the system ID of a user without one, of the user whose changes to list. */
		public String username;
		
		/** The first day whose changes to list. */
		public LocalDate from;
		
		/** The last day whose changes to list. */
		public LocalDate to;
		
		/** The kind of entry to list, one of {@link ActivityLog#KINDS}. */
		public String kind;
		
		/** The uuid of the form whose saves to list. */
		public String formUuid;
		
		/** The uuid of the patient whose changes to list. */
		public String patientUuid;
		
		/** The index, from 0, of the first matching entry to return. */
		public int startIndex;
		
		/** The most entries to return. */
		public int limit = Rows.DEFAULT_LIMIT;
	}
	
	/** One page of the entries a filter matches, with how many entries it matches in all. */
	public static class Page {
		
		/** The entries on the page, newest first. */
		public final List<Entry> results = new ArrayList<Entry>();
		
		/** How many entries the filter matches across every page. */
		public int totalCount;
	}
	
	/**
	 * One entry of the activity log, such as a form saved or a flag dismissed. It tells what was done,
	 * when it was done, which user did it when that is known, and what it was done to.
	 */
	public static class Entry {
		
		/**
		 * The id of the Envers revision the save was recorded under, by which its fields can be listed. It
		 * is null for a flag dismissal, which ACT Core records outside the audit tables.
		 */
		public Integer revisionId;
		
		/** When the save was made. */
		public Date time;
		
		/** The user who made the save, or null when the user is not known. */
		public Names.Who user;
		
		/** The kind of entry, one of {@link ActivityLog#KINDS}. */
		public String kind;
		
		/** What was done, as the page shows it, such as "Saved RHD Consultation Visit". */
		public String action;
		
		/** A short account of what changed, or null when there is nothing to add to the action. */
		public String summary;
		
		/** What the change was made to, or null when the entry names no target. */
		public Target target;
		
		/** The encounter the change was saved on, or null for a change not saved on an encounter. */
		public EncounterRef encounter;
		
		Integer userId;
		
		Integer patientId;
		
		Integer encounterId;
		
		Integer targetUserId;
		
		Integer personId;
		
		String role;
		
		Integer locationId;
		
		Integer programId;
		
		Integer patientProgramId;
		
		Integer stateId;
		
		String voidReason;
		
		boolean patientVoided;
		
		Revision revision;
	}
	
	/**
	 * What an entry's change was made to: a patient, a person who is not a patient, a user, a role, a
	 * clinic or a study.
	 */
	public static class Target {
		
		/** The kind of target: patient, person, user, role, location or program. */
		public String kind;
		
		/** The target's uuid, or null when it is not known, as for a role. */
		public String uuid;
		
		/** The target's name as the page shows it. */
		public String display;
		
		/** The patient's ACT ID for a patient, the username for a user, and null for other targets. */
		public String actId;
	}
	
	/**
	 * The encounter an entry's change was saved on, as it stands now, or as the revision recorded it
	 * once the encounter is purged.
	 */
	public static class EncounterRef {
		
		/** The encounter's uuid. */
		public String uuid;
		
		/** The uuid of the form the encounter was entered through, or null when no form recorded it. */
		public String formUuid;
		
		/** The name users see for the form, or null when no form recorded the encounter. */
		public String formName;
		
		/** The name users see for the encounter's type. */
		public String typeName;
		
		/** The encounter's date and time. */
		public Date visitDate;
	}
	
	/**
	 * One field that a save changed, as an entry's expansion lists it. It gives the field's name, its
	 * value before the save and its value after the save.
	 */
	public static class Field {
		
		/** The field's name as a reader would read it, such as "Visit date". */
		public final String name;
		
		/** The value before the save, or null when there was none. */
		public final String previous;
		
		/** The value after the save, or null when there is none. */
		public final String current;
		
		Field(String name, String previous, String current) {
			this.name = name;
			this.previous = previous;
			this.current = current;
		}
	}
	
	/**
	 * The expansion of one entry: each field the save changed, with its value before and after, and the
	 * reason given when the save voided something.
	 */
	public static class Details {
		
		/** The id of the entry's revision. */
		public int revisionId;
		
		/** The fields the save changed. */
		public final List<Field> fields = new ArrayList<Field>();
		
		/** The reason given for a void, or null when the save voided nothing or gave no reason. */
		public String voidReason;
	}
	
	/** The audit rows of one Envers revision, grouped by the audit table they come from. */
	static class Revision {
		
		final int id;
		
		Date time;
		
		Integer userId;
		
		final Map<String, List<Map<String, Object>>> rows = new HashMap<String, List<Map<String, Object>>>();
		
		Revision(int id) {
			this.id = id;
		}
		
		List<Map<String, Object>> of(String table) {
			List<Map<String, Object>> found = rows.get(table);
			return found == null ? Collections.<Map<String, Object>> emptyList() : found;
		}
		
		Map<String, Object> first(String table, int type) {
			for (Map<String, Object> row : of(table)) {
				if (type(row) == type) {
					return row;
				}
			}
			return null;
		}
	}
	
	private final AuditLogDAO dao;
	
	private final Names names;
	
	/**
	 * Creates a log that reads through the DAO.
	 *
	 * @param dao the DAO
	 */
	public ActivityLog(AuditLogDAO dao) {
		this.dao = dao;
		this.names = new Names(dao);
	}
	
	/**
	 * Lists the changes the filter matches, newest first, including ACT Core's flag dismissals unless
	 * the filter names a form or a kind other than flag. This method does not check a privilege:
	 * {@link org.openmrs.module.actcore.api.AuditLogService} checks
	 * {@link ActCoreConstants#AUDITLOGWEB_VIEW_AUDIT_LOGS} before it calls it.
	 *
	 * @param filter the filters that choose the changes and the page of them to return
	 * @return the page of entries; empty when the filter names a user, patient or form that does not
	 *         exist
	 * @throws Rows.TooMany when a kind filter has more than {@link #MAX_CLASSIFIED} changes to sort
	 */
	public Page list(Filter filter) {
		Page page = new Page();
		Integer userId = null;
		if (filter.username != null) {
			userId = names.userId(filter.username);
			if (userId == null) {
				return page;
			}
		}
		Integer patientId = null;
		if (filter.patientUuid != null) {
			Names.Subject patient = names.patient(filter.patientUuid);
			if (patient == null) {
				return page;
			}
			patientId = patient.id;
		}
		Integer formId = null;
		if (filter.formUuid != null) {
			List<Map<String, Object>> forms = dao.getFormId(filter.formUuid);
			if (forms.isEmpty()) {
				return page;
			}
			formId = Rows.integer(forms.get(0), "form_id");
		}
		
		List<String> candidates = new ArrayList<String>();
		for (Map.Entry<String, List<String>> table : TABLES.entrySet()) {
			boolean fits = (filter.kind == null || table.getValue().contains(filter.kind))
			        && (patientId == null || PATIENT_COLUMNS.containsKey(table.getKey()))
			        && (formId == null || table.getKey().equals("encounter_AUD") || table.getKey().equals("obs_AUD"));
			if (fits) {
				candidates.add(table.getKey());
			}
		}
		List<Entry> entries = new ArrayList<Entry>();
		for (Map<String, Object> row : candidates.isEmpty() ? Collections.<Map<String, Object>> emptyList()
		        : revisions(candidates, userId, filter.from, filter.to, patientId, formId)) {
			Entry entry = new Entry();
			entry.revisionId = Rows.integer(row, "id");
			entry.time = Rows.date(row, "rev_time");
			entry.userId = Rows.integer(row, "rev_user");
			entries.add(entry);
		}
		// A revision found through one kind's tables may be another, so a kind filter classifies all.
		if (filter.kind != null) {
			if (entries.size() > MAX_CLASSIFIED) {
				throw new Rows.TooMany(
				        "More than " + MAX_CLASSIFIED + " changes to sort by action; narrow the dates or the user");
			}
			entries = classified(entries);
			entries.removeIf(entry -> !filter.kind.equals(entry.kind));
		}
		if (formId == null && (filter.kind == null || filter.kind.equals("flag"))) {
			entries.addAll(dismissals(userId, filter.from, filter.to, patientId));
		}
		entries.sort((a, b) -> {
			int byTime = Long.compare(b.time == null ? 0 : b.time.getTime(), a.time == null ? 0 : a.time.getTime());
			if (byTime != 0) {
				return byTime;
			}
			return Integer.compare(b.revisionId == null ? -1 : b.revisionId, a.revisionId == null ? -1 : a.revisionId);
		});
		page.totalCount = entries.size();
		List<Entry> shown = Rows.page(entries, filter.startIndex, filter.limit);
		for (Entry entry : filter.kind == null ? classified(shown) : shown) {
			name(entry);
			page.results.add(entry);
		}
		return page;
	}
	
	/**
	 * Classifies the entries from their revisions' audit rows, which it reads in batches. A flag
	 * dismissal passes through as it is, and an entry whose revision the log leaves out is dropped.
	 */
	private List<Entry> classified(List<Entry> entries) {
		List<Integer> ids = new ArrayList<Integer>();
		for (Entry entry : entries) {
			if (entry.revisionId != null) {
				ids.add(entry.revisionId);
			}
		}
		Map<Integer, Revision> revisions = new HashMap<Integer, Revision>();
		for (int start = 0; start < ids.size(); start += BATCH) {
			List<Integer> batch = ids.subList(start, Math.min(start + BATCH, ids.size()));
			for (String table : TABLES.keySet()) {
				collect(revisions, table, rows(table, batch));
			}
		}
		List<Entry> result = new ArrayList<Entry>();
		for (Entry entry : entries) {
			if (entry.revisionId == null) {
				result.add(entry);
				continue;
			}
			Revision revision = revisions.get(entry.revisionId);
			Entry classified = revision == null ? null : classify(revision);
			if (classified != null) {
				result.add(classified);
			}
		}
		return result;
	}
	
	/**
	 * Gets the expansion of one revision's entry: the fields its save changed and the reason given for
	 * a void.
	 *
	 * @param revisionId the revision's id
	 * @return the entry's fields and void reason; null when no audited table has the revision or the
	 *         log leaves it out
	 */
	public Details details(int revisionId) {
		Map<Integer, Revision> revisions = new HashMap<Integer, Revision>();
		for (String table : TABLES.keySet()) {
			collect(revisions, table, rows(table, Collections.singletonList(revisionId)));
		}
		Revision revision = revisions.get(revisionId);
		Entry entry = revision == null ? null : classify(revision);
		if (entry == null) {
			return null;
		}
		Details details = new Details();
		details.revisionId = revisionId;
		details.voidReason = entry.voidReason;
		details.fields.addAll(fields(entry));
		return details;
	}
	
	private static void collect(Map<Integer, Revision> revisions, String table, List<Map<String, Object>> rows) {
		for (Map<String, Object> row : rows) {
			int id = Rows.integer(row, "REV");
			Revision revision = revisions.get(id);
			if (revision == null) {
				revision = new Revision(id);
				revision.time = Rows.date(row, "rev_time");
				revision.userId = Rows.integer(row, "rev_user");
				revisions.put(id, revision);
			}
			List<Map<String, Object>> found = revision.rows.get(table);
			if (found == null) {
				found = new ArrayList<Map<String, Object>>();
				revision.rows.put(table, found);
			}
			found.add(row);
		}
	}
	
	/**
	 * Finds the revisions that touch any of the tables, newest first, applying the user, date, patient
	 * and form filters in the query. It leaves out the daemon user's revisions, such as Initializer's
	 * loads and the scheduled tasks, since no user made them.
	 */
	private List<Map<String, Object>> revisions(List<String> tables, Integer userId, LocalDate from, LocalDate to,
	        Integer patientId, Integer formId) {
		Map<String, String> patientColumns = new LinkedHashMap<String, String>();
		for (String table : tables) {
			patientColumns.put(table, PATIENT_COLUMNS.get(table));
		}
		return dao.getRevisions(patientColumns, userId, daemonUserId(), from, to, patientId, formId);
	}
	
	private List<Map<String, Object>> rows(String table, List<Integer> revisionIds) {
		return dao.getRevisionRows(table, revisionIds);
	}
	
	private List<Entry> dismissals(Integer userId, LocalDate from, LocalDate to, Integer patientId) {
		List<Entry> entries = new ArrayList<Entry>();
		for (Map<String, Object> row : dao.getFlagDismissals(userId, from, to, patientId)) {
			Entry entry = new Entry();
			entry.time = Rows.date(row, "date_dismissed");
			entry.userId = Rows.integer(row, "dismissed_by");
			entry.patientId = Rows.integer(row, "patient_id");
			entry.kind = "flag";
			entry.action = "Dismissed flag";
			entry.summary = Rows.string(row, "name");
			entries.add(entry);
		}
		return entries;
	}
	
	/**
	 * Works out from the revision's rows what kind of entry it is, returning null for a revision the
	 * log leaves out. The entry is named later, and only when it lands on the page returned.
	 */
	Entry classify(Revision revision) {
		Entry entry = new Entry();
		entry.revision = revision;
		entry.revisionId = revision.id;
		entry.time = revision.time;
		entry.userId = revision.userId;
		
		List<Map<String, Object>> encounters = revision.of("encounter_AUD");
		List<Map<String, Object>> obs = revision.of("obs_AUD");
		if (!encounters.isEmpty() || !obs.isEmpty()) {
			Map<String, Object> encounter = encounters.isEmpty() ? null : encounters.get(0);
			entry.encounterId = Rows.integer(encounter != null ? encounter : obs.get(0), "encounter_id");
			entry.patientId = encounter != null ? Rows.integer(encounter, "patient_id")
			        : Rows.integer(obs.get(0), "person_id");
			if (encounter != null && type(encounter) == ADD) {
				entry.kind = "saved";
			} else if (encounter != null && became(encounter, "encounter_AUD", "encounter_id", "voided", revision.id)) {
				entry.kind = "voided";
				entry.voidReason = Rows.string(encounter, "void_reason");
			} else {
				entry.kind = "edited";
			}
			return entry;
		}
		
		Map<String, Object> newPerson = revision.first("person_AUD", ADD);
		if (newPerson != null && !revision.of("patient_AUD").isEmpty()) {
			entry.kind = "registered";
			entry.patientId = Rows.integer(newPerson, "person_id");
			return entry;
		}
		for (Map<String, Object> person : revision.of("person_AUD")) {
			if (type(person) == MOD && became(person, "person_AUD", "person_id", "dead", revision.id)
			        && isPatient(Rows.integer(person, "person_id"))) {
				entry.kind = "patientStatus";
				entry.patientId = Rows.integer(person, "person_id");
				return entry;
			}
		}
		List<Map<String, Object>> states = revision.of("patient_state_AUD");
		if (!states.isEmpty()) {
			Map<String, Object> state = states.get(0);
			entry.patientProgramId = Rows.integer(state, "patient_program_id");
			entry.stateId = Rows.integer(state, "state");
			Map<String, Object> enrolment = names.row("patient_program", "patient_program_id", entry.patientProgramId);
			entry.patientId = enrolment == null ? null : Rows.integer(enrolment, "patient_id");
			entry.programId = enrolment == null ? null : Rows.integer(enrolment, "program_id");
			entry.kind = isRegistry(entry.programId) ? "patientStatus" : "study";
			return entry;
		}
		List<Map<String, Object>> enrolments = revision.of("patient_program_AUD");
		if (!enrolments.isEmpty()) {
			Map<String, Object> enrolment = enrolments.get(0);
			entry.patientProgramId = Rows.integer(enrolment, "patient_program_id");
			entry.patientId = Rows.integer(enrolment, "patient_id");
			entry.programId = Rows.integer(enrolment, "program_id");
			entry.kind = isRegistry(entry.programId) ? "patientStatus" : "study";
			return entry;
		}
		
		Integer personId = personChanged(revision);
		if (personId != null && isPatient(personId)) {
			entry.kind = "edited";
			entry.patientId = personId;
			// Not patient_AUD: it has no REVTYPE, so became() would read every voided row as the void.
			Map<String, Object> voided = voiding(revision.first("person_AUD", MOD), "person_AUD", "person_id", revision.id);
			if (voided != null) {
				entry.patientVoided = true;
				entry.voidReason = Rows.string(voided, "void_reason");
			}
			return entry;
		}
		List<Map<String, Object>> users = revision.of("users_AUD");
		List<Map<String, Object>> userRoles = revision.of("user_role_AUD");
		if (!users.isEmpty() || !userRoles.isEmpty()) {
			entry.kind = "user";
			entry.targetUserId = Rows.integer(users.isEmpty() ? userRoles.get(0) : users.get(0), "user_id");
			return entry;
		}
		if (personId != null) {
			List<Map<String, Object>> owners = dao.getUserIdsByPerson(personId);
			if (owners.isEmpty()) {
				entry.kind = "other";
				entry.personId = personId;
				return entry;
			}
			entry.kind = "user";
			entry.targetUserId = Rows.integer(owners.get(0), "user_id");
			return entry;
		}
		List<Map<String, Object>> roles = revision.of("role_AUD");
		List<Map<String, Object>> privileges = revision.of("role_privilege_AUD");
		if (!roles.isEmpty() || !privileges.isEmpty()) {
			entry.kind = "role";
			entry.role = Rows.string(roles.isEmpty() ? privileges.get(0) : roles.get(0), "role");
			return entry;
		}
		if (!revision.of("location_AUD").isEmpty()) {
			entry.kind = "clinic";
			entry.locationId = Rows.integer(revision.of("location_AUD").get(0), "location_id");
			return entry;
		}
		if (!revision.of("program_AUD").isEmpty()) {
			entry.kind = "study";
			entry.programId = Rows.integer(revision.of("program_AUD").get(0), "program_id");
			return entry;
		}
		if (!revision.of("visit_AUD").isEmpty()) {
			entry.kind = "other";
			entry.patientId = Rows.integer(revision.of("visit_AUD").get(0), "patient_id");
			return entry;
		}
		return null;
	}
	
	/**
	 * Tells whether the revision set the row's flag, such as voided: the flag is set in this row and
	 * was not set in the entity's previous revision. A later edit of an entity already flagged does not
	 * count.
	 */
	private boolean became(Map<String, Object> row, String table, String idColumn, String flag, int revisionId) {
		if (!Rows.truthy(row, flag)) {
			return false;
		}
		Map<String, Object> before = type(row) == ADD ? null : previous(table, idColumn, row.get(idColumn), revisionId);
		return before == null || !Rows.truthy(before, flag);
	}
	
	/** Returns the row when the revision voids the row's entity, and null otherwise. */
	private Map<String, Object> voiding(Map<String, Object> row, String table, String idColumn, int revisionId) {
		return row != null && became(row, table, idColumn, "voided", revisionId) ? row : null;
	}
	
	/**
	 * Tells whether the row's column holds a value where the entity's previous revision had none.
	 */
	private boolean gained(Map<String, Object> row, String table, String idColumn, String column, int revisionId) {
		if (row.get(column) == null) {
			return false;
		}
		Map<String, Object> before = type(row) == ADD ? null : previous(table, idColumn, row.get(idColumn), revisionId);
		return before == null || before.get(column) == null;
	}
	
	/**
	 * Names the entry for the page, filling in its user, its action and summary, its target and, for a
	 * change saved on an encounter, the encounter.
	 */
	private void name(Entry entry) {
		entry.user = names.user(entry.userId);
		Revision revision = entry.revision;
		switch (entry.kind) {
			case "saved":
			case "edited":
			case "voided": {
				entry.target = patient(entry.patientId, revision);
				if (entry.encounterId == null && entry.patientVoided) {
					entry.action = "Voided the patient";
					entry.summary = StringUtils.isBlank(entry.voidReason) ? "No reason given"
					        : "Reason: " + entry.voidReason;
					return;
				}
				if (entry.encounterId == null) {
					entry.action = "Edited patient details";
					entry.summary = changed(fields(entry), "Details changed");
					return;
				}
				entry.encounter = encounter(entry);
				// An encounter no form recorded, such as a referral, is named by its type.
				String form = entry.encounter.formName != null ? entry.encounter.formName
				        : entry.encounter.typeName != null ? entry.encounter.typeName : "a form";
				if (entry.kind.equals("saved")) {
					entry.action = "Saved " + form;
					int set = obsFields(entry).size();
					entry.summary = set == 0 && entry.encounter.formName == null ? null
					        : set == 1 ? "1 field set" : set + " fields set";
				} else if (entry.kind.equals("voided")) {
					entry.action = "Voided " + form;
					entry.summary = StringUtils.isBlank(entry.voidReason) ? "No reason given"
					        : "Reason: " + entry.voidReason;
				} else {
					entry.action = "Edited " + form;
					entry.summary = changed(fields(entry), "Encounter details changed");
				}
				return;
			}
			case "registered":
				entry.target = patient(entry.patientId, revision);
				entry.action = "Registered a patient";
				entry.summary = registrationSummary(entry);
				return;
			case "patientStatus":
			case "study":
				if (entry.patientProgramId == null && entry.patientId == null) {
					Map<String, Object> row = revision.of("program_AUD").get(0);
					entry.action = type(row) == ADD ? "Created study"
					        : Rows.truthy(row, "retired") ? "Retired study" : "Changed study";
					Map<String, Object> program = names.row("program", "program_id", entry.programId);
					entry.target = target("program", program == null ? null : Rows.string(program, "uuid"),
					    program == null ? Rows.string(row, "name") : Rows.string(program, "name"));
					entry.summary = type(row) == ADD ? null : changed(fields(entry), null);
					return;
				}
				entry.target = patient(entry.patientId, revision);
				nameProgramChange(entry);
				return;
			case "flag":
				entry.target = patient(entry.patientId, null);
				return;
			case "user":
				nameUserChange(entry);
				return;
			case "role":
				nameRoleChange(entry);
				return;
			case "clinic": {
				Map<String, Object> row = revision.of("location_AUD").get(0);
				entry.action = type(row) == ADD ? "Created clinic"
				        : Rows.truthy(row, "retired") ? "Retired clinic" : "Changed clinic";
				entry.target = target("location", names.locationUuid(entry.locationId),
				    StringUtils.defaultIfBlank(names.location(entry.locationId), Rows.string(row, "name")));
				entry.summary = type(row) == ADD ? null : changed(fields(entry), null);
				return;
			}
			case "other": {
				if (entry.personId != null) {
					Names.Subject person = names.patient(entry.personId);
					entry.target = person == null ? null : target("person", person.uuid, person.display);
					entry.action = "Edited a person's details";
					entry.summary = changed(fields(entry), "Details changed");
					return;
				}
				Map<String, Object> row = revision.of("visit_AUD").get(0);
				entry.target = patient(entry.patientId, revision);
				entry.action = type(row) == ADD ? "Started a visit"
				        : Rows.truthy(row, "voided") ? "Voided a visit"
				                : row.get("date_stopped") != null ? "Ended a visit" : "Changed a visit";
				String visitType = names.visitTypeName(Rows.integer(row, "visit_type_id"));
				entry.summary = (visitType == null ? "" : visitType + ", ") + Names.date(Rows.date(row, "date_started"));
				return;
			}
			default:
				return;
		}
	}
	
	private void nameProgramChange(Entry entry) {
		Revision revision = entry.revision;
		boolean registry = isRegistry(entry.programId);
		String program = registry ? "the registry" : "study " + StringUtils.defaultString(names.program(entry.programId));
		Map<String, Object> person = revision.first("person_AUD", MOD);
		if (person != null && became(person, "person_AUD", "person_id", "dead", revision.id)) {
			entry.action = "Marked deceased";
			entry.summary = person.get("death_date") == null ? null
			        : "Date of death " + Names.date(Rows.date(person, "death_date"));
			return;
		}
		Map<String, Object> enrolled = revision.first("patient_program_AUD", ADD);
		if (enrolled != null) {
			entry.action = "Enrolled in " + program;
			entry.summary = "From " + Names.date(Rows.date(enrolled, "date_enrolled"));
			return;
		}
		if (!revision.of("patient_state_AUD").isEmpty()) {
			Map<String, Object> state = revision.of("patient_state_AUD").get(0);
			entry.action = (Rows.truthy(state, "voided") ? "Removed status " : "Set status ")
			        + StringUtils.defaultString(names.state(entry.stateId));
			entry.summary = registry ? null : names.program(entry.programId);
			return;
		}
		Map<String, Object> enrolment = revision.of("patient_program_AUD").get(0);
		if (became(enrolment, "patient_program_AUD", "patient_program_id", "voided", revision.id)) {
			entry.action = "Removed enrolment in " + program;
			entry.summary = Rows.string(enrolment, "void_reason");
		} else if (gained(enrolment, "patient_program_AUD", "patient_program_id", "date_completed", revision.id)) {
			entry.action = "Ended enrolment in " + program;
			entry.summary = "On " + Names.date(Rows.date(enrolment, "date_completed"));
		} else {
			entry.action = "Changed enrolment in " + program;
			entry.summary = changed(fields(entry), null);
		}
	}
	
	private void nameUserChange(Entry entry) {
		Revision revision = entry.revision;
		Map<String, Object> user = revision.of("users_AUD").isEmpty() ? null : revision.of("users_AUD").get(0);
		Names.Who who = names.user(entry.targetUserId);
		if (who == null && user != null) {
			who = new Names.Who();
			who.username = Rows.string(user, "username");
			who.display = who.username;
		}
		if (who != null) {
			entry.target = target("user", who.uuid, who.display);
			entry.target.actId = who.username;
		}
		List<String> given = new ArrayList<String>();
		List<String> removed = new ArrayList<String>();
		for (Map<String, Object> role : revision.of("user_role_AUD")) {
			(type(role) == DEL ? removed : given).add(roleName(Rows.string(role, "role")));
		}
		if (user != null && type(user) == ADD) {
			entry.action = "Created user";
			entry.summary = given.isEmpty() ? null : StringUtils.join(given, ", ");
			return;
		}
		if (user != null && type(user) == DEL) {
			entry.action = "Deleted user";
			return;
		}
		if (user != null && type(user) == MOD) {
			Map<String, Object> before = previous("users_AUD", "user_id", entry.targetUserId, revision.id);
			boolean retired = Rows.truthy(user, "retired");
			if (before != null && retired != Rows.truthy(before, "retired")) {
				entry.action = retired ? "Disabled user" : "Enabled user";
				entry.summary = retired ? Rows.string(user, "retire_reason") : null;
				return;
			}
		}
		entry.action = "Changed user";
		List<String> parts = new ArrayList<String>();
		if (!given.isEmpty()) {
			parts.add("Gave " + StringUtils.join(given, ", "));
		}
		if (!removed.isEmpty()) {
			parts.add("Removed " + StringUtils.join(removed, ", "));
		}
		// User properties, such as the Users page's clinics, are outside Envers: nothing to itemise.
		entry.summary = parts.isEmpty() ? changed(fields(entry), "Account settings changed; not itemised")
		        : StringUtils.join(parts, "; ");
	}
	
	private void nameRoleChange(Entry entry) {
		Revision revision = entry.revision;
		entry.target = target("role", null, entry.role);
		Map<String, Object> role = revision.of("role_AUD").isEmpty() ? null : revision.of("role_AUD").get(0);
		if (role != null && type(role) == ADD) {
			entry.action = "Created role " + entry.role;
		} else if (role != null && type(role) == DEL) {
			entry.action = "Deleted role " + entry.role;
		} else {
			entry.action = "Changed role " + entry.role;
		}
		List<String> added = new ArrayList<String>();
		List<String> removed = new ArrayList<String>();
		for (Map<String, Object> privilege : revision.of("role_privilege_AUD")) {
			(type(privilege) == DEL ? removed : added).add(Rows.string(privilege, "privilege"));
		}
		List<String> parts = new ArrayList<String>();
		if (!added.isEmpty()) {
			parts.add("Added " + StringUtils.join(added, ", "));
		}
		if (!removed.isEmpty()) {
			parts.add("Removed " + StringUtils.join(removed, ", "));
		}
		entry.summary = parts.isEmpty() ? (type(role) == ADD ? null : changed(fields(entry), null))
		        : StringUtils.join(parts, "; ");
	}
	
	/**
	 * Summarises a registration as the clinics it recorded and whether it recorded consent, read from
	 * the person attributes saved with it. It is null when it recorded neither.
	 */
	private String registrationSummary(Entry entry) {
		List<String> clinicTypes = FormDownload.uuids(ActCoreConstants.GP_LOGS_CLINIC_ATTRIBUTE_TYPES);
		String consentType = Context.getAdministrationService()
		        .getGlobalProperty(ActCoreConstants.GP_STUDIES_CONSENT_ATTRIBUTE_TYPE);
		String yes = Context.getAdministrationService().getGlobalProperty(ActCoreConstants.GP_STUDIES_YES_CONCEPT);
		List<String> parts = new ArrayList<String>();
		boolean consented = false;
		for (Map<String, Object> attribute : entry.revision.of("person_attribute_AUD")) {
			Map<String, Object> type = names.row("person_attribute_type", "person_attribute_type_id",
			    Rows.integer(attribute, "person_attribute_type_id"));
			String typeUuid = type == null ? null : Rows.string(type, "uuid");
			String value = Rows.string(attribute, "value");
			if (typeUuid == null || StringUtils.isBlank(value) || Rows.truthy(attribute, "voided")) {
				continue;
			}
			if (clinicTypes.contains(typeUuid)) {
				String clinic = names.attributeValue(Rows.integer(attribute, "person_attribute_type_id"), value);
				if (!parts.contains(clinic)) {
					parts.add(clinic);
				}
			} else if (typeUuid.equals(consentType) && yes != null && value.matches("\\d+")) {
				Map<String, Object> concept = names.row("concept", "concept_id", Integer.valueOf(value));
				consented = concept != null && yes.equals(Rows.string(concept, "uuid"));
			}
		}
		if (consented) {
			parts.add("consent recorded");
		}
		return parts.isEmpty() ? null : StringUtils.join(parts, ", ");
	}
	
	/**
	 * Lists the fields of an entry's expansion. They are the same fields its summary counts when it
	 * says how many fields changed.
	 */
	List<Field> fields(Entry entry) {
		Revision revision = entry.revision;
		List<Field> fields = new ArrayList<Field>();
		switch (entry.kind) {
			case "saved":
				return obsFields(entry);
			case "voided":
				return fields;
			case "edited":
				if (entry.encounterId != null) {
					fields.addAll(obsFields(entry));
				}
				for (Map<String, Object> encounter : revision.of("encounter_AUD")) {
					if (type(encounter) == MOD) {
						fields.addAll(diff("encounter_AUD", "encounter_id", encounter, revision.id));
					}
				}
				if (entry.patientId != null) {
					fields.addAll(personFields(entry.patientId, revision));
				}
				return fields;
			case "registered":
				fields.addAll(personFields(entry.patientId, revision));
				for (Map<String, Object> enrolment : revision.of("patient_program_AUD")) {
					fields.add(new Field("Enrolled in", null, names.program(Rows.integer(enrolment, "program_id"))));
				}
				return fields;
			case "patientStatus":
			case "study":
				for (Map<String, Object> person : revision.of("person_AUD")) {
					fields.addAll(diff("person_AUD", "person_id", person, revision.id));
				}
				for (Map<String, Object> state : revision.of("patient_state_AUD")) {
					fields.addAll(diff("patient_state_AUD", "patient_state_id", state, revision.id));
				}
				for (Map<String, Object> enrolment : revision.of("patient_program_AUD")) {
					fields.addAll(diff("patient_program_AUD", "patient_program_id", enrolment, revision.id));
				}
				for (Map<String, Object> program : revision.of("program_AUD")) {
					fields.addAll(diff("program_AUD", "program_id", program, revision.id));
				}
				return fields;
			case "user":
				for (Map<String, Object> user : revision.of("users_AUD")) {
					fields.addAll(diff("users_AUD", "user_id", user, revision.id));
				}
				for (Map<String, Object> role : revision.of("user_role_AUD")) {
					String name = roleName(Rows.string(role, "role"));
					fields.add(type(role) == DEL ? new Field("Role", name, null) : new Field("Role", null, name));
				}
				if (entry.targetUserId != null) {
					Map<String, Object> user = names.row("users", "user_id", entry.targetUserId);
					if (user != null) {
						fields.addAll(personFields(Rows.integer(user, "person_id"), revision));
					}
				}
				return fields;
			case "role":
				for (Map<String, Object> role : revision.of("role_AUD")) {
					fields.addAll(diff("role_AUD", "role", role, revision.id));
				}
				for (Map<String, Object> privilege : revision.of("role_privilege_AUD")) {
					String name = Rows.string(privilege, "privilege");
					fields.add(
					    type(privilege) == DEL ? new Field("Privilege", name, null) : new Field("Privilege", null, name));
				}
				return fields;
			case "clinic":
				for (Map<String, Object> location : revision.of("location_AUD")) {
					fields.addAll(diff("location_AUD", "location_id", location, revision.id));
				}
				return fields;
			case "other":
				for (Map<String, Object> visit : revision.of("visit_AUD")) {
					fields.addAll(diff("visit_AUD", "visit_id", visit, revision.id));
				}
				fields.addAll(personFields(entry.personId, revision));
				return fields;
			default:
				return fields;
		}
	}
	
	/**
	 * Lists a saved form's observations as fields, each named by its question with its answer. For an
	 * edit, it lists what changed, since the platform records an edited answer by voiding the old
	 * observation and adding a new one in the same save.
	 */
	private List<Field> obsFields(Entry entry) {
		Map<Integer, List<String>> added = new LinkedHashMap<Integer, List<String>>();
		Map<Integer, List<String>> removed = new LinkedHashMap<Integer, List<String>>();
		Set<Integer> order = new LinkedHashSet<Integer>();
		for (Map<String, Object> obs : dao.getAuditedObs(entry.revisionId, entry.encounterId)) {
			String value = obsValue(obs);
			if (value == null) {
				continue;
			}
			Integer concept = Rows.integer(obs, "concept_id");
			order.add(concept);
			if (type(obs) == ADD) {
				put(added, concept, value);
			} else if (Rows.truthy(obs, "voided")) {
				put(removed, concept, value);
			} else {
				Map<String, Object> before = previous("obs_AUD", "obs_id", Rows.integer(obs, "obs_id"), entry.revisionId);
				String was = before == null ? null : obsValue(before);
				if (!value.equals(was)) {
					put(added, concept, value);
					if (was != null) {
						put(removed, concept, was);
					}
				}
			}
		}
		List<Field> fields = new ArrayList<Field>();
		for (Integer concept : order) {
			if (sameValues(added.get(concept), removed.get(concept))) {
				continue;
			}
			String current = added.containsKey(concept) ? StringUtils.join(added.get(concept), ", ") : null;
			String previous = removed.containsKey(concept) ? StringUtils.join(removed.get(concept), ", ") : null;
			if (current != null || previous != null) {
				fields.add(new Field(names.concept(concept), previous, current));
			}
		}
		return fields;
	}
	
	/**
	 * Tells whether the answers added and the answers voided are the same, whatever their order. Moving
	 * a form's visit date voids and re-adds every answer unchanged, and a multi-select answer may come
	 * back in another order, which should not count as a change.
	 */
	private static boolean sameValues(List<String> added, List<String> removed) {
		if (added == null || removed == null) {
			return false;
		}
		List<String> now = new ArrayList<String>(added);
		List<String> was = new ArrayList<String>(removed);
		Collections.sort(now);
		Collections.sort(was);
		return now.equals(was);
	}
	
	private static void put(Map<Integer, List<String>> values, Integer concept, String value) {
		List<String> list = values.get(concept);
		if (list == null) {
			list = new ArrayList<String>();
			values.put(concept, list);
		}
		list.add(value);
	}
	
	/**
	 * Gives an observation's answer as the form showed it, or null for an observation without an
	 * answer, such as a group.
	 */
	private String obsValue(Map<String, Object> obs) {
		if (obs.get("value_coded") != null) {
			String name = names.conceptName(Rows.integer(obs, "value_coded_name_id"));
			return name != null ? name : names.concept(Rows.integer(obs, "value_coded"));
		}
		if (obs.get("value_numeric") != null) {
			return Names.number((Number) obs.get("value_numeric"));
		}
		if (obs.get("value_text") != null) {
			return Rows.string(obs, "value_text");
		}
		if (obs.get("value_datetime") != null) {
			return Names.dateOrTime(Rows.date(obs, "value_datetime"));
		}
		if (obs.get("value_drug") != null) {
			Map<String, Object> drug = names.row("drug", "drug_id", Rows.integer(obs, "value_drug"));
			return drug == null ? "Drug " + obs.get("value_drug") : Rows.string(drug, "name");
		}
		if (obs.get("value_complex") != null) {
			return Rows.string(obs, "value_complex");
		}
		return null;
	}
	
	/**
	 * Lists the patient's details that the revision changed, across the person and their names,
	 * addresses, attributes and identifiers.
	 */
	private List<Field> personFields(Integer personId, Revision revision) {
		List<Field> fields = new ArrayList<Field>();
		if (personId == null) {
			return fields;
		}
		for (Map<String, Object> person : revision.of("person_AUD")) {
			if (personId.equals(Rows.integer(person, "person_id"))) {
				fields.addAll(diff("person_AUD", "person_id", person, revision.id));
			}
		}
		for (Map<String, Object> name : revision.of("person_name_AUD")) {
			if (personId.equals(Rows.integer(name, "person_id"))) {
				fields.addAll(diff("person_name_AUD", "person_name_id", name, revision.id));
			}
		}
		for (Map<String, Object> address : revision.of("person_address_AUD")) {
			if (personId.equals(Rows.integer(address, "person_id"))) {
				fields.addAll(diff("person_address_AUD", "person_address_id", address, revision.id));
			}
		}
		for (Map<String, Object> attribute : revision.of("person_attribute_AUD")) {
			if (!personId.equals(Rows.integer(attribute, "person_id"))) {
				continue;
			}
			Integer type = Rows.integer(attribute, "person_attribute_type_id");
			Map<String, Object> before = type(attribute) == ADD ? null
			        : previous("person_attribute_AUD", "person_attribute_id", Rows.integer(attribute, "person_attribute_id"),
			            revision.id);
			String was = before == null ? null : names.attributeValue(type, Rows.string(before, "value"));
			String now = Rows.truthy(attribute, "voided") ? null
			        : names.attributeValue(type, Rows.string(attribute, "value"));
			if (!StringUtils.equals(was, now)) {
				fields.add(new Field(StringUtils.defaultString(names.attributeType(type), "Attribute"), was, now));
			}
		}
		for (Map<String, Object> identifier : revision.of("patient_identifier_AUD")) {
			if (!personId.equals(Rows.integer(identifier, "patient_id"))) {
				continue;
			}
			Integer type = Rows.integer(identifier, "identifier_type");
			Map<String, Object> before = type(identifier) == ADD ? null
			        : previous("patient_identifier_AUD", "patient_identifier_id",
			            Rows.integer(identifier, "patient_identifier_id"), revision.id);
			String was = before == null ? null : Rows.string(before, "identifier");
			String now = Rows.truthy(identifier, "voided") ? null : Rows.string(identifier, "identifier");
			if (!StringUtils.equals(was, now)) {
				fields.add(new Field(StringUtils.defaultString(names.identifierType(type), "Identifier"), was, now));
			}
		}
		return fields;
	}
	
	/**
	 * Lists the columns the row changed from the entity's previous revision, each named as a reader
	 * would read it. A deletion lists none.
	 */
	private List<Field> diff(String table, String idColumn, Map<String, Object> row, int revisionId) {
		Map<String, Object> before = type(row) == ADD ? null : previous(table, idColumn, row.get(idColumn), revisionId);
		List<Field> fields = new ArrayList<Field>();
		if (type(row) == DEL) {
			return fields;
		}
		for (Map.Entry<String, Object> column : new TreeMap<String, Object>(row).entrySet()) {
			String name = column.getKey().toLowerCase();
			if (SKIPPED_COLUMNS.contains(name) || name.equals(idColumn.toLowerCase())) {
				continue;
			}
			if (before == null && (column.getValue() == null || Boolean.FALSE.equals(column.getValue()))) {
				continue;
			}
			String now = display(name, column.getValue());
			String was = before == null ? null : display(name, before.get(column.getKey()));
			if (!StringUtils.equals(was, now)) {
				fields.add(new Field(label(name), was, now));
			}
		}
		return fields;
	}
	
	/**
	 * Gets the entity's audit row as it stood before the revision, or null when the revision is the
	 * first that recorded the entity.
	 */
	private Map<String, Object> previous(String table, String idColumn, Object id, int revisionId) {
		List<Map<String, Object>> rows = dao.getPreviousAuditRows(table, idColumn, id, revisionId);
		return rows.isEmpty() ? null : rows.get(0);
	}
	
	private String display(String column, Object value) {
		if (value == null) {
			return null;
		}
		switch (column) {
			case "location_id":
			case "parent_location":
				return names.location(((Number) value).intValue());
			case "concept_id":
			case "value_coded":
			case "outcome_concept_id":
			case "outcomes_concept_id":
			case "cause_of_death":
			case "indication_concept_id":
			case "location_type_concept_id":
				return names.concept(((Number) value).intValue());
			case "state":
				return names.state(((Number) value).intValue());
			case "program_id":
				return names.program(((Number) value).intValue());
			case "form_id": {
				Map<String, Object> form = names.form(((Number) value).intValue());
				return form == null ? "Form " + value : Names.shown("Form", form);
			}
			case "encounter_type": {
				String type = names.encounterTypeName(((Number) value).intValue());
				return type == null ? value.toString() : type;
			}
			case "visit_type_id": {
				String type = names.visitTypeName(((Number) value).intValue());
				return type == null ? value.toString() : type;
			}
			case "visit_id":
				return "Visit " + value;
			default:
				break;
		}
		if (value instanceof Boolean) {
			return (Boolean) value ? "Yes" : "No";
		}
		if (value instanceof Date) {
			return Names.dateOrTime((Date) value);
		}
		if (value instanceof Number && !(value instanceof Integer) && !(value instanceof Long)) {
			return Names.number((Number) value);
		}
		return value.toString();
	}
	
	/**
	 * Turns a column's name into a label a reader would understand. Most columns are capitalised with
	 * their underscores and any _id taken out, so "date_enrolled" reads "Date enrolled", while a few
	 * have a label of their own, such as "Visit date" for "encounter_datetime".
	 */
	static String label(String column) {
		switch (column) {
			case "encounter_datetime":
				return "Visit date";
			case "birthdate":
				return "Birth date";
			case "birthdate_estimated":
				return "Birth date estimated";
			case "deathdate_estimated":
				return "Death date estimated";
			case "dead":
				return "Deceased";
			case "city_village":
				return "City or village";
			case "county_district":
				return "County or district";
			case "state_province":
				return "State or province";
			default:
				return StringUtils.capitalize(column.replace("_id", "").replace('_', ' '));
		}
	}
	
	private static String changed(List<Field> fields, String none) {
		if (fields.isEmpty()) {
			return none;
		}
		List<String> labels = new ArrayList<String>();
		for (Field field : fields) {
			if (!labels.contains(field.name)) {
				labels.add(field.name);
			}
		}
		return (fields.size() == 1 ? "1 field changed: " : fields.size() + " fields changed: ")
		        + StringUtils.join(labels, ", ");
	}
	
	/**
	 * Shortens an organizational role's name as the Users page shows roles, so that "Organizational:
	 * ACT Clinician" reads "Clinician". Any other role keeps its name.
	 */
	private static String roleName(String role) {
		return role.startsWith("Organizational: ")
		        ? StringUtils.removeStart(role.substring("Organizational: ".length()), "ACT ")
		        : role;
	}
	
	/**
	 * Gets the patient as a target, by their name and ACT ID as they are now, or as the audit rows up
	 * to the revision had them once the patient is purged. It is null for a null id.
	 */
	private Target patient(Integer patientId, Revision revision) {
		Names.Subject subject = names.patient(patientId);
		Target target = new Target();
		target.kind = "patient";
		if (subject != null) {
			target.uuid = subject.uuid;
			target.display = subject.display;
			target.actId = subject.actId;
			return target;
		}
		if (patientId == null) {
			return null;
		}
		target.display = StringUtils.defaultIfBlank(
		    Names.personName(dao.getAuditedPersonNames(patientId, revision == null ? Integer.MAX_VALUE : revision.id)),
		    "Patient " + patientId);
		List<Map<String, Object>> identifiers = dao.getAuditedIdentifiers(patientId,
		    revision == null ? Integer.MAX_VALUE : revision.id);
		target.actId = identifiers.isEmpty() ? null : Rows.string(identifiers.get(0), "identifier");
		return target;
	}
	
	/**
	 * Gets the entry's encounter, with its form, as it stands now, or as the revision's row had it once
	 * the encounter is purged.
	 */
	private EncounterRef encounter(Entry entry) {
		EncounterRef ref = new EncounterRef();
		Map<String, Object> row = names.row("encounter", "encounter_id", entry.encounterId);
		if (row == null) {
			row = entry.revision.of("encounter_AUD").isEmpty() ? null : entry.revision.of("encounter_AUD").get(0);
		}
		if (row == null) {
			return ref;
		}
		ref.uuid = Rows.string(row, "uuid");
		ref.visitDate = Rows.date(row, "encounter_datetime");
		ref.typeName = names.encounterTypeName(Rows.integer(row, "encounter_type"));
		Map<String, Object> form = names.form(Rows.integer(row, "form_id"));
		if (form != null) {
			ref.formUuid = Rows.string(form, "uuid");
			ref.formName = Names.shown("Form", form);
		}
		return ref;
	}
	
	private static Target target(String kind, String uuid, String display) {
		Target target = new Target();
		target.kind = kind;
		target.uuid = uuid;
		target.display = display;
		return target;
	}
	
	private static Integer personChanged(Revision revision) {
		for (String table : new String[] { "person_AUD", "person_name_AUD", "person_address_AUD", "person_attribute_AUD",
		        "patient_identifier_AUD", "patient_AUD" }) {
			for (Map<String, Object> row : revision.of(table)) {
				Integer personId = Rows.integer(row, PATIENT_COLUMNS.get(table));
				if (personId != null) {
					return personId;
				}
			}
		}
		return null;
	}
	
	private boolean isPatient(Integer personId) {
		return names.row("patient", "patient_id", personId) != null;
	}
	
	/**
	 * Tells whether the program is the RHD Registry rather than a study, by comparing its uuid with
	 * actcore.registryProgram.
	 */
	private boolean isRegistry(Integer programId) {
		Map<String, Object> program = names.row("program", "program_id", programId);
		String registry = Context.getAdministrationService().getGlobalProperty(ActCoreConstants.GP_REGISTRY_PROGRAM);
		return program != null && registry != null && registry.equals(Rows.string(program, "uuid"));
	}
	
	/**
	 * Gets the daemon user's id, or null when there is no daemon user. The daemon's saves are the
	 * server's own, such as Initializer's loads at startup and the scheduled tasks.
	 */
	private Integer daemonUserId() {
		List<Map<String, Object>> rows = dao.getUserIdByUuid(Daemon.getDaemonUserUuid());
		return rows.isEmpty() ? null : Rows.integer(rows.get(0), "user_id");
	}
	
	static int type(Map<String, Object> row) {
		Integer type = Rows.integer(row, "REVTYPE");
		return type == null ? ADD : type;
	}
	
}
