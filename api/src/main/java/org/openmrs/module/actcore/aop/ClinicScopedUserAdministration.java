/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.aop;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
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
import org.openmrs.module.actcore.ActCoreConstants;
import org.openmrs.module.actcore.api.UserClinicsService;
import org.openmrs.module.actcore.clinics.UserClinics;

/**
 * Advice on UserService that confines an administrator limited to their clinics, such as a site
 * administrator, to the users of those clinics, as ACT 2.0 confined its site administrator. Such an
 * administrator sees only the users who share one of their clinics, and changes a user only within
 * the rules the README lists. Every other user passes through untouched.
 */
public class ClinicScopedUserAdministration implements MethodInterceptor {
	
	private static final List<String> LISTS = Arrays.asList("getAllUsers", "getUsersByName", "getUsers");
	
	/**
	 * The names of the UserService methods that look up a single user. For a limited administrator they
	 * return null when the user found is at none of the administrator's clinics, as ACT 2.0's get_user
	 * refused such a user.
	 */
	private static final List<String> LOOKUPS = Arrays.asList("getUserByUuid", "getUserByUsername");
	
	private static final List<String> CHANGES_TO_A_USER = Arrays.asList("saveUser", "createUser", "retireUser",
	    "unretireUser", "purgeUser", "changePassword", "setUserProperty", "removeUserProperty");
	
	/**
	 * Set on the thread while this advice runs its own search of the users. Core answers that search
	 * through the advised service again, and the flag lets the nested call pass through unfiltered.
	 */
	private static final ThreadLocal<Boolean> SEARCHING = new ThreadLocal<Boolean>();
	
	/** @see org.aopalliance.intercept.MethodInterceptor#invoke(MethodInvocation) */
	@Override
	public Object invoke(MethodInvocation invocation) throws Throwable {
		User me = Context.getAuthenticatedUser();
		if (Boolean.TRUE.equals(SEARCHING.get()) || !UserClinics.isLimited(me)) {
			return invocation.proceed();
		}
		UserClinicsService clinics = Context.getService(UserClinicsService.class);
		String method = invocation.getMethod().getName();
		Object[] args = invocation.getArguments();
		if ("getUsers".equals(method) && args.length == 5) {
			return pageOfVisible(invocation, me, clinics);
		}
		if ("getCountOfUsers".equals(method) && args.length == 3) {
			return visible(allMatching(invocation, args), me, clinics).size();
		}
		if (CHANGES_TO_A_USER.contains(method) && args.length > 0 && args[0] instanceof User) {
			User user = (User) args[0];
			if ("setUserProperty".equals(method) || "removeUserProperty".equals(method)) {
				refuseUnlessMayChange(me, user, changedClinics(user, args, "setUserProperty".equals(method)), clinics);
			} else {
				refuseUnlessMayChange(me, user, null, clinics);
			}
		}
		if ("saveUserProperty".equals(method) && args.length == 2 && ActCoreConstants.USER_PROPERTY_CLINICS.equals(args[0])
		        && !UserClinics.parse((String) args[1]).equals(clinics.getStoredClinics(me.getUserId()))) {
			throw refusal("You cannot change your own clinics. Ask an instance administrator");
		}
		if ("saveUserProperties".equals(method) && args.length == 1 && args[0] instanceof Map) {
			Object saved = ((Map<?, ?>) args[0]).get(ActCoreConstants.USER_PROPERTY_CLINICS);
			if (!UserClinics.parse(saved == null ? null : String.valueOf(saved))
			        .equals(clinics.getStoredClinics(me.getUserId()))) {
				throw refusal("You cannot change your own clinics. Ask an instance administrator");
			}
		}
		Object result = invocation.proceed();
		if (LISTS.contains(method) && result instanceof List) {
			return visible((List<?>) result, me, clinics);
		}
		if (LOOKUPS.contains(method) && result instanceof User) {
			return visible(Collections.singletonList(result), me, clinics).isEmpty() ? null : result;
		}
		return result;
	}
	
	/**
	 * Works out the clinics the user will have after a call that sets or removes a user property. It
	 * returns null when the call does not touch the clinics property, in which case the caller checks
	 * the user's current clinics instead.
	 */
	private static Set<String> changedClinics(User user, Object[] args, boolean set) {
		if (args.length < 2 || !ActCoreConstants.USER_PROPERTY_CLINICS.equals(args[1])) {
			return null;
		}
		return set ? UserClinics.parse(args.length > 2 ? (String) args[2] : null) : new HashSet<String>();
	}
	
