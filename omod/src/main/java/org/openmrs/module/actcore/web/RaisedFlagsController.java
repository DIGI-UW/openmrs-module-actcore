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
import org.openmrs.module.webservices.rest.web.v1_0.controller.BaseRestController;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * A patient's raised flags with the date each was raised and whether the user may dismiss it, and
 * dismissing one.
 */
@Controller
@RequestMapping("/rest/" + RestConstants.VERSION_1 + "/actcore/raisedflags")
public class RaisedFlagsController extends BaseRestController {
	
	@RequestMapping(method = RequestMethod.GET)
	@ResponseBody
	public SimpleObject getRaisedFlags(@RequestParam(value = "patient", required = false) String patientUuid) {
		if (!Context.hasPrivilege(PatientFlagsConstants.PRIV_VIEW_PATIENT_FLAGS)) {
			throw new APIAuthenticationException("Privilege required: " + PatientFlagsConstants.PRIV_VIEW_PATIENT_FLAGS);
		}
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
