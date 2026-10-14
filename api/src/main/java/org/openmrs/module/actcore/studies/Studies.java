/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.studies;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;
import org.openmrs.Concept;
import org.openmrs.ConceptName;
import org.openmrs.Program;
import org.openmrs.ProgramWorkflow;
import org.openmrs.ProgramWorkflowState;
import org.openmrs.api.APIException;
import org.openmrs.api.ValidationException;
import org.openmrs.api.context.Context;
import org.openmrs.util.LocaleUtility;
import org.openmrs.util.PrivilegeConstants;
import org.springframework.validation.BindException;
import org.springframework.validation.Errors;

/**
 * Research studies, as ACT 2.0's study list: each study is a program named after it, whose Study
 * status workflow is a copy of the template program's.
 */
public class Studies {
	
	public static final String TEMPLATE_PROPERTY = "actcore.studyTemplateProgram";
	
	private static final String[] PRIVILEGES = { PrivilegeConstants.GET_PROGRAMS, PrivilegeConstants.MANAGE_PROGRAMS,
	        PrivilegeConstants.GET_CONCEPTS, PrivilegeConstants.MANAGE_CONCEPTS, PrivilegeConstants.GET_CONCEPT_CLASSES,
	        PrivilegeConstants.GET_CONCEPT_DATATYPES, PrivilegeConstants.GET_CONCEPT_ATTRIBUTE_TYPES,
	        PrivilegeConstants.GET_OBS, PrivilegeConstants.GET_GLOBAL_PROPERTIES };
	
	/** The studies that are not retired, by name. */
	public List<Program> getStudies() {
		return withPrivileges(() -> {
			Program template = template();
			List<Program> studies = new ArrayList<>();
			for (Program program : Context.getProgramWorkflowService().getAllPrograms(false)) {
				if (isStudy(program, template)) {
					studies.add(program);
				}
			}
			studies.sort(Comparator.comparing(Program::getName, String.CASE_INSENSITIVE_ORDER));
			return studies;
		});
	}
	
	/**
	 * Whether the program is a study: not the template, with a workflow on the template's. False for
	 * every program when the template is missing, so enrolling keeps working without it.
	 */
	public boolean isStudy(Program program) {
		return program != null && withPrivileges(() -> {
			Program template = templateOrNull();
			return template != null && isStudy(program, template);
		});
	}
	
	public Program create(String name) {
		String trimmed = requireName(name);
		return withPrivileges(() -> {
			Program template = template();
			refuseTakenName(trimmed, null);
			Concept templateConcept = template.getConcept();
			Concept concept = new Concept();
			concept.setFullySpecifiedName(new ConceptName(trimmed, locale()));
			concept.setConceptClass(templateConcept.getConceptClass());
			concept.setDatatype(templateConcept.getDatatype());
			Context.getConceptService().saveConcept(concept);
			
			Program study = new Program();
			study.setName(trimmed);
			study.setDescription("Research study: " + trimmed);
			study.setConcept(concept);
			for (ProgramWorkflow templateWorkflow : template.getWorkflows()) {
				if (templateWorkflow.getRetired()) {
					continue;
				}
				ProgramWorkflow workflow = new ProgramWorkflow();
				workflow.setConcept(templateWorkflow.getConcept());
				for (ProgramWorkflowState templateState : templateWorkflow.getStates(false)) {
					ProgramWorkflowState state = new ProgramWorkflowState();
					state.setConcept(templateState.getConcept());
					state.setInitial(templateState.getInitial());
					state.setTerminal(templateState.getTerminal());
					workflow.addState(state);
				}
				study.addWorkflow(workflow);
			}
			return Context.getProgramWorkflowService().saveProgram(study);
		});
	}
	
	/** Renames the study and its concept; enrolments follow, as they point at the program. */
	public Program rename(Program study, String name) {
		String trimmed = requireName(name);
		return withPrivileges(() -> {
			if (!isStudy(study, template())) {
				throw new APIException("Not a research study: " + (study == null ? null : study.getUuid()));
			}
			refuseTakenName(trimmed, study);
			Concept concept = study.getConcept();
			ConceptName old = concept.getFullySpecifiedName(locale());
			if (old == null || !old.getName().equals(trimmed)) {
				concept.setFullySpecifiedName(new ConceptName(trimmed, locale()));
				// The old name would keep it from being given to a new study.
				if (old != null) {
					concept.removeName(old);
				}
				Context.getConceptService().saveConcept(concept);
			}
			study.setName(trimmed);
			study.setDescription("Research study: " + trimmed);
			return Context.getProgramWorkflowService().saveProgram(study);
		});
	}
	
	private static boolean isStudy(Program program, Program template) {
		if (program == null || program.equals(template) || Boolean.TRUE.equals(program.getRetired())) {
			return false;
		}
		for (ProgramWorkflow workflow : program.getWorkflows()) {
			for (ProgramWorkflow templateWorkflow : template.getWorkflows()) {
				if (!workflow.getRetired() && workflow.getConcept().equals(templateWorkflow.getConcept())) {
					return true;
				}
			}
		}
		return false;
	}
	
	private static Program template() {
		Program template = templateOrNull();
		if (template == null) {
			throw new APIException(TEMPLATE_PROPERTY + " names no program with a workflow ("
			        + Context.getAdministrationService().getGlobalProperty(TEMPLATE_PROPERTY) + ")");
		}
		return template;
	}
	
	private static Program templateOrNull() {
		String uuid = Context.getAdministrationService().getGlobalProperty(TEMPLATE_PROPERTY);
		Program template = StringUtils.isBlank(uuid) ? null
		        : Context.getProgramWorkflowService().getProgramByUuid(uuid.trim());
		return template == null || template.getWorkflows().isEmpty() ? null : template;
	}
	
	private static void refuseTakenName(String name, Program renaming) {
		for (Program program : Context.getProgramWorkflowService().getAllPrograms(true)) {
			if (!program.equals(renaming) && name.equalsIgnoreCase(program.getName())) {
				throw refusal("A program is already named " + name);
			}
		}
		Concept named = Context.getConceptService().getConceptByName(name);
		if (named != null && (renaming == null || !named.equals(renaming.getConcept()))) {
			throw refusal("A concept is already named " + name + ", so a study cannot take that name");
		}
	}
	
	private static String requireName(String name) {
		if (StringUtils.isBlank(name)) {
			throw refusal("A study needs a name");
		}
		return name.trim();
	}
	
	private static Locale locale() {
		return LocaleUtility.getDefaultLocale();
	}
	
	// As a global error, so the REST API answers 400 with the message rather than a stack trace.
	private static ValidationException refusal(String message) {
		Errors errors = new BindException(new Object(), "study");
		errors.reject("actcore.study", message);
		return new ValidationException(message, errors);
	}
	
	private static <T> T withPrivileges(Supplier<T> work) {
		for (String privilege : PRIVILEGES) {
			Context.addProxyPrivilege(privilege);
		}
		try {
			return work.get();
		}
		finally {
			for (String privilege : PRIVILEGES) {
				Context.removeProxyPrivilege(privilege);
			}
		}
	}
}
