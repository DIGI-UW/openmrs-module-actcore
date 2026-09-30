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
import org.openmrs.api.context.Context;
import org.openmrs.module.patientflags.Flag;
import org.openmrs.module.actcore.gap.FlagGap;
import org.openmrs.module.actcore.gap.FlagGapLookup;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.ConversionUtil;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.openmrs.module.webservices.rest.web.representation.Representation;
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
public class FlagGapController extends BaseRestController {
	
	private final FlagGapLookup lookup = new FlagGapLookup();
	
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
		Flag flag = lookup.getFlag(flagUuid);
		if (flag == null) {
			throw new ObjectNotFoundException();
		}
		
		List<FlagGap> gaps = lookup.find(patient, flag);
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
	 * Null for a caller without Get Forms, whom webservices.rest 3.5.0 (unlike 2.40.0) would answer
	 * with a placeholder that the JSON writer cannot serialize.
	 */
	private static Object form(Encounter encounter) {
		if (!Context.hasPrivilege(PrivilegeConstants.GET_FORMS)) {
			return null;
		}
		return ConversionUtil.convertToRepresentation(encounter.getForm(), Representation.REF);
	}
}
