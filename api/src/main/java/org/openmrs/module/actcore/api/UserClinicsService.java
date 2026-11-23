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
import java.util.Set;

import org.openmrs.annotation.Authorized;
import org.openmrs.api.OpenmrsService;
import org.openmrs.module.actcore.clinics.UserClinics;
import org.openmrs.util.PrivilegeConstants;

/**
 * The service that reads the clinics and roles saved on users' accounts, and the roles that a
 * global property lists, for the advice on UserService that checks a change to a user. It reads the
 * database rather than the objects, which may hold unsaved edits, and its reads do not flush the
 * session, so a read made while a user is being changed does not write that change first. Every
 * method needs Add Users or Edit Users, which every caller holds: the advice reads only for an
 * administrator limited to their clinics, or one about to save a user.
 */
public interface UserClinicsService extends OpenmrsService {
	
	/**
	 * Gets the clinics saved on a user's account.
	 *
	 * @param userId the user's id, or null for a user not yet saved
	 * @return the clinics' location uuids, in order and without blanks or repeats; empty for a null id
	 *         or a user without the {@value UserClinics#PROPERTY} property
	 * @throws org.openmrs.api.APIAuthenticationException when the user holds neither
	 *             {@link PrivilegeConstants#ADD_USERS} nor {@link PrivilegeConstants#EDIT_USERS}
	 */
	@Authorized({ PrivilegeConstants.ADD_USERS, PrivilegeConstants.EDIT_USERS })
	Set<String> getStoredClinics(Integer userId);
	
	/**
	 * Gets the uuids of the roles saved on a user.
	 *
	 * @param userId the user's id, or null for a user not yet saved
	 * @return the uuids; empty for a null id or a user without saved roles
	 * @throws org.openmrs.api.APIAuthenticationException when the user holds neither
	 *             {@link PrivilegeConstants#ADD_USERS} nor {@link PrivilegeConstants#EDIT_USERS}
	 */
	@Authorized({ PrivilegeConstants.ADD_USERS, PrivilegeConstants.EDIT_USERS })
	Set<String> getStoredRoles(Integer userId);
	
	/**
	 * Gets the role uuids that a global property lists, separated by commas.
	 *
	 * @param property the global property's name
	 * @return the uuids, in order and without blanks or repeats; empty when the property is unset or
	 *         blank
	 * @throws org.openmrs.api.APIAuthenticationException when the user holds neither
	 *             {@link PrivilegeConstants#ADD_USERS} nor {@link PrivilegeConstants#EDIT_USERS}
	 */
	@Authorized({ PrivilegeConstants.ADD_USERS, PrivilegeConstants.EDIT_USERS })
	Set<String> getRolesIn(String property);
	
	/**
	 * Gets the names of the roles with these uuids, for use in messages.
	 *
	 * @param uuids the role uuids
	 * @return a name for each uuid, in the order given; a uuid that no role has is given as it is
	 * @throws org.openmrs.api.APIAuthenticationException when the user holds neither
	 *             {@link PrivilegeConstants#ADD_USERS} nor {@link PrivilegeConstants#EDIT_USERS}
	 */
	@Authorized({ PrivilegeConstants.ADD_USERS, PrivilegeConstants.EDIT_USERS })
	List<String> getRoleNames(Set<String> uuids);
}
