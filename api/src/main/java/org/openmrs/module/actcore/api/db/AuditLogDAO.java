/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.api.db;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The database access behind {@link org.openmrs.module.actcore.api.AuditLogService}, which reads
 * the audit log module's and Envers' tables by SQL. The logs read users, patients and rows this way
 * rather than through the services whose reads the audit log module records, so that listing a log
 * does not itself fill the read audit.
 * <p>
 * Each method gives its rows as maps that look columns up by label ignoring case, so code reads
 * H2's upper-case names and MariaDB's declared names alike. Date and datetime values come back as
 * java.sql.Date and Timestamp from both databases. A method that names a table or column takes it
 * from ACT Core's own code, never from a caller's input, since names cannot be bound as parameters.
 */
public interface AuditLogDAO {
	
	/**
	 * Gets the user_id of the user with this username, then of a user with no username and this system
	 * ID.
	 *
	 * @param username the username or system ID
	 * @return the matching users' user_id column, the user with the username first; empty when none
	 *         matches
	 */
	List<Map<String, Object>> getUserIdsByUsername(String username);
	
	/**
	 * Gets a user's user_id, uuid, username, system_id and person_id columns by user id.
	 *
	 * @param userId the user's id
	 * @return the user's row; empty when no user has the id
	 */
	List<Map<String, Object>> getUserById(Integer userId);
	
	/**
	 * Gets a user's user_id, uuid, username, system_id and person_id columns by uuid.
	 *
	 * @param uuid the user's uuid
	 * @return the user's row; empty when no user has the uuid
	 */
	List<Map<String, Object>> getUserByUuid(String uuid);
	
	/**
	 * Gets the user_id of the user with this uuid.
	 *
	 * @param uuid the user's uuid
	 * @return the user's user_id column; empty when no user has the uuid
	 */
	List<Map<String, Object>> getUserIdByUuid(String uuid);
	
	/**
	 * Gets the user_id of each user of the person.
	 *
	 * @param personId the person's id
	 * @return each user's user_id column; empty when the person is no user
	 */
	List<Map<String, Object>> getUserIdsByPerson(Integer personId);
	
	/**
	 * Gets the roles the user holds directly, as a role column.
	 *
	 * @param userId the user's id
	 * @return the user's roles; empty when they hold none
	 */
	List<Map<String, Object>> getUserRoles(Integer userId);
	
	/**
	 * Gets the parents of the roles the user holds directly, as a role column.
	 *
	 * @param userId the user's id
	 * @return the roles the user's roles inherit; empty when they inherit none
	 */
	List<Map<String, Object>> getInheritedRoles(Integer userId);
	
	/**
	 * Gets a person's uuid by person id.
	 *
	 * @param personId the person's id
	 * @return the person's uuid column; empty when no person has the id
	 */
	List<Map<String, Object>> getPersonUuid(Integer personId);
	
	/**
	 * Gets a person's person_id by uuid.
	 *
	 * @param uuid the person's uuid
	 * @return the person's person_id column; empty when no person has the uuid
	 */
	List<Map<String, Object>> getPersonId(String uuid);
	
	/**
	 * Gets a person's unvoided names, the preferred one first, as given_name, middle_name and
	 * family_name columns.
	 *
	 * @param personId the person's id
	 * @return the names; empty when the person has no unvoided name
	 */
	List<Map<String, Object>> getUnvoidedPersonNames(Integer personId);
	
	/**
	 * Gets all of a person's names, the unvoided ones first and the preferred one first among each, as
	 * given_name, middle_name and family_name columns.
	 *
	 * @param personId the person's id
	 * @return the names; empty when the person has none
	 */
	List<Map<String, Object>> getPersonNames(Integer personId);
	
	/**
	 * Gets a patient's identifiers with their type's uuid, as identifier and type_uuid columns, the
	 * unvoided ones first and the preferred one first among each.
	 *
	 * @param patientId the patient's id
	 * @return the identifiers; empty when the patient has none
	 */
	List<Map<String, Object>> getPatientIdentifiers(Integer patientId);
	
	/**
	 * Gets a patient's unvoided person attributes whose type holds a location, as value and type_uuid
	 * columns.
	 *
	 * @param personId the patient's person id
	 * @return the attributes; empty when the patient has none
	 */
	List<Map<String, Object>> getLocationAttributes(Integer personId);
	
	/**
	 * Gets a user property's value, as a property_value column.
	 *
	 * @param userUuid the user's uuid
	 * @param property the property's name
	 * @return the property's row; empty when the user does not have it
	 */
	List<Map<String, Object>> getUserProperty(String userUuid, String property);
	
	/**
	 * Gets the form_id of the form with this uuid.
	 *
	 * @param uuid the form's uuid
	 * @return the form's form_id column; empty when no form has the uuid
	 */
	List<Map<String, Object>> getFormId(String uuid);
	
	/**
	 * Gets a row of a table by its id column, as the row stands now.
	 *
	 * @param table the table
	 * @param idColumn the table's id column
	 * @param id the row's id
	 * @return the row, with every column; empty when no row has the id
	 */
	List<Map<String, Object>> getRow(String table, String idColumn, Integer id);
	
