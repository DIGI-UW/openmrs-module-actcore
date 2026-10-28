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

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;
import org.openmrs.Concept;
import org.openmrs.ConceptName;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.download.FormDownload;
import org.openmrs.util.PrivilegeConstants;

/**
 * How a log names what it lists, once per request, by SQL rather than the services whose reads the
 * audit log module records, so listing a log does not itself fill the read audit.
 */
public class Names {
	
	/** A user, as the Users page shows one. */
	public static class Who {
		
		public String uuid;
		
		public String username;
		
		public String display;
		
		public String role;
	}
	
	/** A patient, by name and ACT ID. */
	public static class Subject {
		
		public Integer id;
		
		public String uuid;
		
		public String display;
		
		public String actId;
	}
	
	private final Map<String, Object> cache = new HashMap<String, Object>();
	
	private List<String> identifyingTypes;
	
	/**
	 * Users are read by SQL: the site administrator, who reads the logs, is limited by ACT Core's user
	 * administration advice to the users of its clinics, and a log names everyone.
	 */
	public Who user(Integer userId) {
		return userId == null ? null : memo("user:" + userId, key -> who("u.user_id = ?", userId));
	}
	
	public Who user(String uuid) {
		return uuid == null ? null : memo("user:" + uuid, key -> who("u.uuid = ?", uuid));
	}
	
	/**
	 * The id of the user with this username, or with this system ID and no username, as a log names
	 * them; or null.
	 */
	public static Integer userId(String username) {
		List<Map<String, Object>> rows = Rows.select(
		    "select user_id from users where username = ?"
		            + " or ((username is null or username = '') and system_id = ?) order by username desc",
		    username, username);
		return rows.isEmpty() ? null : Rows.integer(rows.get(0), "user_id");
	}
	
	/**
	 * The ACT role is the organizational one, as the roles CSV names it: "Organizational: ACT
	 * Clinician".
	 */
	private static Who who(String where, Object param) {
		List<Map<String, Object>> rows = Rows
		        .select("select u.user_id, u.uuid, u.username, u.system_id, u.person_id from users u where " + where, param);
		if (rows.isEmpty()) {
			return null;
		}
		Map<String, Object> user = rows.get(0);
		Who who = new Who();
		who.uuid = Rows.string(user, "uuid");
		who.username = StringUtils.defaultIfBlank(Rows.string(user, "username"), Rows.string(user, "system_id"));
		who.display = StringUtils
		        .defaultIfBlank(
		            personName(
		                "select given_name, middle_name, family_name from person_name where person_id = ? and voided = 0"
		                        + " order by preferred desc, person_name_id",
		                Rows.integer(user, "person_id")),
		            who.username);
		// The user's own roles before the ones they inherit: a site administrator's role inherits Clinician.
		Integer userId = Rows.integer(user, "user_id");
		who.role = organizationalRole(Rows.select("select role from user_role where user_id = ?", userId));
		if (who.role == null) {
			who.role = organizationalRole(Rows.select("select rr.parent_role as role from role_role rr"
			        + " join user_role ur on ur.role = rr.child_role where ur.user_id = ?",
			    userId));
		}
		return who;
	}
	
	private static String organizationalRole(List<Map<String, Object>> roles) {
		for (Map<String, Object> role : roles) {
			String name = Rows.string(role, "role");
			if (name.startsWith("Organizational: ")) {
				return StringUtils.removeStart(name.substring("Organizational: ".length()), "ACT ");
			}
		}
		return null;
	}
	
	public Subject patient(Integer personId) {
		return personId == null ? null : memo("patient:" + personId, key -> {
			List<Map<String, Object>> rows = Rows.select("select uuid from person where person_id = ?", personId);
			return rows.isEmpty() ? null : subject(personId, Rows.string(rows.get(0), "uuid"));
		});
	}
	
	public Subject patient(String uuid) {
		return uuid == null ? null : memo("patient:" + uuid, key -> {
			List<Map<String, Object>> rows = Rows.select("select person_id from person where uuid = ?", uuid);
			return rows.isEmpty() ? null : subject(Rows.integer(rows.get(0), "person_id"), uuid);
		});
	}
	
	private Subject subject(int personId, String uuid) {
		Subject subject = new Subject();
		subject.id = personId;
		subject.uuid = uuid;
		subject.display = StringUtils.defaultIfBlank(
		    personName("select given_name, middle_name, family_name from person_name where person_id = ? and voided = 0"
		            + " order by preferred desc, person_name_id",
		        personId),
		    uuid);
		subject.actId = actId(personId);
		return subject;
	}
	
	/**
	 * The patient's preferred identifier, unless it identifies them, else the first that does not; as
	 * the download.
	 */
	private String actId(int personId) {
		if (identifyingTypes == null) {
			identifyingTypes = FormDownload.uuids(FormDownload.IDENTIFIER_TYPES_PROPERTY);
		}
		for (Map<String, Object> row : Rows.select(
		    "select i.identifier, t.uuid type_uuid from patient_identifier i"
		            + " join patient_identifier_type t on t.patient_identifier_type_id = i.identifier_type"
		            + " where i.patient_id = ? and i.voided = 0 order by i.preferred desc, i.patient_identifier_id",
		    personId)) {
			if (!identifyingTypes.contains(Rows.string(row, "type_uuid"))) {
				return Rows.string(row, "identifier");
			}
		}
		return null;
	}
	
