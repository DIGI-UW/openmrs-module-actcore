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
import org.openmrs.util.PrivilegeConstants;

/**
 * The clinics on a user's account, as ACT 2.0 gave each user, and whether the signed-in user is an
 * administrator limited to them.
 * <p>
 * A user's clinics are the location uuids in the {@value #PROPERTY} user property, separated by
 * commas. An administrator is limited to their clinics when they may add or edit users but do not
 * hold {@value #ALL_CLINICS_PRIVILEGE}: in ACT, the site administrator.
 */
public final class UserClinics {
	
	/** The user property holding the user's clinics, as location uuids separated by commas. */
	public static final String PROPERTY = "actcore.clinics";
	
	/** Held by the instance and global administrators, who work with every clinic. */
	public static final String ALL_CLINICS_PRIVILEGE = "Task: act.allClinics";
	
	/** The roles, by uuid, that an administrator limited to their clinics may give out. */
	public static final String ASSIGNABLE_ROLES_PROPERTY = "actcore.users.siteAdministratorRoles";
	
	private UserClinics() {
	}
	
	/** The location uuids in a {@value #PROPERTY} value, in order and without blanks or repeats. */
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
	
	/** The clinics on the user's account, as they stand on the object, saved or not. */
	public static Set<String> of(User user) {
		return user == null ? Collections.<String> emptySet() : parse(user.getUserProperty(PROPERTY));
	}
	
	/**
	 * Whether the user is an administrator limited to the clinics on their account: they may add or
	 * edit users, and do not hold {@value #ALL_CLINICS_PRIVILEGE}. A super user holds every privilege,
	 * so is never limited.
	 */
	public static boolean isLimited(User user) {
		if (user == null || user.isSuperUser()) {
			return false;
		}
		boolean administersUsers = user.hasPrivilege(PrivilegeConstants.EDIT_USERS)
		        || user.hasPrivilege(PrivilegeConstants.ADD_USERS);
		return administersUsers && !user.hasPrivilege(ALL_CLINICS_PRIVILEGE);
	}
	
	/** Whether the two sets of clinics have one in common. */
	public static boolean share(Set<String> some, Set<String> others) {
		for (String clinic : some) {
			if (others.contains(clinic)) {
				return true;
			}
		}
		return false;
	}
	
	/**
	 * The roles, by uuid, a limited administrator may give out, from
	 * {@value #ASSIGNABLE_ROLES_PROPERTY}; empty when the property is blank, which leaves roles to
	 * OpenMRS's own rule.
	 */
	public static Set<String> assignableRoles() {
		List<String> values = column("select property_value from global_property where property = ?",
		    ASSIGNABLE_ROLES_PROPERTY);
		return parse(values.isEmpty() ? null : values.get(0));
	}
	
	/** The names of the roles with these uuids, for messages; a uuid with no role stays as it is. */
	public static List<String> roleNames(Set<String> uuids) {
		List<String> names = new ArrayList<String>();
		for (String uuid : uuids) {
			List<String> found = column("select role from role where uuid = ?", uuid);
			names.add(found.isEmpty() ? uuid : found.get(0));
		}
		return names;
	}
	
	/** The uuids of the user's roles on the object, saved or not. */
	public static Set<String> roleUuids(User user) {
		Set<String> uuids = new LinkedHashSet<String>();
		if (user != null && user.getRoles() != null) {
			for (Role role : user.getRoles()) {
				uuids.add(role.getUuid());
			}
		}
		return uuids;
	}
	
	/** The clinics saved on a user's account, read from the database rather than the edited object. */
	public static Set<String> stored(Integer userId) {
		if (userId == null) {
			return Collections.emptySet();
		}
		List<String> values = column("select property_value from user_property where user_id = ? and property = ?", userId,
		    PROPERTY);
		return values.isEmpty() ? Collections.<String> emptySet() : parse(values.get(0));
	}
	
	/** The uuids of the roles saved on a user, read from the database rather than the edited object. */
	public static Set<String> storedRoles(Integer userId) {
		if (userId == null) {
			return new LinkedHashSet<String>();
		}
		return new LinkedHashSet<String>(
		        column("select r.uuid from user_role ur join role r on r.role = ur.role where ur.user_id = ?", userId));
	}
	
	/**
	 * The first column of a query, run over JDBC on the current session's connection: it sees this
	 * transaction's writes, and unlike a service call or HQL it never flushes the session, so it can
	 * run while Hibernate flushes and reads what the database held before that flush.
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
