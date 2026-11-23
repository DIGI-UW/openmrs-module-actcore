/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.api.db;

import org.openmrs.Program;

/**
 * The database access behind {@link org.openmrs.module.actcore.api.StudyService}, which reads a
 * saved enrolment as the database holds it. Its reads leave the session unflushed, so that an
 * enrolment being edited in the session is not written before it is checked.
 */
public interface StudyDAO {
	
	/**
	 * Gets the program the enrolment is saved in.
	 *
	 * @param patientProgramId the id of the enrolment
	 * @return the program; null when no enrolment has the id
	 */
	Program getSavedProgram(Integer patientProgramId);
	
	/**
	 * Tells whether the enrolment is saved without a completion date and without an unvoided, non-blank
	 * attribute of the type.
	 *
	 * @param patientProgramId the id of the enrolment
	 * @param attributeTypeUuid the uuid of the program attribute type
	 * @return true when the enrolment is saved open without such an attribute; false when no enrolment
	 *         has the id
	 */
	boolean isSavedOpenWithoutAttribute(Integer patientProgramId, String attributeTypeUuid);
}
