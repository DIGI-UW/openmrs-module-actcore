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
import java.util.List;

import org.openmrs.Patient;
import org.openmrs.annotation.Authorized;
import org.openmrs.api.OpenmrsService;
import org.openmrs.module.actcore.api.impl.NextConsultation;
import org.openmrs.module.actcore.api.impl.NextSteps;
import org.openmrs.module.actcore.model.NextStep;
import org.openmrs.util.PrivilegeConstants;

/**
 * The service that works out a patient's next steps for the chart, and the date the patient's next
 * cardiology review is due. Both read the patient's records with proxy privileges once the caller
 * has shown they may read the patient's observations.
 */
public interface NextStepsService extends OpenmrsService {
	
	/**
	 * Gets the steps the signed-in user may take for the patient, as
	 * {@link NextSteps#of(Patient, LocalDate)} gives them.
	 *
	 * @param patient the patient
	 * @param today the day the steps are for
	 * @return the steps; empty when no rule gives the user a step
	 * @throws org.openmrs.api.APIAuthenticationException when the user lacks
	 *             {@link PrivilegeConstants#GET_OBS}
	 */
	@Authorized(PrivilegeConstants.GET_OBS)
	List<NextStep> getNextSteps(Patient patient, LocalDate today);
	
	/**
	 * Gets the date the patient's next cardiology review is due, as
	 * {@link NextConsultation#of(Patient)} works it out.
	 *
	 * @param patient the patient
	 * @return the due date; null in the cases {@link NextConsultation#of(Patient)} lists
	 * @throws org.openmrs.api.APIAuthenticationException when the user lacks
	 *             {@link PrivilegeConstants#GET_OBS}
	 */
	@Authorized(PrivilegeConstants.GET_OBS)
	LocalDate getNextConsultationDate(Patient patient);
}
