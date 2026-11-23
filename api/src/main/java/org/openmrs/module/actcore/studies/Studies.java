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

/**
 * The settings of the research studies, which {@link org.openmrs.module.actcore.api.StudyService}
 * lists, creates and renames. Each study is a program named after it, whose Study status workflow
 * is a copy of the template program's.
 */
public class Studies {
	
	/**
	 * The name of the global property that holds the uuid of the template program, whose workflows each
	 * new study copies.
	 */
	public static final String TEMPLATE_PROPERTY = "actcore.studyTemplateProgram";
}
