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

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.openmrs.Patient;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.ContextAuthenticationException;
import org.openmrs.module.actcore.gap.FlagGapLookup;
import org.openmrs.module.actcore.task.RaisedFlags;
import org.openmrs.module.patientflags.Flag;
import org.openmrs.module.patientflags.PatientFlag;
import org.openmrs.module.patientflags.PatientFlagsConstants;
import org.openmrs.module.patientflags.api.FlagService;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.ConversionUtil;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.openmrs.module.webservices.rest.web.representation.Representation;
import org.openmrs.module.webservices.rest.web.response.IllegalRequestException;
import org.openmrs.module.webservices.rest.web.response.ObjectNotFoundException;
import org.openmrs.module.webservices.rest.web.response.ResponseException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Serves a patient's raised flags, each with the date it was raised and whether the user may
 * dismiss it, and dismisses a flag for a patient.
 */
@Controller
@RequestMapping("/rest/" + RestConstants.VERSION_1 + "/actcore/raisedflags")
public class RaisedFlagsController extends ActCoreRestController {
	
	/**
	 * Gets the patient's raised flags, less the dismissed ones: each flag's uuid and name, since when
	 * it has been raised, and whether the user may dismiss it.
	 *
	 * @param patientUuid the patient's uuid
	 * @return the flags as {@code results}, one per flag, empty when none is shown; a flag's
	 *         raisedSince is null when ACT Core has no date for it
	 * @throws ContextAuthenticationException when the user lacks View Patient Flags
	 * @throws APIAuthenticationException when the user lacks Get Patients
	 * @throws IllegalRequestException when no patient is given
	 * @throws ObjectNotFoundException when no patient has the uuid
	 */
	@RequestMapping(method = RequestMethod.GET)
	@ResponseBody
	public SimpleObject getRaisedFlags(@RequestParam(value = "patient", required = false) String patientUuid) {
		Context.requirePrivilege(PatientFlagsConstants.PRIV_VIEW_PATIENT_FLAGS);
		Patient patient = patient(patientUuid);
		Map<Integer, Date> raisedSince = RaisedFlags.raisedSinceByFlag(patient.getPatientId());
		List<SimpleObject> results = new ArrayList<SimpleObject>();
		Set<Integer> seen = new HashSet<Integer>();
		for (PatientFlag row : Context.getService(FlagService.class).getPatientFlags(patient)) {
			Flag flag = row.getFlag();
			if (Boolean.TRUE.equals(row.getVoided()) || flag == null || !seen.add(flag.getFlagId())) {
				continue;
			}
			Date since = raisedSince.get(flag.getFlagId());
			results.add(new SimpleObject().add("flag", flag.getUuid()).add("display", flag.getName())
			        .add("raisedSince",
			            since == null ? null : ConversionUtil.convertToRepresentation(since, Representation.REF))
			        .add("dismissible", RaisedFlags.mayDismiss(flag)));
		}
		return new SimpleObject().add("results", results);
	}
	
	/**
	 * Dismisses a raised flag for the patient, as {@link RaisedFlags#dismiss} does.
	 *
	 * @param body the request body, holding the patient's uuid as {@code patient} and the flag's as
	 *            {@code flag}
	 * @return {@code dismissed} true, also when the flag was already dismissed
	 * @throws IllegalRequestException when the patient or flag is missing, the flag may not be
	 *             dismissed, or it is not raised for the patient now
	 * @throws ObjectNotFoundException when no patient or no flag has the uuid
	 * @throws APIAuthenticationException when the user lacks Get Patients, View Patient Flags or
	 *             {@link RaisedFlags#DISMISS_PRIVILEGE}
	 * @throws ResponseException with 409 Conflict while the patient flag refresh runs
	 */
	@RequestMapping(value = "/dismiss", method = RequestMethod.POST)
	@ResponseBody
	public SimpleObject dismiss(@RequestBody Map<String, Object> body) {
		Patient patient = patient(value(body, "patient"));
		String flagUuid = value(body, "flag");
		if (StringUtils.isBlank(flagUuid)) {
			throw new IllegalRequestException("The flag is required");
		}
		Flag flag = new FlagGapLookup().getFlag(flagUuid);
		if (flag == null) {
			throw new ObjectNotFoundException();
		}
		switch (RaisedFlags.dismiss(patient, flag)) {
			case NOT_DISMISSIBLE:
				throw new IllegalRequestException("The flag '" + flag.getName() + "' cannot be dismissed");
			case NOT_RAISED:
				throw new IllegalRequestException("The flag '" + flag.getName() + "' is not raised for this patient");
			case REFRESH_RUNNING:
				throw new RefreshRunning();
			default:
				return new SimpleObject().add("dismissed", true);
		}
	}
	
	/**
	 * Thrown when a dismissal is asked for while the patient flag refresh runs, which the REST layer
	 * answers with 409 Conflict.
	 */
	@ResponseStatus(HttpStatus.CONFLICT)
	static class RefreshRunning extends ResponseException {
		
		private static final long serialVersionUID = 1L;
		
		RefreshRunning() {
			super("The patient flag refresh is running; dismiss the flag again once it has finished");
		}
	}
	
	private static Patient patient(String uuid) {
		if (StringUtils.isBlank(uuid)) {
			throw new IllegalRequestException("The patient is required");
		}
		Patient patient = Context.getPatientService().getPatientByUuid(uuid);
		if (patient == null) {
			throw new ObjectNotFoundException();
		}
		return patient;
	}
	
	private static String value(Map<String, Object> body, String key) {
		Object value = body == null ? null : body.get(key);
		return value == null ? null : value.toString();
	}
}