	/**
	 * Finds the uuids of unvoided patients who have the query as an unvoided identifier or within an
	 * unvoided given, middle or family name, ignoring case.
	 *
	 * @param query the identifier, or the part of a name
	 * @param limit the most uuids to return
	 * @return the patients' uuid column, at most the limit; empty when none matches
	 */
	List<Map<String, Object>> findPatientUuids(String query, int limit);
	
	/**
	 * Finds the Envers revisions that touch any of the audit tables, newest first, as id, rev_time and
	 * rev_user columns.
	 *
	 * @param patientColumns the audit tables to search, in order, each mapped to its column that names
	 *            the patient; the column is read only when a patient is given, and patient_state_AUD is
	 *            matched through the patient's enrolments instead
	 * @param userId the id of the user whose revisions to find, or null for every user
	 * @param excludedUserId the id of a user whose revisions to leave out, or null to leave none out
	 * @param from the first day whose revisions to find, or null
	 * @param to the last day whose revisions to find, or null
	 * @param patientId the id of the patient whose rows a revision must touch, or null
	 * @param formId the id of the form whose encounters or observations a revision must touch, or null
	 * @return the revisions; empty when none matches
	 */
	List<Map<String, Object>> getRevisions(Map<String, String> patientColumns, Integer userId, Integer excludedUserId,
	        LocalDate from, LocalDate to, Integer patientId, Integer formId);
	
	/**
	 * Gets an audit table's rows of the revisions, each with its revision's rev_time and rev_user.
	 * obs_AUD's rows come counted, one per revision, type, encounter and person, with an n column.
	 *
	 * @param auditTable the audit table
	 * @param revisionIds the revisions' ids; at least one
	 * @return the rows; empty when the table has none of the revisions
	 */
	List<Map<String, Object>> getRevisionRows(String auditTable, List<Integer> revisionIds);
	
	/**
	 * Gets an entity's audit row as it stood before the revision.
	 *
	 * @param auditTable the entity's audit table
	 * @param idColumn the table's column for the entity's id
	 * @param id the entity's id
	 * @param revisionId the revision's id
	 * @return the entity's earlier rows, the latest first; empty when the revision is the first that
	 *         recorded the entity
	 */
	List<Map<String, Object>> getPreviousAuditRows(String auditTable, String idColumn, Object id, int revisionId);
	
	/**
	 * Gets the observations of an encounter that a revision recorded, by obs_id.
	 *
	 * @param revisionId the revision's id
	 * @param encounterId the encounter's id
	 * @return the obs_AUD rows; empty when the revision recorded none for the encounter
	 */
	List<Map<String, Object>> getAuditedObs(Integer revisionId, Integer encounterId);
	
	/**
	 * Gets a person's names as revisions up to this one recorded them, the latest first, as given_name,
	 * middle_name and family_name columns.
	 *
	 * @param personId the person's id
	 * @param revisionId the latest revision to read
	 * @return the names; empty when no revision up to this one recorded a name
	 */
	List<Map<String, Object>> getAuditedPersonNames(Integer personId, int revisionId);
	
	/**
	 * Gets a patient's identifiers as revisions up to this one recorded them, the preferred first and
	 * then the latest first, as an identifier column.
	 *
	 * @param patientId the patient's id
	 * @param revisionId the latest revision to read
	 * @return the identifiers; empty when no revision up to this one recorded one
	 */
	List<Map<String, Object>> getAuditedIdentifiers(Integer patientId, int revisionId);
	
	/**
	 * Gets ACT Core's flag dismissals, the latest first, as patient_id, dismissed_by, date_dismissed
	 * and name columns, the name being the flag's.
	 *
	 * @param userId the id of the user whose dismissals to get, or null for every user
	 * @param from the first day whose dismissals to get, or null
	 * @param to the last day whose dismissals to get, or null
	 * @param patientId the id of the patient whose dismissals to get, or null
	 * @return the dismissals; empty when none matches
	 */
	List<Map<String, Object>> getFlagDismissals(Integer userId, LocalDate from, LocalDate to, Integer patientId);
	
	/**
	 * Gets the lowest and highest id of the read audit's rows, as lowest and highest columns.
	 *
	 * @param from the first day whose reads to count, or null
	 * @param to the last day whose reads to count, or null
	 * @return one row, whose columns are null when no read falls in the days
	 */
	List<Map<String, Object>> getReadIdRange(LocalDate from, LocalDate to);
	
	/**
	 * Gets the reads of patients' data whose ids fall in a span, each with how many entities it found
	 * and how many patients those belong to, and the first patient's uuid and name.
	 *
	 * @param entityNames the entity names whose reads count as reads of patients' data, besides reads
	 *            that recorded a patient
	 * @param username the username of the user whose reads to get, or null for every user
	 * @param from the first day whose reads to get, or null
	 * @param to the last day whose reads to get, or null
	 * @param patientUuids the uuids of the patients one of whom a read must have found, or null
	 * @param after the id the span starts after
	 * @param upTo the last id of the span
	 * @return the reads, one row each; empty when none matches
	 */
	List<Map<String, Object>> getReads(Collection<String> entityNames, String username, LocalDate from, LocalDate to,
	        List<String> patientUuids, long after, long upTo);
}
