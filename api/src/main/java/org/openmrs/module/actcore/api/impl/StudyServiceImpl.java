/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.api.impl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;
import org.openmrs.Concept;
import org.openmrs.ConceptName;
import org.openmrs.PatientProgram;
import org.openmrs.Program;
import org.openmrs.ProgramWorkflow;
import org.openmrs.ProgramWorkflowState;
import org.openmrs.api.APIException;
import org.openmrs.api.ValidationException;
import org.openmrs.api.context.Context;
import org.openmrs.api.impl.BaseOpenmrsService;
import org.openmrs.module.actcore.api.StudyService;
import org.openmrs.module.actcore.api.db.StudyDAO;
import org.openmrs.module.actcore.studies.Studies;
import org.openmrs.util.LocaleUtility;
import org.openmrs.util.PrivilegeConstants;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.BindException;
import org.springframework.validation.Errors;

/**
 * Default implementation of {@link org.openmrs.module.actcore.api.StudyService}. It reads and saves
 * studies through core's program and concept services with proxy privileges, so that a user who may
 * manage studies need not also hold the privileges to manage programs and concepts.
 */
@Transactional
public class StudyServiceImpl extends BaseOpenmrsService implements StudyService {
	
	private static final int MAX_NAME_LENGTH = 50;
	
	private static final String[] PRIVILEGES = { PrivilegeConstants.GET_PROGRAMS, PrivilegeConstants.MANAGE_PROGRAMS,
	        PrivilegeConstants.GET_CONCEPTS, PrivilegeConstants.MANAGE_CONCEPTS, PrivilegeConstants.GET_CONCEPT_CLASSES,
	        PrivilegeConstants.GET_CONCEPT_DATATYPES, PrivilegeConstants.GET_CONCEPT_ATTRIBUTE_TYPES,
	        PrivilegeConstants.GET_OBS, PrivilegeConstants.GET_GLOBAL_PROPERTIES };
	
	private StudyDAO dao;
	
	public void setDao(StudyDAO dao) {
		this.dao = dao;
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.StudyService#getStudies()
	 */
	@Override
	@Transactional(readOnly = true)
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
	 * @see org.openmrs.module.actcore.api.StudyService#isStudy(Program)
	 */
	@Override
	@Transactional(readOnly = true)
	public boolean isStudy(Program program) {
		return program != null && withPrivileges(() -> {
			Program template = templateOrNull();
			return template != null && isStudy(program, template);
		});
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.StudyService#createStudy(String)
	 */
	@Override
	public Program createStudy(String name) {
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
	 * @see org.openmrs.module.actcore.api.StudyService#renameStudy(Program, String)
	 */
	@Override
	public Program renameStudy(Program study, String name) {
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
	
	/**
	 * @see org.openmrs.module.actcore.api.StudyService#getSavedProgram(PatientProgram)
	 */
	@Override
	@Transactional(readOnly = true)
	public Program getSavedProgram(PatientProgram enrolment) {
		return enrolment.getPatientProgramId() == null ? null : dao.getSavedProgram(enrolment.getPatientProgramId());
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.StudyService#isSavedOpenWithoutAttribute(PatientProgram,
	 *      String)
	 */
	@Override
	@Transactional(readOnly = true)
	public boolean isSavedOpenWithoutAttribute(PatientProgram enrolment, String attributeTypeUuid) {
		return enrolment.getPatientProgramId() != null
		        && dao.isSavedOpenWithoutAttribute(enrolment.getPatientProgramId(), attributeTypeUuid);
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
			throw new APIException(Studies.TEMPLATE_PROPERTY + " names no program with a workflow ("
			        + Context.getAdministrationService().getGlobalProperty(Studies.TEMPLATE_PROPERTY) + ")");
		}
		return template;
	}
	
	private static Program templateOrNull() {
		String uuid = Context.getAdministrationService().getGlobalProperty(Studies.TEMPLATE_PROPERTY);
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
