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
 * The database access behind {@link org.openmrs.module.actcore.api.UserClinicsService}, which reads
 * user properties, global properties and roles. It reads over JDBC on the current session's
 * connection, so that Hibernate does not flush the session first. That lets the Hibernate
 * interceptor that checks a user's change call it in the middle of a flush.
 */
public interface UserClinicsDAO {
	
	/**
	 * Gets the value of a user property as saved.
	 *
	 * @param userId the user's id
	 * @param property the property's name
	 * @return the value; null when the user has no such property
	 */
	String getUserProperty(Integer userId, String property);
	
	/**
	 * Gets the value of a global property as saved.
	 *
	 * @param property the property's name
	 * @return the value; null when the property is not set
	 */
	String getGlobalProperty(String property);
	
	/**
	 * Gets the uuids of the roles saved on a user.
	 *
	 * @param userId the user's id
	 * @return the uuids; empty when the user has no saved roles
	 */
	List<String> getRoleUuids(Integer userId);
	
	/**
	 * Gets the name of the role with this uuid.
	 *
	 * @param uuid the role's uuid
	 * @return the name; null when no role has the uuid
	 */
	String getRoleName(String uuid);
}
