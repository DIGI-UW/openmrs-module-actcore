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

import org.openmrs.Concept;
import org.openmrs.ConceptName;
import org.openmrs.Program;
import org.openmrs.ProgramWorkflow;
import org.openmrs.ProgramWorkflowState;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.ActCorePrivileges;
import org.openmrs.module.actcore.api.StudyService;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.openmrs.module.webservices.rest.web.response.ObjectNotFoundException;
import org.openmrs.util.PrivilegeConstants;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Serves the research studies: lists them with their Study status states, and creates and renames
 * them.
 */
@Controller
@RequestMapping("/rest/" + RestConstants.VERSION_1 + "/actcore/studies")
public class StudiesController extends ActCoreRestController {
	
	/** The privilege a user needs, unless they are a superuser, to create or rename a study. */
	public static final String MANAGE_PRIVILEGE = ActCorePrivileges.MANAGE_STUDIES;
	
	/**
	 * Gets the studies that are not retired, by name, each with its uuid, name and Study status states,
	 * the initial states first.
	 *
	 * @return the studies as {@code results}; empty when there are none
	 * @throws APIAuthenticationException when the user lacks Get Programs
	 * @throws org.openmrs.api.APIException when the study template program is not configured
	 */
	@RequestMapping(method = RequestMethod.GET)
	@ResponseBody
	public SimpleObject getStudies() {
		requirePrivilege(PrivilegeConstants.GET_PROGRAMS);
		List<SimpleObject> results = new ArrayList<>();
		for (Program study : Context.getService(StudyService.class).getStudies()) {
			results.add(toJson(study));
		}
		return new SimpleObject().add("results", results);
	}
	
	/**
	 * Creates a study, as {@link StudyService#createStudy(String)} does.
	 *
	 * @param body the request body, holding the study's name as {@code name}
	 * @return the new study, with its uuid, name and states
	 * @throws APIAuthenticationException when the user lacks {@link #MANAGE_PRIVILEGE}
	 * @throws org.openmrs.api.ValidationException when the name is missing, too long or taken
	 * @throws org.openmrs.api.APIException when the study template program is not configured
	 */
	@RequestMapping(method = RequestMethod.POST)
	@ResponseBody
	public SimpleObject create(@RequestBody Map<String, Object> body) {
		requirePrivilege(MANAGE_PRIVILEGE);
		return toJson(Context.getService(StudyService.class).createStudy(name(body)));
	}
	
	/**
	 * Renames a study, as {@link StudyService#renameStudy(Program, String)} does.
	 *
	 * @param uuid the study's uuid
	 * @param body the request body, holding the new name as {@code name}
	 * @return the renamed study, with its uuid, name and states
	 * @throws APIAuthenticationException when the user lacks {@link #MANAGE_PRIVILEGE}
	 * @throws ObjectNotFoundException when no unretired study has the uuid
	 * @throws org.openmrs.api.ValidationException when the name is missing, too long or taken
	 * @throws org.openmrs.api.APIException when the study template program is not configured
	 */
	@RequestMapping(value = "/{uuid}", method = RequestMethod.POST)
	@ResponseBody
	public SimpleObject rename(@PathVariable("uuid") String uuid, @RequestBody Map<String, Object> body) {
		requirePrivilege(MANAGE_PRIVILEGE);
		StudyService studies = Context.getService(StudyService.class);
		Program study = studies.getStudies().stream().filter(p -> p.getUuid().equals(uuid)).findFirst()
		        .orElseThrow(ObjectNotFoundException::new);
		return toJson(studies.renameStudy(study, name(body)));
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
			        .thenComparing(state -> label(state.getConcept())));
			for (ProgramWorkflowState state : sorted) {
				states.add(new SimpleObject().add("uuid", state.getUuid()).add("name", label(state.getConcept()))
				        .add("initial", state.getInitial()).add("terminal", state.getTerminal()));
			}
		}
		return new SimpleObject().add("uuid", study.getUuid()).add("name", study.getName()).add("states", states);
	}
	
	/**
	 * Gives a state's label: its concept's short name in the user's locale when it has one, such as
	 * "Active" for Active (study status), or else its concept's name.
	 */
	private static String label(Concept concept) {
		ConceptName shortName = concept.getShortNameInLocale(Context.getLocale());
		return shortName != null ? shortName.getName() : concept.getName().getName();
	}
	
	private static void requirePrivilege(String privilege) {
		if (!Context.hasPrivilege(privilege)) {
			throw new APIAuthenticationException("Privilege required: " + privilege);
		}
	}
}