	/**
	 * Refuses a limited administrator's change to a user unless the rules allow it, comparing the user
	 * as changed with what is saved, which it reads through the service given.
	 *
	 * @param me the limited administrator making the change
	 * @param user the user as changed
	 * @param clinicsAfter the clinics the user will have, or null to read them from the user
	 * @param clinics the service that reads the user's and the administrator's saved clinics and roles
	 * @throws APIAuthenticationException when the rules do not allow the change
	 */
	public static void refuseUnlessMayChange(User me, User user, Set<String> clinicsAfter, UserClinicsService clinics) {
		Set<String> after = clinicsAfter != null ? clinicsAfter : UserClinics.of(user);
		Set<String> mine = clinics.getStoredClinics(me.getUserId());
		if (me.getUserId() != null && me.getUserId().equals(user.getUserId())) {
			if (!after.equals(mine)) {
				throw refusal("You cannot change your own clinics. Ask an instance administrator");
			}
			if (!UserClinics.roleUuids(user).equals(clinics.getStoredRoles(me.getUserId()))) {
				throw refusal("You cannot change your own roles. Ask an instance administrator");
			}
			return;
		}
		Set<String> assignable = clinics.getRolesIn(ActCoreConstants.GP_USERS_SITE_ADMINISTRATOR_ROLES);
		if (user.getUserId() != null) {
			if (!UserClinics.share(clinics.getStoredClinics(user.getUserId()), mine)) {
				throw refusal("This user is not at one of your clinics, so you cannot change them");
			}
			if (!assignable.isEmpty() && !assignable.containsAll(clinics.getStoredRoles(user.getUserId()))) {
				throw refusal(
				    "This user has a role you cannot give out, so only an instance administrator can change" + " them");
			}
		}
		// As ACT 2.0 needed only an overlap: the clinics that are not theirs stay as stored, so a clinician at A and B
		// is changed by A's site administrator without leaving B's lists.
		Set<String> othersBefore = new HashSet<String>(clinics.getStoredClinics(user.getUserId()));
		othersBefore.removeAll(mine);
		Set<String> othersAfter = new HashSet<String>(after);
		othersAfter.removeAll(mine);
		if (!othersAfter.equals(othersBefore)) {
			throw refusal("You can give or take away only your own clinics");
		}
		if (!UserClinics.share(after, mine)) {
			throw refusal("Give the user at least one of your clinics");
		}
		if (!assignable.isEmpty() && !assignable.containsAll(UserClinics.roleUuids(user))) {
			throw refusal("You can give a user only the clinician roles: " + roleNames(assignable, clinics));
		}
	}
	
	/**
	 * Runs the paged user search for a limited administrator. It filters every matching user before it
	 * cuts the page, so that each page stays full and in order.
	 */
	private static List<User> pageOfVisible(MethodInvocation invocation, User me, UserClinicsService clinics) {
		Object[] args = invocation.getArguments();
		List<User> visible = visible(allMatching(invocation, args), me, clinics);
		int start = args[3] == null ? 0 : Math.max(0, (Integer) args[3]);
		int end = args[4] == null ? visible.size() : Math.min(visible.size(), start + Math.max(0, (Integer) args[4]));
		return start >= end ? new ArrayList<User>() : new ArrayList<User>(visible.subList(start, end));
	}
	
	/** Runs the search again for every user it matches, without filtering or paging them. */
	@SuppressWarnings("unchecked")
	private static List<User> allMatching(MethodInvocation invocation, Object[] args) {
		SEARCHING.set(true);
		try {
			return Context.getUserService().getUsers((String) args[0], (List<Role>) args[1], (Boolean) args[2]);
		}
		finally {
			SEARCHING.remove();
		}
	}
	
	private static List<User> visible(Collection<?> users, User me, UserClinicsService clinics) {
		Set<String> mine = clinics.getStoredClinics(me.getUserId());
		List<User> shown = new ArrayList<User>();
		for (Object row : users) {
			User user = (User) row;
			if (me.getUserId() != null && me.getUserId().equals(user.getUserId())
			        || UserClinics.share(UserClinics.of(user), mine)) {
				shown.add(user);
			}
		}
		return shown;
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
