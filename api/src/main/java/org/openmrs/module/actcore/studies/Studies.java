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
 * Lists, creates and renames research studies, which take the place of ACT 2.0's study list. Each
 * study is a program named after it, whose Study status workflow is a copy of the template
 * program's. Its methods do their reads and writes with proxy privileges, so a caller that must
 * restrict who may use them checks the user's privileges itself, as the studies endpoint does.
 */
public class Studies {
	
	/**
	 * The name of the global property that holds the uuid of the template program, whose workflows each
	 * new study copies.
	 */
	public static final String TEMPLATE_PROPERTY = "actcore.studyTemplateProgram";
	
	private static final int MAX_NAME_LENGTH = 50;
	
	private static final String[] PRIVILEGES = { PrivilegeConstants.GET_PROGRAMS, PrivilegeConstants.MANAGE_PROGRAMS,
	        PrivilegeConstants.GET_CONCEPTS, PrivilegeConstants.MANAGE_CONCEPTS, PrivilegeConstants.GET_CONCEPT_CLASSES,
	        PrivilegeConstants.GET_CONCEPT_DATATYPES, PrivilegeConstants.GET_CONCEPT_ATTRIBUTE_TYPES,
	        PrivilegeConstants.GET_OBS, PrivilegeConstants.GET_GLOBAL_PROPERTIES };
	
	/**
	 * Gets the studies that are not retired, sorted by name.
	 *
	 * @return the studies, sorted by name ignoring case; empty when there are none
	 * @throws APIException when {@value #TEMPLATE_PROPERTY} names no program with a workflow
	 */
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
	 * Tells whether the program is a study, whether retired or not. A study is a program other than the
	 * template that has an unretired workflow on one of the template's workflow concepts. While no
	 * template is configured, no program is a study, so enrolling keeps working.
	 *
	 * @param program the program, or null
	 * @return true for a study; false for a null program, the template itself, or when no template is
	 *         configured
	 */
	public boolean isStudy(Program program) {
		return program != null && withPrivileges(() -> {
			Program template = templateOrNull();
			return template != null && isStudy(program, template);
		});
	}
	
	/**
	 * Creates a study: a program with the name, a new concept named after it, and a copy of each of the
	 * template's unretired workflows with their unretired states.
	 *
	 * @param name the study's name, trimmed before use
	 * @return the saved program
	 * @throws ValidationException when the name is blank, longer than 50 characters, or already used by
	 *             another program or another study's concept
	 * @throws APIException when {@value #TEMPLATE_PROPERTY} names no program with a workflow
	 */
	public Program create(String name) {
		String trimmed = requireName(name);
		return withPrivileges(() -> {
			Program template = template();
			refuseTakenName(trimmed, null);
			Concept templateConcept = template.getConcept();
			Concept concept = new Concept();
			concept.setFullySpecifiedName(new ConceptName(conceptName(trimmed), locale()));
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
	
	/**
	 * Renames the study and its concept. The study's enrolments follow the new name, since they point
	 * at the program rather than at its name.
	 *
	 * @param study the study to rename
	 * @param name the new name, trimmed before use
	 * @return the saved program
	 * @throws ValidationException when the name is blank, longer than 50 characters, or already used by
	 *             another program or another study's concept
	 * @throws APIException when the program is not a study, or {@value #TEMPLATE_PROPERTY} names no
	 *             program with a workflow
	 */
	public Program rename(Program study, String name) {
		String trimmed = requireName(name);
		return withPrivileges(() -> {
			if (!isStudy(study, template())) {
				throw new APIException("Not a research study: " + (study == null ? null : study.getUuid()));
			}
			refuseTakenName(trimmed, study);
			Concept concept = study.getConcept();
			ConceptName old = concept.getFullySpecifiedName(locale());
			if (old == null || !old.getName().equals(conceptName(trimmed))) {
				concept.setFullySpecifiedName(new ConceptName(conceptName(trimmed), locale()));
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
		if (program == null || program.equals(template)) {
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
		Concept named = Context.getConceptService().getConceptByName(conceptName(name));
		if (named != null && (renaming == null || !named.equals(renaming.getConcept()))) {
			throw refusal("A concept is already named " + conceptName(name) + ", so a study cannot take that name");
		}
	}
	
	/**
	 * Gives the name of a study's concept, which adds " (research study)" to the study's name so that
	 * it stays apart from other concepts' names that the study's name may share, such as the old form's
	 * study answers.
	 */
	private static String conceptName(String name) {
		return name + " (research study)";
	}
	
	private static String requireName(String name) {
		if (StringUtils.isBlank(name)) {
			throw refusal("A study needs a name");
		}
		// program.name is VARCHAR(50) though Hibernate allows 100; longer fails after the concept is saved.
		if (name.trim().length() > MAX_NAME_LENGTH) {
			throw refusal("A study's name can be at most " + MAX_NAME_LENGTH + " characters");
		}
		return name.trim();
	}
	
	private static Locale locale() {
		return LocaleUtility.getDefaultLocale();
	}
	
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
