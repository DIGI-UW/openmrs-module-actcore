/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.api;

import java.util.Date;
import java.util.Map;
import java.util.Set;

import org.openmrs.Patient;
import org.openmrs.annotation.Authorized;
import org.openmrs.api.OpenmrsService;
import org.openmrs.module.actcore.ActCoreConstants;
import org.openmrs.module.actcore.ActCorePrivileges;
import org.openmrs.module.actcore.model.RaisedFlags;
import org.openmrs.module.patientflags.Flag;
import org.openmrs.module.patientflags.PatientFlagsConstants;

/**
 * The service that keeps the date each flag was raised for each patient, and the flag's dismissal,
 * and dismisses flags. The patient flag refresh raises and lowers flags through it, the flag lists,
 * the next steps and the raised flags endpoint read through it, and the advice on FlagService reads
 * the dismissed flags through it. ACT Core keeps these in tables of its own because the
 * patientflags module recreates its own rows on every save, so neither the date_created of its row
 * nor a voided row outlives the next save.
 */
public interface RaisedFlagService extends OpenmrsService {
	
	/**
	 * Records the flag as raised for the patient from now, unless it is already recorded as raised or
	 * the patient does not exist.
	 *
	 * @param flagId the id of the flag
	 * @param patientId the id of the patient
	 * @throws org.openmrs.api.APIAuthenticationException when the user lacks
	 *             {@link PatientFlagsConstants#PRIV_TEST_FLAGS}
	 */
	@Authorized(PatientFlagsConstants.PRIV_TEST_FLAGS)
	void raise(Integer flagId, Integer patientId);
	
	/**
	 * Forgets that the flag was raised for the patient, and its dismissal with it. The dismissal stays
	 * in the activity log.
	 *
	 * @param flagId the id of the flag
	 * @param patientId the id of the patient
	 * @throws org.openmrs.api.APIAuthenticationException when the user lacks
	 *             {@link PatientFlagsConstants#PRIV_TEST_FLAGS}
	 */
	@Authorized(PatientFlagsConstants.PRIV_TEST_FLAGS)
	void lower(Integer flagId, Integer patientId);
	
	/**
	 * Forgets that the flag was raised for any patient, and its dismissals with it, as for a flag that
	 * is disabled or retired. The dismissals stay in the activity log.
	 *
	 * @param flagId the id of the flag
	 * @throws org.openmrs.api.APIAuthenticationException when the user lacks
	 *             {@link PatientFlagsConstants#PRIV_TEST_FLAGS}
	 */
	@Authorized(PatientFlagsConstants.PRIV_TEST_FLAGS)
	void lowerAll(Integer flagId);
	
	/**
	 * Gets the patients the flag is recorded as raised for, dismissed or not.
	 *
	 * @param flagId the id of the flag
	 * @return the ids of the patients; empty when the flag is raised for none
	 * @throws org.openmrs.api.APIAuthenticationException when the user holds neither
	 *             {@link PatientFlagsConstants#PRIV_VIEW_PATIENT_FLAGS} nor
	 *             {@link PatientFlagsConstants#PRIV_TEST_FLAGS}
	 */
	@Authorized({ PatientFlagsConstants.PRIV_VIEW_PATIENT_FLAGS, PatientFlagsConstants.PRIV_TEST_FLAGS })
	Set<Integer> getRaisedPatients(Integer flagId);
	
	/**
	 * Gets the patients the flag is raised for and dismissed for.
	 *
	 * @param flagId the id of the flag
	 * @return the ids of the patients; empty when the flag is dismissed for none
	 * @throws org.openmrs.api.APIAuthenticationException when the user holds neither
	 *             {@link PatientFlagsConstants#PRIV_VIEW_PATIENT_FLAGS} nor
	 *             {@link PatientFlagsConstants#PRIV_TEST_FLAGS}
	 */
	@Authorized({ PatientFlagsConstants.PRIV_VIEW_PATIENT_FLAGS, PatientFlagsConstants.PRIV_TEST_FLAGS })
	Set<Integer> getDismissedPatients(Integer flagId);
	
	/**
	 * Tells whether the flag is raised for the patient and dismissed.
	 *
	 * @param flagId the id of the flag
	 * @param patientId the id of the patient
	 * @return true when the flag is recorded as raised for the patient and has been dismissed
	 * @throws org.openmrs.api.APIAuthenticationException when the user holds neither
	 *             {@link PatientFlagsConstants#PRIV_VIEW_PATIENT_FLAGS} nor
	 *             {@link PatientFlagsConstants#PRIV_TEST_FLAGS}
	 */
	@Authorized({ PatientFlagsConstants.PRIV_VIEW_PATIENT_FLAGS, PatientFlagsConstants.PRIV_TEST_FLAGS })
	boolean isDismissed(Integer flagId, Integer patientId);
	
