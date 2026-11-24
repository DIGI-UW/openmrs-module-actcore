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
import java.util.ArrayList;
import java.util.List;

import org.openmrs.Patient;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.api.NextStepsService;
import org.openmrs.module.actcore.nextsteps.NextStep;
import org.openmrs.module.actcore.nextsteps.NextSteps;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.openmrs.module.webservices.rest.web.response.IllegalRequestException;
import org.openmrs.module.webservices.rest.web.response.ObjectNotFoundException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/** Serves the next steps the chart suggests for a patient. */
@Controller
@RequestMapping("/rest/" + RestConstants.VERSION_1 + "/actcore")
public class NextStepsController extends ActCoreRestController {
	
	/**
	 * Gets the patient's next steps, as {@link NextSteps#of(Patient, LocalDate)} gives them as of
	 * today.
	 *
	 * @param patientUuid the patient's uuid
	 * @return the steps as {@code steps}, each with its key, form, title, reason, referral type and
	 *         whether it is new, done, on hold or an alert
	 * @throws IllegalRequestException when no patient is given
	 * @throws ObjectNotFoundException when no patient has the uuid
	 * @throws APIAuthenticationException when the user lacks Get Patients or Get Observations
	 */
	@RequestMapping(value = "/nextsteps", method = RequestMethod.GET)
	@ResponseBody
	public SimpleObject getNextSteps(@RequestParam(value = "patient", required = false) String patientUuid) {
		Patient patient = patient(patientUuid);
		List<SimpleObject> steps = new ArrayList<SimpleObject>();
		for (NextStep step : Context.getService(NextStepsService.class).getNextSteps(patient, LocalDate.now())) {
			steps.add(new SimpleObject().add("key", step.getKey()).add("form", step.getForm()).add("title", step.getTitle())
			        .add("reason", step.getReason()).add("isNew", step.isNew()).add("done", step.isDone())
			        .add("onHold", step.isOnHold()).add("referralType", step.getReferralType())
			        .add("alert", step.isAlert()));
		}
		return new SimpleObject().add("steps", steps);
	}
}
