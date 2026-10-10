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

import org.apache.commons.lang3.StringUtils;
import org.openmrs.Patient;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.nextsteps.NextStep;
import org.openmrs.module.actcore.nextsteps.NextSteps;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.openmrs.module.webservices.rest.web.response.IllegalRequestException;
import org.openmrs.module.webservices.rest.web.response.ObjectNotFoundException;
import org.openmrs.module.webservices.rest.web.v1_0.controller.BaseRestController;
import org.openmrs.util.PrivilegeConstants;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
@RequestMapping("/rest/" + RestConstants.VERSION_1 + "/actcore")
public class NextStepsController extends BaseRestController {
	
	@RequestMapping(value = "/nextsteps", method = RequestMethod.GET)
	@ResponseBody
	public SimpleObject getNextSteps(@RequestParam(value = "patient", required = false) String patientUuid) {
		if (StringUtils.isBlank(patientUuid)) {
			throw new IllegalRequestException("The patient is required");
		}
		Patient patient = Context.getPatientService().getPatientByUuid(patientUuid);
		if (patient == null) {
			throw new ObjectNotFoundException();
		}
		// Thrown here rather than by requirePrivilege, whose exception the REST layer answers with a 500.
		if (!Context.hasPrivilege(PrivilegeConstants.GET_OBS)) {
			throw new APIAuthenticationException("Privilege required: " + PrivilegeConstants.GET_OBS);
		}
		
		List<SimpleObject> steps = new ArrayList<SimpleObject>();
		for (NextStep step : NextSteps.of(patient, LocalDate.now())) {
			steps.add(new SimpleObject().add("key", step.getKey()).add("form", step.getForm()).add("title", step.getTitle())
			        .add("reason", step.getReason()).add("isNew", step.isNew()).add("done", step.isDone()));
		}
		return new SimpleObject().add("steps", steps);
	}
}
