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
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.openmrs.Patient;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.ActCorePrivileges;
import org.openmrs.module.actcore.api.FlagGapService;
import org.openmrs.module.actcore.api.RaisedFlagService;
import org.openmrs.module.actcore.model.ChartFlag;
import org.openmrs.module.patientflags.Flag;
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
	 * @throws APIAuthenticationException when the user lacks Get Patients or View Patient Flags
	 * @throws IllegalRequestException when no patient is given
	 * @throws ObjectNotFoundException when no patient has the uuid
	 */
	@RequestMapping(method = RequestMethod.GET)
	@ResponseBody
	public SimpleObject getRaisedFlags(@RequestParam(value = "patient", required = false) String patientUuid) {
		Patient patient = patient(patientUuid);
		List<SimpleObject> results = new ArrayList<SimpleObject>();
		for (ChartFlag raised : Context.getService(RaisedFlagService.class).getRaisedFlags(patient)) {
			results.add(new SimpleObject().add("flag", raised.getFlag().getUuid()).add("display", raised.getFlag().getName())
			        .add("raisedSince",
			            raised.getSince() == null ? null
			                    : ConversionUtil.convertToRepresentation(raised.getSince(), Representation.REF))
			        .add("dismissible", raised.isDismissible()));
		}
		return new SimpleObject().add("results", results);
	}
	
	/**
	 * Dismisses a raised flag for the patient, as {@link RaisedFlagService#dismiss} does.
	 *
	 * @param body the request body, holding the patient's uuid as {@code patient} and the flag's as
	 *            {@code flag}
	 * @return {@code dismissed} true, also when the flag was already dismissed
	 * @throws IllegalRequestException when the patient or flag is missing, the flag may not be
	 *             dismissed, or it is not raised for the patient now
	 * @throws ObjectNotFoundException when no patient or no flag has the uuid
	 * @throws APIAuthenticationException when the user lacks Get Patients, View Patient Flags or
	 *             {@link ActCorePrivileges#DISMISS_FLAGS}
	 * @throws ResponseException with 409 Conflict while the full patient flag or adherence refresh runs
	 */
	@RequestMapping(value = "/dismiss", method = RequestMethod.POST)
	@ResponseBody
	public SimpleObject dismiss(@RequestBody Map<String, Object> body) {
		Patient patient = patient(value(body, "patient"));
		String flagUuid = value(body, "flag");
		if (StringUtils.isBlank(flagUuid)) {
			throw new IllegalRequestException("The flag is required");
		}
		Flag flag = Context.getService(FlagGapService.class).getFlag(flagUuid);
		if (flag == null) {
			throw new ObjectNotFoundException();
		}
		switch (Context.getService(RaisedFlagService.class).dismiss(patient, flag)) {
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
	 * Thrown when a dismissal is asked for while the full patient flag or adherence refresh runs, which
	 * the REST layer answers with 409 Conflict.
	 */
	@ResponseStatus(HttpStatus.CONFLICT)
	static class RefreshRunning extends ResponseException {
		
		private static final long serialVersionUID = 1L;
		
		RefreshRunning() {
			super("A refresh of the registry is running; dismiss the flag again once it has finished");
		}
	}
	
	private static String value(Map<String, Object> body, String key) {
		Object value = body == null ? null : body.get(key);
		return value == null ? null : value.toString();
	}
}
