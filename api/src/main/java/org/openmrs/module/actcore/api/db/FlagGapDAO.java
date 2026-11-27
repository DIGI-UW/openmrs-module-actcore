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

import java.util.List;

/**
 * The database access behind {@link org.openmrs.module.actcore.api.FlagGapService}, which runs a
 * flag's own criteria SQL, as the patientflags module's SQL evaluator runs it.
 */
public interface FlagGapDAO {
	
	/**
	 * Runs a select over JDBC on the current session's connection.
	 *
	 * @param query the flag's criteria, rewritten for one patient
	 * @return each row as a list of its column values, as JDBC returns them; empty when there are none
	 * @throws IllegalArgumentException when the query is not a select
	 */
	List<List<Object>> getCriteriaRows(String query);
}
