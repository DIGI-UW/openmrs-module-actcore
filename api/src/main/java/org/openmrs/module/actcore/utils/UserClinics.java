/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.openmrs.Role;
import org.openmrs.User;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.module.actcore.ActCoreConstants;
import org.openmrs.module.actcore.ActCorePrivileges;
import org.openmrs.module.actcore.api.UserClinicsService;
import org.openmrs.util.PrivilegeConstants;

/**
 * Helpers that read the clinics on a user's account, kept in the
 * {@value ActCoreConstants#USER_PROPERTY_CLINICS} user property, and tell whether a user is an
 * administrator limited to their clinics. The README describes both. The class also holds the rules
 * that limit such an administrator's changes to a user. The clinics and roles saved in the database
 * are read through {@link UserClinicsService}.
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
	
	/**
	 * Refuses a limited administrator's change to a user unless the rules allow it, comparing the user
	 * as changed with what is saved, which it reads through the service given. On their own account the
	 * administrator may not change their clinics or roles. On another user's, a user already saved must
	 * share one of their clinics, the administrator may give or take away only their own clinics, and
	 * the user must keep at least one of them. There too, when
	 * {@value ActCoreConstants#GP_USERS_SITE_ADMINISTRATOR_ROLES} lists roles, a user already saved
	 * must hold only those roles, and the user as changed may have only those.
	 *
	 * @param me the limited administrator making the change
	 * @param user the user as changed
	 * @param clinicsAfter the clinics the user will have, or null to read them from the user
	 * @param clinics the service that reads the user's and the administrator's saved clinics and roles
	 * @throws APIAuthenticationException when the rules do not allow the change
	 */
	public static void refuseUnlessMayChange(User me, User user, Set<String> clinicsAfter, UserClinicsService clinics) {
		Set<String> after = clinicsAfter != null ? clinicsAfter : of(user);
		Set<String> mine = clinics.getStoredClinics(me.getUserId());
		if (me.getUserId() != null && me.getUserId().equals(user.getUserId())) {
			if (!after.equals(mine)) {
				throw refusal("You cannot change your own clinics. Ask an instance administrator");
			}
			if (!roleUuids(user).equals(clinics.getStoredRoles(me.getUserId()))) {
				throw refusal("You cannot change your own roles. Ask an instance administrator");
			}
			return;
		}
		Set<String> assignable = clinics.getRolesIn(ActCoreConstants.GP_USERS_SITE_ADMINISTRATOR_ROLES);
		if (user.getUserId() != null) {
			if (!share(clinics.getStoredClinics(user.getUserId()), mine)) {
				throw refusal("This user is not at one of your clinics, so you cannot change them");
			}
			if (!assignable.isEmpty() && !assignable.containsAll(clinics.getStoredRoles(user.getUserId()))) {
				throw refusal(
				    "This user has a role you cannot give out, so only an instance administrator can change" + " them");
			}
		}
		// As in ACT 2.0 an overlap is enough, so the clinics that are not theirs must stay as stored.
		Set<String> othersBefore = new HashSet<String>(clinics.getStoredClinics(user.getUserId()));
		othersBefore.removeAll(mine);
		Set<String> othersAfter = new HashSet<String>(after);
		othersAfter.removeAll(mine);
		if (!othersAfter.equals(othersBefore)) {
			throw refusal("You can give or take away only your own clinics");
		}
		if (!share(after, mine)) {
			throw refusal("Give the user at least one of your clinics");
		}
		if (!assignable.isEmpty() && !assignable.containsAll(roleUuids(user))) {
			throw refusal("You can give a user only the clinician roles: " + roleNames(assignable, clinics));
		}
	}
	
	private static String roleNames(Set<String> uuids, UserClinicsService clinics) {
		List<String> names = new ArrayList<String>();
		for (String name : clinics.getRoleNames(uuids)) {
			names.add(name.replaceFirst("^Organizational: ACT ", ""));
		}
		return String.join(", ", names);
	}
	
	private static APIAuthenticationException refusal(String message) {
		return new APIAuthenticationException(message);
	}
}
