/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.users;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.hibernate.SessionFactory;
import org.openmrs.Privilege;
import org.openmrs.Role;
import org.openmrs.User;
import org.openmrs.api.context.Context;
import org.openmrs.util.PrivilegeConstants;
import org.openmrs.util.RoleConstants;

/**
 * A user's clinics, and who may manage which users. A user's clinics are the location uuids in its
 * {@link #CLINICS_PROPERTY} user property. An administrator without {@link #ALL_CLINICS_PRIVILEGE}
 * manages only users at its own clinics, and gives only roles that don't manage users, as in ACT
 * 2.0.
 */
public final class ClinicUsers {
	
	/** Comma-separated location uuids. */
	public static final String CLINICS_PROPERTY = "act.clinics";
	
	/** The distribution creates it; its holders manage users at every clinic. */
	public static final String ALL_CLINICS_PRIVILEGE = "Task: act.users.allClinics";
	
	private ClinicUsers() {
	}
	
	public static Set<String> parse(String clinics) {
		Set<String> uuids = new LinkedHashSet<>();
		for (String uuid : StringUtils.split(StringUtils.defaultString(clinics), ',')) {
			if (StringUtils.isNotBlank(uuid)) {
				uuids.add(uuid.trim());
			}
		}
		return uuids;
	}
	
	public static Set<String> clinicsOf(User user) {
		return parse(user.getUserProperty(CLINICS_PROPERTY));
	}
	
	/** True unless the current user is a superuser or holds {@link #ALL_CLINICS_PRIVILEGE}. */
	public static boolean isClinicLimited() {
		User actor = Context.getAuthenticatedUser();
		return actor != null && !actor.isSuperUser() && !Context.hasPrivilege(ALL_CLINICS_PRIVILEGE);
	}
	
	/** Whether the role, with the roles it inherits, can create or edit users. */
	public static boolean managesUsers(Role role) {
		return isSuperUserRole(role) || grants(role, PrivilegeConstants.EDIT_USERS)
		        || grants(role, PrivilegeConstants.ADD_USERS);
	}
	
	/**
	 * Whether the current user may give the role: OpenMRS's own rule (every privilege of the role and
	 * the roles it inherits, and a superuser role only by a superuser), and, if clinic-limited, ACT
	 * 2.0's.
	 */
	public static boolean mayGive(Role role) {
		User actor = Context.getAuthenticatedUser();
		if (actor == null) {
			return false;
		}
		if (actor.isSuperUser()) {
			return true;
		}
		if (isSuperUserRole(role) || (isClinicLimited() && managesUsers(role))) {
			return false;
		}
		for (Privilege privilege : allPrivileges(role)) {
			if (!actor.hasPrivilege(privilege.getPrivilege())) {
				return false;
			}
		}
		return true;
	}
	
	private static boolean isSuperUserRole(Role role) {
		for (Role each : withParents(role)) {
			if (RoleConstants.SUPERUSER.equals(each.getRole())) {
				return true;
			}
		}
		return false;
	}
	
	private static Set<Role> withParents(Role role) {
		Set<Role> roles = new HashSet<>(role.getAllParentRoles());
		roles.add(role);
		return roles;
	}
	
	private static boolean grants(Role role, String privilege) {
		for (Privilege held : allPrivileges(role)) {
			if (privilege.equals(held.getPrivilege())) {
				return true;
			}
		}
		return false;
	}
	
	private static Set<Privilege> allPrivileges(Role role) {
		Set<Privilege> privileges = new HashSet<>();
		for (Role each : withParents(role)) {
			if (each.getPrivileges() != null) {
				privileges.addAll(each.getPrivileges());
			}
		}
		return privileges;
	}
	
	/** A user's clinics and role names as the database holds them, before the edit being saved. */
	public static final class Stored {
		
		public final Set<String> clinics;
		
		public final Set<String> roles;
		
		private Stored(Set<String> clinics, Set<String> roles) {
			this.clinics = clinics;
			this.roles = roles;
		}
		
		/**
		 * Reads through the current transaction's connection, without flushing the session: REST and the
		 * legacy form change the user object before they save it, so the object no longer says what it was.
		 */
		public static Stored of(Integer userId) {
			if (userId == null) {
				return new Stored(Collections.<String> emptySet(), Collections.<String> emptySet());
			}
			SessionFactory sessions = Context.getRegisteredComponent("sessionFactory", SessionFactory.class);
			return sessions.getCurrentSession().doReturningWork(connection -> {
				Set<String> clinics = Collections.emptySet();
				try (PreparedStatement query = connection
				        .prepareStatement("select property_value from user_property where user_id = ? and property = ?")) {
					query.setInt(1, userId);
					query.setString(2, CLINICS_PROPERTY);
					try (ResultSet rows = query.executeQuery()) {
						if (rows.next()) {
							clinics = parse(rows.getString(1));
						}
					}
				}
				Set<String> roles = new HashSet<>();
				try (PreparedStatement query = connection.prepareStatement("select role from user_role where user_id = ?")) {
					query.setInt(1, userId);
					try (ResultSet rows = query.executeQuery()) {
						while (rows.next()) {
							roles.add(rows.getString(1));
						}
					}
				}
				return new Stored(clinics, roles);
			});
		}
	}
	
	static Set<String> roleNames(Collection<Role> roles) {
		Set<String> names = new HashSet<>();
		if (roles != null) {
			for (Role role : roles) {
				names.add(role.getRole());
			}
		}
		return names;
	}
}
