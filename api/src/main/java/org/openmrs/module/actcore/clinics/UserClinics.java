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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.hibernate.SessionFactory;
import org.hibernate.jdbc.ReturningWork;
import org.openmrs.Role;
import org.openmrs.User;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.ActCorePrivileges;
import org.openmrs.util.PrivilegeConstants;

/**
 * Helpers that read the clinics on a user's account, kept in the {@value #PROPERTY} user property,
 * and tell whether a user is an administrator limited to their clinics. The README describes both.
 */
public final class UserClinics {
	
	/**
	 * The name of the user property that holds the user's clinics, as location uuids separated by
	 * commas.
	 */
	public static final String PROPERTY = "actcore.clinics";
	
	/**
	 * The privilege that lets a user who administers users work with every clinic. The instance and
	 * global administrators hold it; an administrator of users without it is limited to their clinics.
	 */
	public static final String ALL_CLINICS_PRIVILEGE = ActCorePrivileges.ALL_CLINICS;
	
	/**
	 * The name of the global property that lists, by uuid and separated by commas, the roles that an
	 * administrator limited to their clinics may give out.
	 */
	public static final String ASSIGNABLE_ROLES_PROPERTY = "actcore.users.siteAdministratorRoles";
	
	private UserClinics() {
	}
	
	/**
	 * Parses a {@value #PROPERTY} value into its location uuids, keeping their order and dropping
	 * blanks and repeats.
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
		return user == null ? Collections.<String> emptySet() : parse(user.getUserProperty(PROPERTY));
	}
	
	/**
	 * Tells whether the user is an administrator limited to their clinics: one who may add or edit
	 * users but lacks {@value #ALL_CLINICS_PRIVILEGE}. A super user is not limited.
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
		return administersUsers && !user.hasPrivilege(ALL_CLINICS_PRIVILEGE);
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
	 * Gets the uuids of the roles that an administrator limited to their clinics may give out. An empty
	 * set means ACT Core does not restrict the roles, and leaves them to OpenMRS's own checks.
	 *
	 * @return the role uuids in {@value #ASSIGNABLE_ROLES_PROPERTY}; empty when it is unset or blank
	 */
	public static Set<String> assignableRoles() {
		return rolesIn(ASSIGNABLE_ROLES_PROPERTY);
	}
	
	/**
	 * Reads the role uuids that a global property lists, by SQL, so that reading does not flush the
	 * session.
	 *
	 * @param property the global property's name
	 * @return the uuids, in order and without blanks or repeats; empty when the property is unset or
	 *         blank
	 */
	private static Set<String> rolesIn(String property) {
		List<String> values = column("select property_value from global_property where property = ?", property);
		return parse(values.isEmpty() ? null : values.get(0));
	}
	
	/**
	 * Gets the names of the roles with these uuids, for use in messages. A uuid that no role has is
	 * given as it is.
	 *
	 * @param uuids the role uuids
	 * @return a name for each uuid, in the order given
	 */
	public static List<String> roleNames(Set<String> uuids) {
		List<String> names = new ArrayList<String>();
		for (String uuid : uuids) {
			List<String> found = column("select role from role where uuid = ?", uuid);
			names.add(found.isEmpty() ? uuid : found.get(0));
		}
		return names;
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
	 * Gets the clinics saved on a user's account, read from the database rather than from an object
	 * that may hold unsaved edits.
	 *
	 * @param userId the user's id, or null for a user not yet saved
	 * @return the clinics' location uuids; empty for a null id or a user without the property
	 */
	public static Set<String> stored(Integer userId) {
		if (userId == null) {
			return Collections.emptySet();
		}
		List<String> values = column("select property_value from user_property where user_id = ? and property = ?", userId,
		    PROPERTY);
		return values.isEmpty() ? Collections.<String> emptySet() : parse(values.get(0));
	}
	
	/**
	 * Gets the uuids of the roles saved on a user, read from the database rather than from an object
	 * that may hold unsaved edits.
	 *
	 * @param userId the user's id, or null for a user not yet saved
	 * @return the uuids; empty for a null id or a user without saved roles
	 */
	public static Set<String> storedRoles(Integer userId) {
		if (userId == null) {
			return new LinkedHashSet<String>();
		}
		return new LinkedHashSet<String>(
		        column("select r.uuid from user_role ur join role r on r.role = ur.role where ur.user_id = ?", userId));
	}
	
	/**
	 * Runs a query over JDBC on the current session's connection and returns the values of its first
	 * column. Going through JDBC keeps Hibernate from flushing the session first.
	 */
	private static List<String> column(final String query, final Object... params) {
		SessionFactory sessionFactory = Context.getRegisteredComponents(SessionFactory.class).get(0);
		return sessionFactory.getCurrentSession().doReturningWork(new ReturningWork<List<String>>() {
			
			@Override
			public List<String> execute(Connection connection) throws SQLException {
				List<String> values = new ArrayList<String>();
				PreparedStatement statement = connection.prepareStatement(query);
				try {
					for (int i = 0; i < params.length; i++) {
						statement.setObject(i + 1, params[i]);
					}
					ResultSet rows = statement.executeQuery();
					while (rows.next()) {
						values.add(rows.getString(1));
					}
				}
				finally {
					statement.close();
				}
				return values;
			}
		});
	}
}