	/** The full name from a names query's first row, or null. */
	static String personName(String sql, Object... params) {
		List<Map<String, Object>> rows = Rows.select(sql, params);
		if (rows.isEmpty()) {
			return null;
		}
		Map<String, Object> row = rows.get(0);
		StringBuilder name = new StringBuilder();
		for (String part : new String[] { "given_name", "middle_name", "family_name" }) {
			if (StringUtils.isNotBlank(Rows.string(row, part))) {
				name.append(name.length() == 0 ? "" : " ").append(Rows.string(row, part).trim());
			}
		}
		return name.toString();
	}
	
	/**
	 * Concepts, locations and forms are configuration, so the reader needs no privilege to name them.
	 */
	public String concept(Integer conceptId) {
		return conceptId == null ? null
		        : memo("concept:" + conceptId, key -> proxied(PrivilegeConstants.GET_CONCEPTS, () -> {
			        Concept concept = Context.getConceptService().getConcept(conceptId);
			        if (concept == null) {
				        return "Concept " + conceptId;
			        }
			        ConceptName name = concept.getName(Context.getLocale());
			        return name == null ? concept.getDisplayString() : name.getName();
		        }));
	}
	
	public String conceptName(Integer conceptNameId) {
		return conceptNameId == null ? null
		        : memo("conceptName:" + conceptNameId, key -> proxied(PrivilegeConstants.GET_CONCEPTS, () -> {
			        ConceptName name = Context.getConceptService().getConceptName(conceptNameId);
			        return name == null ? null : name.getName();
		        }));
	}
	
	public String location(Integer locationId) {
		Map<String, Object> location = row("location", "location_id", locationId);
		return location == null ? (locationId == null ? null : "Location " + locationId) : Rows.string(location, "name");
	}
	
	public String locationUuid(Integer locationId) {
		Map<String, Object> location = row("location", "location_id", locationId);
		return location == null ? null : Rows.string(location, "uuid");
	}
	
	/** The form's uuid and name, from its row; null once purged. */
	public Map<String, Object> form(Integer formId) {
		return row("form", "form_id", formId);
	}
	
	public String encounterTypeName(Integer typeId) {
		Map<String, Object> type = row("encounter_type", "encounter_type_id", typeId);
		return type == null ? null : Rows.string(type, "name");
	}
	
	public static <T> T proxied(String privilege, Supplier<T> lookup) {
		Context.addProxyPrivilege(privilege);
		try {
			return lookup.get();
		}
		finally {
			Context.removeProxyPrivilege(privilege);
		}
	}
	
	/** A row of a table by its id column, as it stands now; null once purged. */
	public Map<String, Object> row(String table, String idColumn, Integer id) {
		return id == null ? null : memo("row:" + table + ":" + id, key -> {
			List<Map<String, Object>> rows = Rows.select("select * from " + table + " where " + idColumn + " = ?", id);
			return rows.isEmpty() ? null : rows.get(0);
		});
	}
	
	public String program(Integer programId) {
		Map<String, Object> program = row("program", "program_id", programId);
		return program == null ? (programId == null ? null : "Program " + programId) : Rows.string(program, "name");
	}
	
	/** A program workflow state, by its concept's name. */
	public String state(Integer stateId) {
		Map<String, Object> state = row("program_workflow_state", "program_workflow_state_id", stateId);
		return state == null ? null : concept(Rows.integer(state, "concept_id"));
	}
	
	public String attributeType(Integer typeId) {
		Map<String, Object> type = row("person_attribute_type", "person_attribute_type_id", typeId);
		return type == null ? null : Rows.string(type, "name");
	}
	
	/**
	 * A person attribute's value as a reader would see it: a location's or concept's name, else the
	 * text.
	 */
	public String attributeValue(Integer typeId, String value) {
		Map<String, Object> type = row("person_attribute_type", "person_attribute_type_id", typeId);
		String format = type == null ? null : Rows.string(type, "format");
		if (StringUtils.isBlank(value) || format == null || !value.matches("\\d+")) {
			return value;
		}
		if (format.equals("org.openmrs.Location")) {
			return location(Integer.valueOf(value));
		}
		if (format.equals("org.openmrs.Concept")) {
			return concept(Integer.valueOf(value));
		}
		return value;
	}
	
	public String identifierType(Integer typeId) {
		Map<String, Object> type = row("patient_identifier_type", "patient_identifier_type_id", typeId);
		return type == null ? null : Rows.string(type, "name");
	}
	
	public static String date(Date date) {
		return date == null ? null : new SimpleDateFormat("dd-MMM-yyyy").format(date);
	}
	
	public static String dateTime(Date date) {
		return date == null ? null : new SimpleDateFormat("dd-MMM-yyyy HH:mm").format(date);
	}
	
	/** The date alone when the time is midnight, as a date question's answer is stored. */
	public static String dateOrTime(Date date) {
		return date == null ? null
		        : new SimpleDateFormat("HH:mm:ss").format(date).equals("00:00:00") ? date(date) : dateTime(date);
	}
	
	public static String number(Number number) {
		return number == null ? null : BigDecimal.valueOf(number.doubleValue()).stripTrailingZeros().toPlainString();
	}
	
	@SuppressWarnings("unchecked")
	private <T> T memo(String key, Function<String, T> lookup) {
		if (!cache.containsKey(key)) {
			cache.put(key, lookup.apply(key));
		}
		return (T) cache.get(key);
	}
}
