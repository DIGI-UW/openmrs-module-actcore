/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.web;

import java.time.LocalDate;

import org.apache.commons.lang3.StringUtils;
import org.openmrs.Patient;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.ContextAuthenticationException;
import org.openmrs.module.actcore.api.NextStepsService;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.openmrs.module.webservices.rest.web.response.IllegalRequestException;
import org.openmrs.module.webservices.rest.web.response.ObjectNotFoundException;
import org.openmrs.util.PrivilegeConstants;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/** Serves the date a patient's next cardiology review is due. */
@Controller
@RequestMapping("/rest/" + RestConstants.VERSION_1 + "/actcore")
public class CardiologyController extends ActCoreRestController {
	
	/**
	 * Gets the date the patient's next cardiology review is due, as {@code nextDue}.
	 *
	 * @param patientUuid the patient's uuid
	 * @return the due date as an ISO date, or a null nextDue when
	 *         {@link org.openmrs.module.actcore.api.impl.NextConsultation#of(Patient)} gives none
	 * @throws IllegalRequestException when no patient is given
	 * @throws ObjectNotFoundException when no patient has the uuid
	 * @throws APIAuthenticationException when the user lacks Get Patients
	 * @throws ContextAuthenticationException when the user lacks Get Observations
	 */
	@RequestMapping(value = "/cardiology", method = RequestMethod.GET)
	@ResponseBody
	public SimpleObject getNextVisit(@RequestParam(value = "patient", required = false) String patientUuid) {
		if (StringUtils.isBlank(patientUuid)) {
			throw new IllegalRequestException("The patient is required");
		}
		Patient patient = Context.getPatientService().getPatientByUuid(patientUuid);
		if (patient == null) {
			throw new ObjectNotFoundException();
		}
		Context.requirePrivilege(PrivilegeConstants.GET_OBS);
		
		LocalDate nextDue = Context.getService(NextStepsService.class).getNextConsultationDate(patient);
		return new SimpleObject().add("nextDue", nextDue == null ? null : nextDue.toString());
	}
}
