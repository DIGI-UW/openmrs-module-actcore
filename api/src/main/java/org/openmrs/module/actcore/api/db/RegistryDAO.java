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

import java.util.Collection;
import java.util.List;

/**
 * The database access of the RHD Registry's advice and start-up work, which no service wraps. Its
 * callers run each read in a transaction of their own or join the one in progress.
 */
public interface RegistryDAO {
	
	/**
	 * Gets the patients recorded dead, and not voided, who have an unvoided enrolment that is not
	 * completed in one of the programs.
	 *
	 * @param programIds the programs' ids; at least one
	 * @return the patients' ids, each once; empty when there are none
	 */
	List<Integer> getDeadPatientsWithOpenEnrolments(Collection<Integer> programIds);
}
