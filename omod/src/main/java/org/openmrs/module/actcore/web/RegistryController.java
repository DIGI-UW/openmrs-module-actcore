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

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.openmrs.PatientProgram;
import org.openmrs.Program;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.registry.EnrolOnRegistration;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.openmrs.module.webservices.rest.web.v1_0.controller.BaseRestController;
import org.openmrs.util.PrivilegeConstants;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
@RequestMapping("/rest/" + RestConstants.VERSION_1 + "/actcore")
public class RegistryController extends BaseRestController {
	
	@RequestMapping(value = "/registry/count", method = RequestMethod.GET)
	@ResponseBody
	public SimpleObject getCount() {
		if (!Context.hasPrivilege(PrivilegeConstants.GET_PATIENT_PROGRAMS)) {
			throw new APIAuthenticationException("Privilege required: " + PrivilegeConstants.GET_PATIENT_PROGRAMS);
		}
		String uuid = Context.getAdministrationService().getGlobalProperty(EnrolOnRegistration.PROGRAM_PROPERTY);
		if (StringUtils.isBlank(uuid)) {
			return new SimpleObject().add("count", 0);
		}
		Program registry = Context.getProgramWorkflowService().getProgramByUuid(uuid.trim());
		if (registry == null) {
			return new SimpleObject().add("count", 0);
		}
		List<PatientProgram> enrolments = Context.getProgramWorkflowService().getPatientPrograms(null, registry, null, null,
		    null, null, false);
		Set<Integer> patients = new HashSet<Integer>();
		for (PatientProgram enrolment : enrolments) {
			if (enrolment.getDateCompleted() == null) {
				patients.add(enrolment.getPatient().getPatientId());
			}
		}
		return new SimpleObject().add("count", patients.size());
	}
}
