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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.hibernate.SessionFactory;
import org.openmrs.Privilege;
import org.openmrs.Role;
import org.openmrs.User;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.Daemon;
import org.openmrs.api.db.UserDAO;
import org.openmrs.util.PrivilegeConstants;
import org.openmrs.util.RoleConstants;

/**
 * A user's clinics, the location uuids in its {@link #CLINICS_PROPERTY} user property, and which
 * users and roles an administrator may manage, as in ACT 2.0.
 */
public final class ClinicUsers {
	
	/** Comma-separated location uuids. */
	public static final String CLINICS_PROPERTY = "act.clinics";
	
	/** The distribution creates it; its holders manage users at every clinic. */
	public static final String ALL_CLINICS_PRIVILEGE = "Task: act.users.allClinics";
	
	/** Comma-separated uuids of the roles any administrator may give. */
	public static final String CLINICIAN_ROLES_PROPERTY = "actcore.users.clinicianRoles";
	
	/** Comma-separated uuids of the roles an administrator of every clinic may also give. */
	public static final String SITE_ADMINISTRATOR_ROLES_PROPERTY = "actcore.users.siteAdministratorRoles";
	
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
	
	/**
	 * True unless the current user is a superuser or its roles grant {@link #ALL_CLINICS_PRIVILEGE}:
	 * its roles, not Context.hasPrivilege, which may query roles, and a flush must not query.
	 */
	public static boolean isClinicLimited() {
		User actor = Context.getAuthenticatedUser();
		return actor != null && !actor.isSuperUser() && !actor.hasPrivilege(ALL_CLINICS_PRIVILEGE);
	}
	
	/**
	 * Core's password rules, which an administrator setting a password cannot read itself, with core's
	 * defaults (OpenmrsUtil.validatePassword) when a global property is unset. Read through the
	 * session's connection, like the other properties here: no proxy privilege.
	 */
	public static Map<String, Object> passwordRules() {
		Map<String, Object> rules = new HashMap<>();
		String minimumLength = setting("security.passwordMinimumLength", "8");
		rules.put("minimumLength", StringUtils.isNumeric(minimumLength) ? Integer.parseInt(minimumLength) : 8);
		rules.put("requiresUpperAndLowerCase",
		    "true".equalsIgnoreCase(setting("security.passwordRequiresUpperAndLowerCase", "true")));
		rules.put("requiresDigit", "true".equalsIgnoreCase(setting("security.passwordRequiresDigit", "true")));
		rules.put("requiresNonDigit", "true".equalsIgnoreCase(setting("security.passwordRequiresNonDigit", "true")));
		rules.put("cannotMatchUsername", "true".equalsIgnoreCase(setting("security.passwordCannotMatchUsername", "true")));
		rules.put("customRegex", StringUtils.defaultIfBlank(setting("security.passwordCustomRegex", null), null));
		return rules;
	}
	
	private static String setting(String property, String defaultValue) {
		String value = first(column("select property_value from global_property where property = ?", property));
		return StringUtils.isBlank(value) ? defaultValue : value.trim();
	}
	
	/**
	 * The names of the roles the current user may give, or null for any, read through the connection:
	 * core 2.8's service needs Get Global Properties, which an administrator lacks.
	 */
	public static Set<String> givableRoles() {
		User actor = Context.getAuthenticatedUser();
		if (actor.isSuperUser() || Daemon.isDaemonThread()) {
			return null;
		}
		Set<String> uuids = parse(
		    first(column("select property_value from global_property where property = ?", CLINICIAN_ROLES_PROPERTY)));
		if (!isClinicLimited()) {
			uuids.addAll(parse(first(column("select property_value from global_property where property = ?",
			    SITE_ADMINISTRATOR_ROLES_PROPERTY))));
		}
		Set<String> names = new HashSet<>();
		for (String uuid : uuids) {
			names.addAll(column("select role from role where uuid = ?", uuid));
		}
		return names;
	}
	
	/**
	 * Whether the current user may give the role: one of {@code givable} (null for any), and OpenMRS's
	 * own rule, every privilege of the role and its parents, and a superuser role only by a superuser.
	 */
	public static boolean mayGive(Role role, Set<String> givable) {
		User actor = Context.getAuthenticatedUser();
		if (actor == null) {
			return false;
		}
		if (actor.isSuperUser()) {
			return true;
		}
		if (isSuperUserRole(role) || (givable != null && !givable.contains(role.getRole()))) {
			return false;
		}
		for (Privilege privilege : allPrivileges(role)) {
			if (!actor.hasPrivilege(privilege.getPrivilege())) {
				return false;
			}
		}
		return true;
	}
	
	/**
	 * Every role, read without granting Manage Roles: holding it, even briefly, lets the session edit
	 * roles.
	 */
	public static List<Role> allRoles() {
		return userDao().getAllRoles();
	}
	
