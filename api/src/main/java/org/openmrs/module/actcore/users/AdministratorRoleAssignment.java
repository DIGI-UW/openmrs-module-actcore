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

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.openmrs.Location;
import org.openmrs.Privilege;
import org.openmrs.Role;
import org.openmrs.User;
import org.openmrs.api.context.Authenticated;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.ContextAuthenticationException;
import org.openmrs.api.context.Credentials;
import org.openmrs.api.context.UserContext;
import org.openmrs.module.actcore.clinics.UserClinics;
import org.openmrs.util.PrivilegeConstants;

/**
 * Lets the instance and global administrators give the roles configured for them, which OpenMRS
 * would refuse for the clinical privileges they lack; the README describes the rules.
 */
public class AdministratorRoleAssignment implements MethodInterceptor {
	
	/** The instance administrator role, by uuid. */
	public static final String INSTANCE_ADMINISTRATOR_ROLE = "07a7affa-b7de-46d9-9faa-aac4ef2eccba";
	
	/** The global administrator role, by uuid. */
	public static final String GLOBAL_ADMINISTRATOR_ROLE = "c95f35f4-0422-46a3-a593-a6615fab90bc";
	
	/** The roles, by uuid, an instance administrator may give out. */
	public static final String INSTANCE_ROLES_PROPERTY = "actcore.users.instanceAdministratorRoles";
	
	/** The roles, by uuid, a global administrator may give out. */
	public static final String GLOBAL_ROLES_PROPERTY = "actcore.users.globalAdministratorRoles";
	
	@Override
	public Object invoke(MethodInvocation invocation) throws Throwable {
		User me = Context.getAuthenticatedUser();
		String method = invocation.getMethod().getName();
		Object[] args = invocation.getArguments();
		boolean saves = "saveUser".equals(method) || "createUser".equals(method);
		if (!saves || args.length == 0 || !(args[0] instanceof User) || me == null || me.isSuperUser()) {
			return invocation.proceed();
		}
		User user = (User) args[0];
		if (!mayGiveTheRolesOf(me, user)) {
			return invocation.proceed();
		}
		UserContext own = Context.getUserContext();
		Context.setUserContext(new HoldingTheRolesOf(own, user));
		try {
			return invocation.proceed();
		}
		finally {
			Context.setUserContext(own);
		}
	}
	
	/**
	 * Whether the administrator may give out every role the user has, now or already, and may add or
	 * edit users; anyone else, and an administrator's own account, is left to OpenMRS.
	 */
	private static boolean mayGiveTheRolesOf(User me, User user) {
		Set<String> grantable = grantableBy(me);
		boolean isNew = user.getUserId() == null;
		boolean mayAdminister = Context.hasPrivilege(isNew ? PrivilegeConstants.ADD_USERS : PrivilegeConstants.EDIT_USERS);
		boolean isMe = me.getUserId() != null && me.getUserId().equals(user.getUserId());
		if (grantable.isEmpty() || !mayAdminister || isMe) {
			return false;
		}
		Set<String> roles = new HashSet<String>(UserClinics.roleUuids(user));
		roles.addAll(UserClinics.storedRoles(user.getUserId()));
		return grantable.containsAll(roles);
	}
	
	/** The roles the administrator may give out, from the property of each administrator role held. */
	private static Set<String> grantableBy(User me) {
		Set<String> grantable = new HashSet<String>();
		Set<String> held = new HashSet<String>();
		for (Role role : me.getAllRoles()) {
			held.add(role.getUuid());
		}
		if (held.contains(INSTANCE_ADMINISTRATOR_ROLE)) {
			grantable.addAll(UserClinics.rolesIn(INSTANCE_ROLES_PROPERTY));
		}
		if (held.contains(GLOBAL_ADMINISTRATOR_ROLE)) {
			grantable.addAll(UserClinics.rolesIn(GLOBAL_ROLES_PROPERTY));
		}
		return grantable;
	}
	
	/**
	 * The caller's own context, also holding the privileges of the roles being saved. It is set on this
	 * thread only for the save, so no other request of the session sees them.
	 */
	private static final class HoldingTheRolesOf extends UserContext {
		
		private static final long serialVersionUID = 1L;
		
		private final UserContext own;
		
		private final Set<String> privileges = new HashSet<String>();
		
		HoldingTheRolesOf(UserContext own, User user) {
			super(null);
			this.own = own;
			for (Role role : user.getAllRoles()) {
				for (Privilege privilege : role.getPrivileges()) {
					privileges.add(privilege.getPrivilege());
				}
			}
		}
		
		@Override
		public boolean hasPrivilege(String privilege) {
			return own.hasPrivilege(privilege) || privileges.contains(privilege);
		}
		
		@Override
		public Authenticated authenticate(Credentials credentials) throws ContextAuthenticationException {
			return own.authenticate(credentials);
		}
		
		@Override
		public void refreshAuthenticatedUser() {
			own.refreshAuthenticatedUser();
		}
		
		@Override
		public User becomeUser(String systemId) throws ContextAuthenticationException {
			return own.becomeUser(systemId);
		}
		
		@Override
		public User getAuthenticatedUser() {
			return own.getAuthenticatedUser();
		}
		
		@Override
		public boolean isAuthenticated() {
			return own.isAuthenticated();
		}
		
		@Override
		public void logout() {
			own.logout();
		}
		
		@Override
		public void addProxyPrivilege(String privilege) {
			own.addProxyPrivilege(privilege);
		}
		
		@Override
		public void removeProxyPrivilege(String privilege) {
			own.removeProxyPrivilege(privilege);
		}
		
		@Override
		public void setLocale(Locale locale) {
			own.setLocale(locale);
		}
		
		@Override
		public Locale getLocale() {
			return own.getLocale();
		}
		
		@Override
		public Set<Role> getAllRoles() throws Exception {
			return own.getAllRoles();
		}
		
		@Override
		public Set<Role> getAllRoles(User user) throws Exception {
			return own.getAllRoles(user);
		}
		
		@Override
		public Integer getLocationId() {
			return own.getLocationId();
		}
		
		@Override
		public void setLocationId(Integer locationId) {
			own.setLocationId(locationId);
		}
		
		@Override
		public Location getLocation() {
			return own.getLocation();
		}
		
		@Override
		public void setLocation(Location location) {
			own.setLocation(location);
		}
	}
}
