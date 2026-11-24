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

import java.util.List;

import org.openmrs.Patient;
import org.openmrs.annotation.Authorized;
import org.openmrs.api.OpenmrsService;
import org.openmrs.module.actcore.gap.FlagGap;
import org.openmrs.module.patientflags.Flag;
import org.openmrs.module.patientflags.PatientFlagsConstants;

/**
 * The service that looks up the gaps behind a patient's flag, so the chart can say which questions
 * to answer. It reruns a SQL flag's criteria for the one patient and reads each row they return as
 * a patient_id, the uuid of the encounter missing an answer, and the uuid of the question missing.
 */
public interface FlagGapService extends OpenmrsService {
	
	/**
	 * Gets a flag by uuid. Reading a flag definition normally takes Test Flags, which a clinician who
	 * sees flags on the chart need not hold, so this reads it with that privilege as a proxy.
	 *
	 * @param uuid the flag's uuid
	 * @return the flag, or null when none has the uuid
	 * @throws org.openmrs.api.APIAuthenticationException when the user lacks
	 *             {@link PatientFlagsConstants#PRIV_VIEW_PATIENT_FLAGS}
	 */
	@Authorized(PatientFlagsConstants.PRIV_VIEW_PATIENT_FLAGS)
	Flag getFlag(String uuid);
	
	/**
	 * Finds the gaps behind the flag for the patient, in the order of their encounters' dates. It
	 * returns null when the flag is not a SQL flag whose criteria name a patient_id column, when the
	 * patient is voided, or when the criteria return no row for the patient or rows of fewer than three
	 * columns. A row is left out when either uuid is missing or names nothing, or when its encounter is
	 * voided, belongs to another patient, or is one the user may not view.
	 *
	 * @param patient the patient whose gaps to find
	 * @param flag the flag whose criteria to run
	 * @return the gaps, oldest encounter first; empty when rows come back but each is left out, and
	 *         null in the cases described
	 * @throws org.openmrs.api.APIAuthenticationException when the user lacks
	 *             {@link PatientFlagsConstants#PRIV_VIEW_PATIENT_FLAGS}, or lacks Get Encounters or Get
	 *             Concepts once the criteria return a row with both uuids to look up
	 */
	@Authorized(PatientFlagsConstants.PRIV_VIEW_PATIENT_FLAGS)
	List<FlagGap> getGaps(Patient patient, Flag flag);
}
