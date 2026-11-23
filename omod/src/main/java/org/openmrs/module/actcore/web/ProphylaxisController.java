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
import org.openmrs.api.context.ContextAuthenticationException;
import org.openmrs.module.actcore.adherence.ProphylaxisSummary;
import org.openmrs.module.actcore.api.AdherenceService;
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

/**
 * Serves a patient's prophylaxis summary to the chart, computed from their saved forms when asked.
 */
@Controller
@RequestMapping("/rest/" + RestConstants.VERSION_1 + "/actcore")
public class ProphylaxisController extends ActCoreRestController {
	
	/**
	 * Gets the patient's prophylaxis: the regimen in force, its type and interval, the last and next
	 * doses and status, the on-time count, the adherence, the injections and whether BPG is on hold.
	 *
	 * @param patientUuid the patient's uuid
	 * @return the summary, as {@link AdherenceService#getProphylaxisSummary(Patient, LocalDate)}
	 *         computes it; without a regimen in force its status is none, bpgOnHold is false, the
	 *         injections are still listed, and its other values are null
	 * @throws IllegalRequestException when no patient is given
	 * @throws ObjectNotFoundException when no patient has the uuid
	 * @throws APIAuthenticationException when the user lacks Get Patients
	 * @throws ContextAuthenticationException when the user lacks Get Observations
	 */
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
		Context.requirePrivilege(PrivilegeConstants.GET_OBS);
		
		ProphylaxisSummary summary = Context.getService(AdherenceService.class).getProphylaxisSummary(patient,
		    LocalDate.now());
		ProphylaxisSummary.OnTime onTime = summary.getOnTime();
		return new SimpleObject().add("regimen", summary.getRegimen()).add("type", summary.getType())
		        .add("intervalDays", summary.getIntervalDays()).add("lastGiven", date(summary.getLastGiven()))
		        .add("nextDue", date(summary.getNextDue())).add("status", summary.getStatus())
		        .add("onTime",
		            onTime == null ? null
		                    : new SimpleObject().add("given", onTime.getGiven()).add("total", onTime.getTotal())
		                            .add("months", onTime.getMonths()))
		        .add("adherence", summary.getAdherence()).add("injections", injections(summary.getInjections()))
		        .add("bpgOnHold", summary.isBpgOnHold());
	}
	
	private static List<SimpleObject> injections(List<ProphylaxisSummary.Injection> injections) {
		List<SimpleObject> list = new ArrayList<SimpleObject>();
		for (ProphylaxisSummary.Injection injection : injections) {
			list.add(new SimpleObject().add("date", date(injection.getDate())).add("onTime", injection.getOnTime()));
		}
		return list;
	}
	
	private static String date(LocalDate date) {
		return date == null ? null : date.toString();
	}
}
