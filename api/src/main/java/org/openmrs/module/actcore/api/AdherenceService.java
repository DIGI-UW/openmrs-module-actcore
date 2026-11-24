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

import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.SortedSet;

import org.openmrs.Patient;
import org.openmrs.annotation.Authorized;
import org.openmrs.api.OpenmrsService;
import org.openmrs.module.actcore.ActCorePrivileges;
import org.openmrs.module.actcore.adherence.AdherenceRefresh;
import org.openmrs.module.actcore.adherence.ProphylaxisSummary;
import org.openmrs.util.PrivilegeConstants;

/**
 * The service that computes patients' prophylaxis adherence from their saved forms. It rewrites the
 * rows of {@value AdherenceRefresh#TABLE} and {@value AdherenceRefresh#TIMING_TABLE}, which the
 * registry and the overdue flag read, for every patient on a schedule and for one patient when a
 * form of theirs is saved. It also computes a patient's prophylaxis summary for the chart, and
 * patients' adherence on past days for the run charts.
 */
public interface AdherenceService extends OpenmrsService {
	
	/**
	 * Recomputes the rows of every patient, then refreshes the patients whose own refresh was left to
	 * this run because it was running. Call it outside a transaction: inside one, the rewrite would
	 * commit only after the run lets go of the rows, and the patients left to it would be refreshed
	 * from a snapshot that does not hold the rewrite yet. The scheduler, and the patient flag refresh
	 * on a daemon thread, call it.
	 *
	 * @param today the day to compute adherence and due dates as of
	 * @return the number of patients written a row; 0 when none has a prescription
	 * @throws org.openmrs.api.APIAuthenticationException when the user lacks
	 *             {@link ActCorePrivileges#REFRESH_FLAGS}
	 */
	@Authorized(ActCorePrivileges.REFRESH_FLAGS)
	int refreshAll(LocalDate today);
	
	/**
	 * Recomputes one patient's rows, so that the registry reflects a saved form at once rather than
	 * after the next full refresh. While a full refresh runs, the patient is left to that run, which
	 * refreshes the patient and their flags once it has finished. It needs no privilege beyond signing
	 * in, because it runs once the save of any of the patient's encounters commits, as whoever saved
	 * it, and it writes only the rows it computes.
	 *
	 * @param patientId the patient to recompute
	 * @param today the day to compute adherence and due dates as of
	 * @throws org.openmrs.api.APIAuthenticationException when no user is signed in
	 */
	@Authorized
	void refreshPatient(int patientId, LocalDate today);
	
	/**
	 * Computes the patient's prophylaxis summary by replaying their saved forms now, as the refresh
	 * does, so that a dose saved today already counts.
	 *
	 * @param patient the patient
	 * @param today the day to compute the summary as of
	 * @return the summary; without a regimen in force its status is none, it is not on hold, and its
	 *         other values are null except the injections, which are still listed
	 * @throws org.openmrs.api.APIAuthenticationException when the user lacks
	 *             {@link PrivilegeConstants#GET_OBS}
	 */
	@Authorized(PrivilegeConstants.GET_OBS)
	ProphylaxisSummary getProphylaxisSummary(Patient patient, LocalDate today);
	
	/**
	 * Gets the adherence each patient held on each of the days. Each patient's history is read and
	 * replayed once for all the days.
	 *
	 * @param patientIds the patients to replay
	 * @param days the days to replay each patient on
	 * @return each patient's adherence by day, keyed by patient id; a day on which the patient held no
	 *         row, or a row without adherence, is absent from that patient's map, a patient with
	 *         nothing recorded that the refresh reads is absent, and the map is empty when either
	 *         argument is empty
	 * @throws org.openmrs.api.APIAuthenticationException when the user lacks
	 *             {@link PrivilegeConstants#GET_OBS}
	 */
	@Authorized(PrivilegeConstants.GET_OBS)
	Map<Integer, Map<LocalDate, Double>> getAdherenceOn(Collection<Integer> patientIds, SortedSet<LocalDate> days);
}
