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
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.openmrs.Program;
import org.openmrs.ProgramWorkflow;
import org.openmrs.ProgramWorkflowState;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.studies.Studies;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.openmrs.module.webservices.rest.web.response.ObjectNotFoundException;
import org.openmrs.module.webservices.rest.web.v1_0.controller.BaseRestController;
import org.openmrs.util.PrivilegeConstants;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Lists the research studies with their Study status states, and creates and renames them.
 */
@Controller
@RequestMapping("/rest/" + RestConstants.VERSION_1 + "/actcore/studies")
public class StudiesController extends BaseRestController {
	
	/**
	 * The distribution creates it; a user without it, other than a superuser, cannot change studies.
	 */
	public static final String MANAGE_PRIVILEGE = "Task: act.manageStudies";
	
	private final Studies studies = new Studies();
	
	@RequestMapping(method = RequestMethod.GET)
	@ResponseBody
	public SimpleObject getStudies() {
		requirePrivilege(PrivilegeConstants.GET_PROGRAMS);
		List<SimpleObject> results = new ArrayList<>();
		for (Program study : studies.getStudies()) {
			results.add(toJson(study));
		}
		return new SimpleObject().add("results", results);
	}
	
	@RequestMapping(method = RequestMethod.POST)
	@ResponseBody
	public SimpleObject create(@RequestBody Map<String, Object> body) {
		requirePrivilege(MANAGE_PRIVILEGE);
		return toJson(studies.create(name(body)));
	}
	
	@RequestMapping(value = "/{uuid}", method = RequestMethod.POST)
	@ResponseBody
	public SimpleObject rename(@PathVariable("uuid") String uuid, @RequestBody Map<String, Object> body) {
		requirePrivilege(MANAGE_PRIVILEGE);
		Program study = studies.getStudies().stream().filter(p -> p.getUuid().equals(uuid)).findFirst()
		        .orElseThrow(ObjectNotFoundException::new);
		return toJson(studies.rename(study, name(body)));
	}
	
	private static String name(Map<String, Object> body) {
		Object name = body == null ? null : body.get("name");
		return name == null ? null : name.toString();
	}
	
	private static SimpleObject toJson(Program study) {
		List<SimpleObject> states = new ArrayList<>();
		for (ProgramWorkflow workflow : study.getWorkflows()) {
			if (workflow.getRetired()) {
				continue;
			}
			List<ProgramWorkflowState> sorted = new ArrayList<>(workflow.getStates(false));
			sorted.sort(Comparator.comparing((ProgramWorkflowState state) -> !state.getInitial())
			        .thenComparing(state -> state.getConcept().getName().getName()));
			for (ProgramWorkflowState state : sorted) {
				states.add(
				    new SimpleObject().add("uuid", state.getUuid()).add("name", state.getConcept().getName().getName())
				            .add("initial", state.getInitial()).add("terminal", state.getTerminal()));
			}
		}
		return new SimpleObject().add("uuid", study.getUuid()).add("name", study.getName()).add("states", states);
	}
	
	private static void requirePrivilege(String privilege) {
		if (!Context.hasPrivilege(privilege)) {
			throw new APIAuthenticationException("Privilege required: " + privilege);
		}
	}
}