	/**
	 * Gets the flags dismissed for the patient that are still recorded as raised. It needs no privilege
	 * beyond signing in, because the advice on FlagService calls it to filter getPatientFlags and
	 * getFlagsForPatient, which need none.
	 *
	 * @param patientId the id of the patient
	 * @return the ids of the flags dismissed and still raised for the patient; empty when there are
	 *         none
	 * @throws org.openmrs.api.APIAuthenticationException when no user is signed in
	 */
	@Authorized
	Set<Integer> getDismissedFlags(Integer patientId);
	
	/**
	 * Gets when the flag was raised for each of the patients it is recorded as raised for.
	 *
	 * @param flagId the id of the flag
	 * @return the date the flag was raised for each patient, by patient id; empty when it is raised for
	 *         none
	 * @throws org.openmrs.api.APIAuthenticationException when the user holds neither
	 *             {@link PatientFlagsConstants#PRIV_VIEW_PATIENT_FLAGS} nor
	 *             {@link PatientFlagsConstants#PRIV_TEST_FLAGS}
	 */
	@Authorized({ PatientFlagsConstants.PRIV_VIEW_PATIENT_FLAGS, PatientFlagsConstants.PRIV_TEST_FLAGS })
	Map<Integer, Date> getRaisedSinceByPatient(Integer flagId);
	
	/**
	 * Gets when each of the patient's raised flags was raised.
	 *
	 * @param patientId the id of the patient
	 * @return the date each raised flag was raised, by flag id; empty when none is raised
	 * @throws org.openmrs.api.APIAuthenticationException when the user holds neither
	 *             {@link PatientFlagsConstants#PRIV_VIEW_PATIENT_FLAGS} nor
	 *             {@link PatientFlagsConstants#PRIV_TEST_FLAGS}
	 */
	@Authorized({ PatientFlagsConstants.PRIV_VIEW_PATIENT_FLAGS, PatientFlagsConstants.PRIV_TEST_FLAGS })
	Map<Integer, Date> getRaisedSinceByFlag(Integer patientId);
	
	/**
	 * Tells whether the signed-in user may dismiss this flag. They may when they hold
	 * {@link ActCorePrivileges#DISMISS_FLAGS} and View Patient Flags, and the flag carries the tag that
	 * {@value ActCoreConstants#GP_DISMISS_FLAG_TAG} names, or that property is blank.
	 *
	 * @param flag the flag
	 * @return true when the user may dismiss the flag, whether or not it is raised for anyone
	 * @throws org.openmrs.api.APIAuthenticationException when the user lacks
	 *             {@link PatientFlagsConstants#PRIV_VIEW_PATIENT_FLAGS}
	 */
	@Authorized(PatientFlagsConstants.PRIV_VIEW_PATIENT_FLAGS)
	boolean mayDismiss(Flag flag);
	
	/**
	 * Dismisses the flag for the patient, which hides it wherever ACT reads flags until ACT Core finds
	 * that the flag has stopped matching the patient. A flag can be dismissed only while it matches the
	 * patient, and not while the full refresh runs, which would undo the dismissal. The dismissal is
	 * saved before the patient's place on the flag's list is updated, so a failure to update the list
	 * leaves the dismissal saved.
	 *
	 * @param patient the patient the flag is raised for
	 * @param flag the flag to dismiss
	 * @return DISMISSED, also when it was already dismissed; NOT_DISMISSIBLE when it lacks the dismiss
	 *         tag; REFRESH_RUNNING while the full refresh runs; NOT_RAISED when the patient is voided,
	 *         the flag is disabled or retired, or it does not match the patient now
	 * @throws org.openmrs.api.APIAuthenticationException when the user lacks
	 *             {@link ActCorePrivileges#DISMISS_FLAGS} or
	 *             {@link PatientFlagsConstants#PRIV_VIEW_PATIENT_FLAGS}
	 */
	@Authorized(value = { ActCorePrivileges.DISMISS_FLAGS,
	        PatientFlagsConstants.PRIV_VIEW_PATIENT_FLAGS }, requireAll = true)
	RaisedFlags.Dismissal dismiss(Patient patient, Flag flag);
}
