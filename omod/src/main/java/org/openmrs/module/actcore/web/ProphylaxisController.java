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
import org.openmrs.module.actcore.adherence.ProphylaxisSummary;
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
public class ProphylaxisController extends BaseRestController {
	
	@RequestMapping(value = "/prophylaxis", method = RequestMethod.GET)
	@ResponseBody
	public SimpleObject getSummary(@RequestParam(value = "patient", required = false) String patientUuid) {
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
		
		ProphylaxisSummary summary = ProphylaxisSummary.of(patient, LocalDate.now());
		ProphylaxisSummary.OnTime onTime = summary.getOnTime();
		return new SimpleObject().add("regimen", summary.getRegimen()).add("type", summary.getType())
		        .add("intervalDays", summary.getIntervalDays()).add("lastGiven", date(summary.getLastGiven()))
		        .add("nextDue", date(summary.getNextDue())).add("status", summary.getStatus()).add("onTime",
		            onTime == null ? null
		                    : new SimpleObject().add("given", onTime.getGiven()).add("total", onTime.getTotal())
		                            .add("months", onTime.getMonths()));
	}
	
	private static String date(LocalDate date) {
		return date == null ? null : date.toString();
	}
}
