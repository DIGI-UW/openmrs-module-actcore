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

import java.util.Date;
import java.util.Map;
import java.util.Set;

import org.openmrs.module.actcore.model.FlagDismissal;
import org.openmrs.module.actcore.model.RaisedFlag;

/**
 * The database access behind {@link org.openmrs.module.actcore.api.RaisedFlagService}, which reads,
 * saves, dismisses and deletes the raised flags and saves the dismissals. Its reads leave the
 * session unflushed, since a raised flag changes only by a bulk statement or a save that is flushed
 * at once.
 */
public interface RaisedFlagDAO {
	
	/**
	 * Tells whether the patient exists and the flag is not recorded as raised for them. It reads the
	 * table rather than the session, which can hold a raised flag that a bulk delete has since removed.
	 *
	 * @param flagId the id of the flag
	 * @param patientId the id of the patient
	 * @return true when the patient exists and has no raised flag row for the flag
	 */
	boolean isRaisable(Integer flagId, Integer patientId);
	
	/**
	 * Saves the raised flag, flushes it and takes it out of the session, so that later commits of a
	 * long run do not check it for changes.
	 *
	 * @param raised the raised flag to save
	 */
	void saveRaisedFlag(RaisedFlag raised);
	
	/**
	 * Deletes the flag's raised flag rows, for one patient or for all of them.
	 *
	 * @param flagId the id of the flag
	 * @param patientId the id of the patient, or null for every patient
	 */
	void deleteRaisedFlags(Integer flagId, Integer patientId);
	
	/**
	 * Marks the flag's raised flag row for the patient as dismissed, unless it is already dismissed.
	 *
	 * @param flagId the id of the flag
	 * @param patientId the id of the patient
	 * @param userId the id of the user who dismissed it
	 * @param date when it was dismissed
	 * @return true when a row was marked; false when the row is already dismissed or does not exist
	 */
	boolean dismissRaisedFlag(Integer flagId, Integer patientId, Integer userId, Date date);
	
	/**
	 * Saves a dismissal in the current session.
	 *
	 * @param dismissal the dismissal to save
	 */
	void saveFlagDismissal(FlagDismissal dismissal);
	
	/**
	 * Gets when the flag was raised for each patient it is raised for.
	 *
	 * @param flagId the id of the flag
	 * @return the date it was raised, by patient id; empty when it is raised for none
	 */
	Map<Integer, Date> getRaisedSinceByPatient(Integer flagId);
	
	/**
	 * Gets when each flag raised for the patient was raised.
	 *
	 * @param patientId the id of the patient
	 * @return the date each flag was raised, by flag id; empty when none is raised
	 */
	Map<Integer, Date> getRaisedSinceByFlag(Integer patientId);
	
	/**
	 * Gets the patients the flag is raised for and dismissed for.
	 *
	 * @param flagId the id of the flag
	 * @return the ids of the patients; empty when there are none
	 */
	Set<Integer> getDismissedPatients(Integer flagId);
	
	/**
	 * Gets the flags raised for the patient and dismissed for them.
	 *
	 * @param patientId the id of the patient
	 * @return the ids of the flags; empty when there are none
	 */
	Set<Integer> getDismissedFlags(Integer patientId);
}
