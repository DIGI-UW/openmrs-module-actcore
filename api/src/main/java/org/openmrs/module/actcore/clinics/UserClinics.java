/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.clinics;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.openmrs.Role;
import org.openmrs.User;
import org.openmrs.module.actcore.ActCoreConstants;
import org.openmrs.module.actcore.ActCorePrivileges;
import org.openmrs.util.PrivilegeConstants;

/**
 * Helpers that read the clinics on a user's account, kept in the
 * {@value ActCoreConstants#USER_PROPERTY_CLINICS} user property, and tell whether a user is an
 * administrator limited to their clinics. The README describes both. The clinics and roles saved in
 * the database are read through {@link org.openmrs.module.actcore.api.UserClinicsService}.
 */
public final class UserClinics {
	
	private UserClinics() {
	}
	
	/**
	 * Parses a {@value ActCoreConstants#USER_PROPERTY_CLINICS} value into its location uuids, keeping
	 * their order and dropping blanks and repeats.
	 *
	 * @param value the property value, or null
	 * @return the uuids, trimmed; empty when the value is null or blank
	 */
	public static Set<String> parse(String value) {
		Set<String> clinics = new LinkedHashSet<String>();
		if (value == null) {
			return clinics;
		}
		for (String clinic : value.split(",")) {
			if (StringUtils.isNotBlank(clinic)) {
				clinics.add(clinic.trim());
			}
		}
		return clinics;
	}
	
	/**
	 * Gets the clinics on the user's account as they stand on the object, whether or not they have been
	 * saved.
	 *
	 * @param user the user, or null
	 * @return the clinics' location uuids; empty for a null user or one without the property
	 */
	public static Set<String> of(User user) {
		return user == null ? Collections.<String> emptySet()
		        : parse(user.getUserProperty(ActCoreConstants.USER_PROPERTY_CLINICS));
	}
	
	/**
	 * Tells whether the user is an administrator limited to their clinics: one who may add or edit
	 * users but lacks {@value ActCorePrivileges#ALL_CLINICS}. A super user is not limited.
	 *
	 * @param user the user, or null
	 * @return true for an administrator limited to their clinics; false for anyone else, a super user
	 *         or a null user
	 */
	public static boolean isLimited(User user) {
		if (user == null || user.isSuperUser()) {
			return false;
		}
		boolean administersUsers = user.hasPrivilege(PrivilegeConstants.EDIT_USERS)
		        || user.hasPrivilege(PrivilegeConstants.ADD_USERS);
		return administersUsers && !user.hasPrivilege(ActCorePrivileges.ALL_CLINICS);
	}
	
	/**
	 * Tells whether the two sets of clinics have at least one clinic in common.
	 *
	 * @param some one set of location uuids
	 * @param others the other
	 * @return true when a clinic is in both; false when either set is empty
	 */
	public static boolean share(Set<String> some, Set<String> others) {
		for (String clinic : some) {
			if (others.contains(clinic)) {
				return true;
			}
		}
		return false;
	}
	
	/**
	 * Gets the uuids of the user's roles as they stand on the object, whether or not they have been
	 * saved.
	 *
	 * @param user the user, or null
	 * @return the uuids; empty for a null user or one without roles
	 */
	public static Set<String> roleUuids(User user) {
		Set<String> uuids = new LinkedHashSet<String>();
		if (user != null && user.getRoles() != null) {
			for (Role role : user.getRoles()) {
				uuids.add(role.getUuid());
			}
		}
		return uuids;
	}
}
