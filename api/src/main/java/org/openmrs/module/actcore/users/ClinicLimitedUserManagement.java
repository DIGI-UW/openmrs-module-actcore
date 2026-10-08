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

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.openmrs.Role;
import org.openmrs.User;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.context.Context;

/**
 * Keeps the UserService changes of a user to the users at the administrator's clinics, if it is
 * clinic-limited, and to the roles it may give (see {@link ClinicUsers}).
 */
public class ClinicLimitedUserManagement implements MethodInterceptor {
	
	private static final List<String> SAVES = Arrays.asList("createUser", "saveUser");
	
	private static final List<String> OTHER_CHANGES = Arrays.asList("retireUser", "unretireUser", "purgeUser",
	    "changePassword", "changeHashedPassword", "changeQuestionAnswer", "setUserProperty", "removeUserProperty",
	    "setUserActivationKey");
	
	private static final List<String> OWN_PROPERTIES = Arrays.asList("saveUserProperty", "saveUserProperties");
	
	@Override
	public Object invoke(MethodInvocation invocation) throws Throwable {
		Object[] args = invocation.getArguments();
		String method = invocation.getMethod().getName();
		User actor = Context.getAuthenticatedUser();
		User target = OWN_PROPERTIES.contains(method) ? actor : null;
		if ((SAVES.contains(method) || OTHER_CHANGES.contains(method)) && args.length > 0 && args[0] instanceof User) {
			target = (User) args[0];
		}
		Set<String> givable = target != null && actor != null ? ClinicUsers.givableRoles() : null;
		if (givable != null) {
			check(method, args, target, actor, givable);
		}
		return invocation.proceed();
	}
	
	private static void check(String method, Object[] args, User target, User actor, Set<String> givable) {
		ClinicUsers.Stored stored = ClinicUsers.Stored.of(target.getUserId());
		Set<String> clinics = clinicsAfter(method, args, target, stored);
		Set<String> roles = SAVES.contains(method) ? ClinicUsers.roleNames(target.getRoles()) : stored.roles;
		if (!ClinicUsers.isClinicLimited()) {
			checkRoles(roles, stored.roles, givable);
			return;
		}
		if (target.getUserId() != null && target.getUserId().equals(actor.getUserId())) {
			// Saving one's own preferences, such as the login location, stays open.
			if (!clinics.equals(stored.clinics) || !roles.equals(stored.roles)) {
				throw refusal("You cannot change your own clinics or roles");
			}
			return;
		}
		Set<String> mine = ClinicUsers.clinicsOf(actor);
		if (mine.isEmpty()) {
			throw refusal("You have no clinics, so you cannot manage users");
		}
		if (target.getUserId() != null) {
			checkManaged(name(target), stored, mine);
		}
		if (clinics.isEmpty()) {
			throw refusal("Give the user at least one of your clinics");
		}
		if (!mine.containsAll(changed(clinics, stored.clinics))) {
			throw refusal("You can add or remove only your own clinics");
		}
		checkRoles(roles, stored.roles, givable);
	}
	
	private static void checkRoles(Set<String> roles, Set<String> before, Set<String> givable) {
		for (String role : changed(roles, before)) {
			if (!givable.contains(role)) {
				throw refusal("You cannot give or remove the role " + role);
			}
		}
	}
	
	/** Refuses a stored user who shares none of {@code mine} or who manages users. */
	static void checkManaged(String name, ClinicUsers.Stored stored, Set<String> mine) {
		if (Collections.disjoint(stored.clinics, mine)) {
			throw refusal(name + " is not at your clinics");
		}
		if (anyManagesUsers(stored.roles)) {
			throw refusal(name + " administers users, which only an instance administrator can change");
		}
	}
	
	private static Set<String> clinicsAfter(String method, Object[] args, User target, ClinicUsers.Stored stored) {
		if (SAVES.contains(method)) {
			return ClinicUsers.clinicsOf(target);
		}
		if ("saveUserProperties".equals(method)) {
			return ClinicUsers.parse((String) ((Map<?, ?>) args[0]).get(ClinicUsers.CLINICS_PROPERTY));
		}
		int key = "saveUserProperty".equals(method) ? 0 : 1;
		boolean property = "saveUserProperty".equals(method) || "setUserProperty".equals(method)
		        || "removeUserProperty".equals(method);
		if (property && ClinicUsers.CLINICS_PROPERTY.equals(args[key])) {
			return "removeUserProperty".equals(method) ? Collections.<String> emptySet()
			        : ClinicUsers.parse((String) args[key + 1]);
		}
		return stored.clinics;
	}
	
	private static Set<String> changed(Set<String> after, Set<String> before) {
		Set<String> changed = new HashSet<>(after);
		changed.addAll(before);
		Set<String> unchanged = new HashSet<>(after);
		unchanged.retainAll(before);
		changed.removeAll(unchanged);
		return changed;
	}
	
	private static boolean anyManagesUsers(Set<String> roleNames) {
		for (String name : roleNames) {
			Role role = ClinicUsers.role(name);
			if (role != null && ClinicUsers.managesUsers(role)) {
				return true;
			}
		}
		return false;
	}
	
	private static String name(User user) {
		return user.getUsername() != null ? user.getUsername() : user.getSystemId();
	}
	
	private static APIAuthenticationException refusal(String message) {
		return new APIAuthenticationException(message);
	}
}
