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
import java.util.Set;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.openmrs.Role;
import org.openmrs.User;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.context.Context;
import org.openmrs.util.PrivilegeConstants;

/**
 * Keeps a clinic-limited administrator (see {@link ClinicUsers}) to the users at its clinics, as
 * ACT 2.0 did. OpenMRS has no clinic for a user, so without this a site administrator edits any
 * user whose roles it could give, at every clinic. Guards every UserService method that changes
 * another user, whichever screen or REST call reaches it.
 */
public class ClinicLimitedUserManagement implements MethodInterceptor {
	
	private static final List<String> SAVES = Arrays.asList("createUser", "saveUser");
	
	private static final List<String> OTHER_CHANGES = Arrays.asList("retireUser", "unretireUser", "purgeUser",
	    "changePassword", "changeHashedPassword", "setUserProperty", "removeUserProperty", "setUserActivationKey");
	
	@Override
	public Object invoke(MethodInvocation invocation) throws Throwable {
		Object[] args = invocation.getArguments();
		String method = invocation.getMethod().getName();
		boolean guarded = SAVES.contains(method) || OTHER_CHANGES.contains(method);
		if (guarded && args.length > 0 && args[0] instanceof User && ClinicUsers.isClinicLimited()) {
			check(method, args, (User) args[0]);
		}
		return invocation.proceed();
	}
	
	private static void check(String method, Object[] args, User target) {
		User actor = Context.getAuthenticatedUser();
		ClinicUsers.Stored stored = ClinicUsers.Stored.of(target.getUserId());
		boolean saving = SAVES.contains(method);
		Set<String> clinics = saving ? ClinicUsers.clinicsOf(target) : stored.clinics;
		if (isClinicsProperty(method, args)) {
			clinics = "setUserProperty".equals(method) ? ClinicUsers.parse((String) args[2]) : Collections.emptySet();
		}
		Set<String> roles = saving ? ClinicUsers.roleNames(target.getRoles()) : stored.roles;
		
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
			if (Collections.disjoint(stored.clinics, mine)) {
				throw refusal(name(target) + " is not at your clinics");
			}
			if (anyManagesUsers(stored.roles)) {
				throw refusal(name(target) + " administers users, which only an instance administrator can change");
			}
		}
		if (clinics.isEmpty()) {
			throw refusal("Give the user at least one of your clinics");
		}
		Set<String> changed = new HashSet<>(clinics);
		changed.addAll(stored.clinics);
		Set<String> unchanged = new HashSet<>(clinics);
		unchanged.retainAll(stored.clinics);
		changed.removeAll(unchanged);
		if (!mine.containsAll(changed)) {
			throw refusal("You can add or remove only your own clinics");
		}
		if (anyManagesUsers(roles)) {
			throw refusal("You can give only roles that do not manage users");
		}
	}
	
	private static boolean isClinicsProperty(String method, Object[] args) {
		return ("setUserProperty".equals(method) || "removeUserProperty".equals(method)) && args.length > 1
		        && ClinicUsers.CLINICS_PROPERTY.equals(args[1]);
	}
	
	private static boolean anyManagesUsers(Set<String> roleNames) {
		Context.addProxyPrivilege(PrivilegeConstants.GET_ROLES);
		try {
			for (String name : roleNames) {
				Role role = Context.getUserService().getRole(name);
				if (role != null && ClinicUsers.managesUsers(role)) {
					return true;
				}
			}
			return false;
		}
		finally {
			Context.removeProxyPrivilege(PrivilegeConstants.GET_ROLES);
		}
	}
	
	private static String name(User user) {
		return user.getUsername() != null ? user.getUsername() : user.getSystemId();
	}
	
	private static APIAuthenticationException refusal(String message) {
		return new APIAuthenticationException(message);
	}
}
