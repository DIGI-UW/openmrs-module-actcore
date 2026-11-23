/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.api;

import java.util.List;

import org.openmrs.PatientProgram;
import org.openmrs.Program;
import org.openmrs.annotation.Authorized;
import org.openmrs.api.OpenmrsService;
import org.openmrs.module.actcore.ActCorePrivileges;
import org.openmrs.module.actcore.studies.Studies;
import org.openmrs.util.PrivilegeConstants;

/**
 * The service that lists, creates and renames research studies, which take the place of ACT 2.0's
 * study list. Each study is a program named after it, whose Study status workflow is a copy of the
 * template program's. The studies endpoint lists, creates and renames studies through it, and the
 * advice on the patient, person and program services asks it whether a program is a study and how a
 * study enrolment is saved.
 */
public interface StudyService extends OpenmrsService {
	
	/**
	 * Gets the studies that are not retired, sorted by name.
	 *
	 * @return the studies, sorted by name ignoring case; empty when there are none
	 * @throws org.openmrs.api.APIException when {@value Studies#TEMPLATE_PROPERTY} names no program
	 *             with a workflow
	 * @throws org.openmrs.api.APIAuthenticationException when the user lacks
	 *             {@link PrivilegeConstants#GET_PROGRAMS}
	 */
	@Authorized(PrivilegeConstants.GET_PROGRAMS)
	List<Program> getStudies();
	
	/**
	 * Tells whether the program is a study, whether retired or not. A study is a program other than the
	 * template that has an unretired workflow on one of the template's workflow concepts. While no
	 * template is configured, no program is a study, so enrolling keeps working. It needs no privilege
	 * beyond signing in, because the advice asks it for whoever saves a patient, a person or an
	 * enrolment.
	 *
	 * @param program the program, or null
	 * @return true for a study; false for a null program, the template itself, or when no template is
	 *         configured
	 * @throws org.openmrs.api.APIAuthenticationException when no user is signed in
	 */
	@Authorized
	boolean isStudy(Program program);
	
	/**
	 * Creates a study: a program with the name, a new concept named after it, and a copy of each of the
	 * template's unretired workflows with their unretired states. The concept and the program are saved
	 * in one transaction.
	 *
	 * @param name the study's name, trimmed before use
	 * @return the saved program
	 * @throws org.openmrs.api.ValidationException when the name is blank, longer than 50 characters, or
	 *             already used by another program or another study's concept
	 * @throws org.openmrs.api.APIException when {@value Studies#TEMPLATE_PROPERTY} names no program
	 *             with a workflow
	 * @throws org.openmrs.api.APIAuthenticationException when the user lacks
	 *             {@link ActCorePrivileges#MANAGE_STUDIES}
	 */
	@Authorized(ActCorePrivileges.MANAGE_STUDIES)
	Program createStudy(String name);
	
	/**
	 * Renames the study and its concept. The study's enrolments follow the new name, since they point
	 * at the program rather than at its name.
	 *
	 * @param study the study to rename
	 * @param name the new name, trimmed before use
	 * @return the saved program
	 * @throws org.openmrs.api.ValidationException when the name is blank, longer than 50 characters, or
	 *             already used by another program or another study's concept
	 * @throws org.openmrs.api.APIException when the program is not a study, or
	 *             {@value Studies#TEMPLATE_PROPERTY} names no program with a workflow
	 * @throws org.openmrs.api.APIAuthenticationException when the user lacks
	 *             {@link ActCorePrivileges#MANAGE_STUDIES}
	 */
	@Authorized(ActCorePrivileges.MANAGE_STUDIES)
	Program renameStudy(Program study, String name);
	
	/**
	 * Gets the program an enrolment is saved in, read from the database rather than from the session,
	 * which holds the enrolment as it is being edited. It needs the privileges that saving an enrolment
	 * needs, because the enrolment rules ask it while an enrolment is saved.
	 *
	 * @param enrolment the enrolment
	 * @return the program it is saved in; null when it is not saved
	 * @throws org.openmrs.api.APIAuthenticationException when the user holds neither
	 *             {@link PrivilegeConstants#ADD_PATIENT_PROGRAMS} nor
	 *             {@link PrivilegeConstants#EDIT_PATIENT_PROGRAMS}
	 */
	@Authorized({ PrivilegeConstants.ADD_PATIENT_PROGRAMS, PrivilegeConstants.EDIT_PATIENT_PROGRAMS })
	Program getSavedProgram(PatientProgram enrolment);
	
	/**
	 * Tells whether an enrolment is saved open and without an unvoided, non-blank attribute of the
	 * type, read from the database rather than from the session, which holds the enrolment as it is
	 * being edited. The enrolment rules let such an enrolment, as the copy that core's merge saves, be
	 * saved again without a Study ID, while they refuse to reopen a completed one that has none. It
	 * needs the privileges that saving an enrolment needs, because the rules ask it while an enrolment
	 * is saved.
	 *
	 * @param enrolment the enrolment
	 * @param attributeTypeUuid the uuid of the program attribute type
	 * @return true when the saved enrolment has no completion date and no such attribute; false when it
	 *         is not saved
	 * @throws org.openmrs.api.APIAuthenticationException when the user holds neither
	 *             {@link PrivilegeConstants#ADD_PATIENT_PROGRAMS} nor
	 *             {@link PrivilegeConstants#EDIT_PATIENT_PROGRAMS}
	 */
	@Authorized({ PrivilegeConstants.ADD_PATIENT_PROGRAMS, PrivilegeConstants.EDIT_PATIENT_PROGRAMS })
	boolean isSavedOpenWithoutAttribute(PatientProgram enrolment, String attributeTypeUuid);
}
