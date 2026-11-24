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
import java.util.Map;

/**
 * The database access behind {@link org.openmrs.module.actcore.api.AuditLogService}, which reads
 * the audit log module's and Envers' tables by SQL. The logs read users, patients and rows this way
 * rather than through the services whose reads the audit log module records, so that listing a log
 * does not itself fill the read audit.
 */
public interface AuditLogDAO {
	
	/**
	 * Runs a query over JDBC on the current session's connection and returns its rows. The maps look
	 * columns up by label ignoring case, so code reads H2's upper-case names and MariaDB's declared
	 * names alike, and date and datetime values come back as java.sql.Date and Timestamp from both
	 * databases.
	 *
	 * @param sql the query, with a ? for each parameter; it must come from ACT Core's own code
	 * @param params the parameters, in order
	 * @return each row as a map of column label to value; empty when there are none
	 */
	List<Map<String, Object>> select(String sql, Object... params);
}
