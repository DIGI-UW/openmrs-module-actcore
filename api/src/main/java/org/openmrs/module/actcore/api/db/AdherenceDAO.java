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

import java.util.Collection;
import java.util.List;

/**
 * The database access behind {@link org.openmrs.module.actcore.api.AdherenceService}: the SQL that
 * reads the forms the adherence refresh replays, and the bulk delete and insert that rewrites its
 * rows. Concepts are named by uuid, which the caller has checked holds only letters, digits and
 * hyphens, since the queries name them in their text. Each read gives its rows as JDBC returns
 * them, one list of column values per row, and reads every patient when the patient ids are null.
 */
public interface AdherenceDAO {
	
	/**
	 * Reads each prescription group of each consultation: the patient id, the consultation's date (its
	 * Consultation Date, or else its encounter's date), the encounter id, the regimen's uuid and
	 * concept id, and the course's start and stop dates. The rows are ordered by patient, date and
	 * encounter.
	 *
	 * @param prescription the uuid of the prescription group concept
	 * @param consultationDate the uuid of the Consultation Date concept
	 * @param dateStarted the uuid of the concept recording when a course started
	 * @param dateStopped the uuid of the concept recording when a course stopped
	 * @param patientIds the patients to read, or null for every patient
	 * @return the rows; empty when there are none
	 */
	List<List<Object>> getPrescriptions(String prescription, String consultationDate, String dateStarted, String dateStopped,
	        Collection<Integer> patientIds);
	
	/**
	 * Reads each injection date recorded: the patient id and the date.
	 *
	 * @param injectionDate the uuid of the injection date concept
	 * @param patientIds the patients to read, or null for every patient
	 * @return the rows; empty when there are none
	 */
	List<List<Object>> getInjections(String injectionDate, Collection<Integer> patientIds);
	
	/**
	 * Reads each oral adherence form: the patient id, its day (its Date of Adherence Estimate, or else
	 * its encounter's date), the estimate and the uuid of the duration answer. The rows are ordered by
	 * patient, day and encounter.
	 *
	 * @param estimateDate the uuid of the Date of Adherence Estimate concept, or null to date every
	 *            form by its encounter
	 * @param estimate the uuid of the estimate concept
	 * @param duration the uuid of the prescription duration concept
	 * @param patientIds the patients to read, or null for every patient
	 * @return the rows; empty when there are none
	 */
	List<List<Object>> getEstimates(String estimateDate, String estimate, String duration, Collection<Integer> patientIds);
	
	/**
	 * Reads each consultation's date: the patient id, the date and the uuid of the answer recording how
	 * injections are entered. The rows are ordered by patient, date and encounter.
	 *
	 * @param consultationDate the uuid of the Consultation Date concept
	 * @param dataEntry the uuid of the concept recording how injections are entered
	 * @param patientIds the patients to read, or null for every patient
	 * @return the rows; empty when there are none
	 */
	List<List<Object>> getConsultations(String consultationDate, String dataEntry, Collection<Integer> patientIds);
	
	/**
	 * Replaces the adherence rows and injection timings of one patient, or of every patient, with the
	 * rows given, inserting several rows per statement. It runs in the caller's transaction.
	 *
	 * @param patientId the patient whose rows to replace, or null for every patient
	 * @param rows each adherence row's patient id, regimen concept id, injection interval, adherence,
	 *            last dose, next due date and time computed
	 * @param timings each injection's patient id, date and whether it was on time
	 * @param rowsPerInsert the most rows one insert statement writes
	 */
	void replaceRows(Integer patientId, List<Object[]> rows, List<Object[]> timings, int rowsPerInsert);
}