	private static UserDAO userDao() {
		return Context.getRegisteredComponent("userDAO", UserDAO.class);
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
	
	private static Set<Privilege> allPrivileges(Role role) {
		Set<Privilege> privileges = new HashSet<>();
		for (Role each : withParents(role)) {
			if (each.getPrivileges() != null) {
				privileges.addAll(each.getPrivileges());
			}
		}
		return privileges;
	}
	
	/** A user's row, properties, clinics and role names as the database holds them. */
	public static final class Stored {
		
		static final Stored NONE = new Stored(null, null, Collections.<String, String> emptyMap(),
		        Collections.<String> emptySet());
		
		public final String name;
		
		public final Integer personId;
		
		public final Map<String, String> properties;
		
		public final Set<String> clinics;
		
		public final Set<String> roles;
		
		private Stored(String name, Integer personId, Map<String, String> properties, Set<String> roles) {
			this.name = name;
			this.personId = personId;
			this.properties = properties;
			this.clinics = parse(properties.get(CLINICS_PROPERTY));
			this.roles = roles;
		}
		
		/**
		 * Reads through the current transaction's connection, without flushing the session: REST and the
		 * legacy form change the user object before they save it, so the object no longer says what it was.
		 */
		public static Stored of(Integer userId) {
			if (userId == null) {
				return NONE;
			}
			List<String[]> user = rows(
			    "select coalesce(nullif(username, ''), system_id), person_id from users where user_id = ?", userId);
			Map<String, String> properties = new HashMap<>();
			for (String[] row : rows("select property, property_value from user_property where user_id = ?", userId)) {
				properties.put(row[0], row[1]);
			}
			return new Stored(user.isEmpty() ? null : user.get(0)[0],
			        user.isEmpty() ? null : Integer.valueOf(user.get(0)[1]), properties,
			        new HashSet<>(column("select role from user_role where user_id = ?", userId)));
		}
	}
	
	/** Every role's parents and privileges as the database holds them, read in two queries. */
	static final class Roles {
		
		private final Map<String, Set<String>> parents = new HashMap<>();
		
		private final Map<String, Set<String>> privileges = new HashMap<>();
		
		static Roles stored() {
			Roles roles = new Roles();
			for (String[] row : rows("select child_role, parent_role from role_role")) {
				roles.parents.computeIfAbsent(row[0], role -> new HashSet<>()).add(row[1]);
			}
			for (String[] row : rows("select role, privilege from role_privilege")) {
				roles.privileges.computeIfAbsent(row[0], role -> new HashSet<>()).add(row[1]);
			}
			return roles;
		}
		
		/** Whether the roles, with the roles they inherit, can create or edit users. */
		boolean manageUsers(Set<String> names) {
			Set<String> roles = withParents(names);
			Set<String> held = privilegesOf(roles);
			return roles.contains(RoleConstants.SUPERUSER) || held.contains(PrivilegeConstants.EDIT_USERS)
			        || held.contains(PrivilegeConstants.ADD_USERS);
		}
		
		/**
		 * Whether the roles, with the roles they inherit, include a superuser role or a privilege the
		 * actor's roles lack: OpenMRS's rule for giving a role, applied to whoever holds them.
		 */
		boolean outrank(User actor, Set<String> names) {
			if (actor.isSuperUser()) {
				return false;
			}
			Set<String> roles = withParents(names);
			Set<String> actorRoles = roleNames(actor.getRoles());
			actorRoles.add(RoleConstants.AUTHENTICATED);
			actorRoles.add(RoleConstants.ANONYMOUS);
			return roles.contains(RoleConstants.SUPERUSER)
			        || !privilegesOf(withParents(actorRoles)).containsAll(privilegesOf(roles));
		}
		
		private Set<String> withParents(Set<String> names) {
			Set<String> roles = new HashSet<>();
			Deque<String> unread = new ArrayDeque<>(names);
			while (!unread.isEmpty()) {
				String role = unread.pop();
				if (roles.add(role)) {
					unread.addAll(parents.getOrDefault(role, Collections.<String> emptySet()));
				}
			}
			return roles;
		}
		
		private Set<String> privilegesOf(Set<String> roles) {
			Set<String> held = new HashSet<>();
			for (String role : roles) {
				held.addAll(privileges.getOrDefault(role, Collections.<String> emptySet()));
			}
			return held;
		}
	}
	
	/** The ids of a person's users, read as {@link Stored#of} reads. */
	static List<Integer> usersOf(Integer personId) {
		List<Integer> users = new ArrayList<>();
		for (String id : column("select user_id from users where person_id = ?", personId)) {
			users.add(Integer.valueOf(id));
		}
		return users;
	}
	
	private static String first(List<String> values) {
		return values.isEmpty() ? null : values.get(0);
	}
	
	static List<String> column(String sql, Object... parameters) {
		List<String> values = new ArrayList<>();
		for (String[] row : rows(sql, parameters)) {
			values.add(row[0]);
		}
		return values;
	}
	
	private static List<String[]> rows(String sql, Object... parameters) {
		SessionFactory sessions = Context.getRegisteredComponent("sessionFactory", SessionFactory.class);
		return sessions.getCurrentSession().doReturningWork(connection -> {
			List<String[]> rows = new ArrayList<>();
			try (PreparedStatement query = connection.prepareStatement(sql)) {
				for (int i = 0; i < parameters.length; i++) {
					query.setObject(i + 1, parameters[i]);
				}
				try (ResultSet results = query.executeQuery()) {
					int columns = results.getMetaData().getColumnCount();
					while (results.next()) {
						String[] row = new String[columns];
						for (int i = 0; i < columns; i++) {
							row[i] = results.getString(i + 1);
						}
						rows.add(row);
					}
				}
			}
			return rows;
		});
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
