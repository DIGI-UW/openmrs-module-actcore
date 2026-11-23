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

import org.apache.commons.lang3.StringUtils;
import org.openmrs.Encounter;
import org.openmrs.Patient;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.api.FlagGapService;
import org.openmrs.module.actcore.gap.FlagGap;
import org.openmrs.module.patientflags.Flag;
import org.openmrs.module.patientflags.PatientFlagsConstants;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.ConversionUtil;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.openmrs.module.webservices.rest.web.representation.Representation;
import org.openmrs.module.webservices.rest.web.response.IllegalRequestException;
import org.openmrs.module.webservices.rest.web.response.ObjectNotFoundException;
import org.openmrs.util.PrivilegeConstants;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Serves the gaps behind a patient's flag: the questions missing from the patient's encounters, as
 * the flag's own SQL criteria report them.
 */
@Controller
@RequestMapping("/rest/" + RestConstants.VERSION_1 + "/actcore")
public class FlagGapController extends ActCoreRestController {
	
	/**
	 * Gets the gaps behind the flag for the patient: each one's encounter uuid and date, its form, and
	 * the question missing.
	 *
	 * @param patientUuid the patient's uuid
	 * @param flagUuid the flag's uuid
	 * @return {@code configured}, false when the look-up gives no answer for the flag, as
	 *         {@link FlagGapService#getGaps(Patient, Flag)} describes, and the gaps as {@code results},
	 *         oldest encounter first and empty when configured is false; a gap's form is null for a
	 *         user without Get Forms
	 * @throws IllegalRequestException when the patient or flag is missing
	 * @throws ObjectNotFoundException when no patient or no flag has the uuid
	 * @throws org.openmrs.api.APIAuthenticationException when the user lacks Get Patients or View
	 *             Patient Flags, or lacks Get Encounters or Get Concepts once the flag's criteria
	 *             return a gap to look up
	 */
	@RequestMapping(value = "/gap", method = RequestMethod.GET)
	@ResponseBody
	public SimpleObject getGaps(@RequestParam(value = "patient", required = false) String patientUuid,
	        @RequestParam(value = "flag", required = false) String flagUuid) {
		if (StringUtils.isBlank(patientUuid) || StringUtils.isBlank(flagUuid)) {
			throw new IllegalRequestException("Both patient and flag are required");
		}
		Patient patient = Context.getPatientService().getPatientByUuid(patientUuid);
		if (patient == null) {
			throw new ObjectNotFoundException();
		}
		Flag flag = flag(flagUuid);
		if (flag == null) {
			throw new ObjectNotFoundException();
		}
		
		List<FlagGap> gaps = Context.getService(FlagGapService.class).getGaps(patient, flag);
		List<SimpleObject> results = new ArrayList<SimpleObject>();
		if (gaps != null) {
			for (FlagGap gap : gaps) {
				Encounter encounter = gap.getEncounter();
				results.add(new SimpleObject().add("encounter", encounter.getUuid())
				        .add("encounterDatetime",
				            ConversionUtil.convertToRepresentation(encounter.getEncounterDatetime(), Representation.REF))
				        .add("form", form(encounter))
				        .add("concept", ConversionUtil.convertToRepresentation(gap.getQuestion(), Representation.REF)));
			}
		}
		return new SimpleObject().add("configured", gaps != null).add("results", results);
	}
	
	/**
	 * Gets a flag by uuid, refusing a user without View Patient Flags with the message these endpoints
	 * gave before the look-up moved behind a service, whose message differs.
	 *
	 * @param uuid the flag's uuid
	 * @return the flag, or null when none has the uuid
	 * @throws APIAuthenticationException when the user lacks View Patient Flags
	 */
	static Flag flag(String uuid) {
		if (!Context.hasPrivilege(PatientFlagsConstants.PRIV_VIEW_PATIENT_FLAGS)) {
			throw new APIAuthenticationException("Privilege required: " + PatientFlagsConstants.PRIV_VIEW_PATIENT_FLAGS);
		}
		return Context.getService(FlagGapService.class).getFlag(uuid);
	}
	
	/**
	 * Gives the encounter's form as a reference, or null for a caller without Get Forms. For such a
	 * caller, webservices.rest 3.5.0, unlike 2.40.0, would give a placeholder that the JSON writer
	 * cannot serialize.
	 */
	private static Object form(Encounter encounter) {
		if (!Context.hasPrivilege(PrivilegeConstants.GET_FORMS)) {
			return null;
		}
		return ConversionUtil.convertToRepresentation(encounter.getForm(), Representation.REF);
	}
}
