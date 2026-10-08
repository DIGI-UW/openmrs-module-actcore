/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.web;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.openmrs.Role;
import org.openmrs.User;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.users.ClinicUsers;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.openmrs.module.webservices.rest.web.v1_0.controller.BaseRestController;
import org.openmrs.util.PrivilegeConstants;
import org.openmrs.util.RoleConstants;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * The users an administrator manages, with their clinics, and the roles it may give: all users for
 * an administrator of every clinic, and those sharing one of its clinics for a clinic-limited one.
 * Saving goes through core REST, where {@code ClinicLimitedUserManagement} checks it.
 */
@Controller
@RequestMapping("/rest/" + RestConstants.VERSION_1 + "/actcore")
public class UsersController extends BaseRestController {
	
	@RequestMapping(value = "/users", method = RequestMethod.GET)
	@ResponseBody
	public SimpleObject getUsers() {
		if (!Context.hasPrivilege(PrivilegeConstants.EDIT_USERS)) {
			throw new APIAuthenticationException("Privilege required: " + PrivilegeConstants.EDIT_USERS);
		}
		boolean limited = ClinicUsers.isClinicLimited();
		Set<String> mine = ClinicUsers.clinicsOf(Context.getAuthenticatedUser());
		List<SimpleObject> users = new ArrayList<>();
		for (User user : Context.getUserService().getAllUsers()) {
			Set<String> clinics = ClinicUsers.clinicsOf(user);
			if (limited && Collections.disjoint(clinics, mine)) {
				continue;
			}
			users.add(new SimpleObject().add("uuid", user.getUuid()).add("username", user.getUsername())
			        .add("systemId", user.getSystemId())
			        .add("display", user.getPersonName() != null ? user.getPersonName().getFullName() : user.getUsername())
			        .add("person", user.getPerson().getUuid()).add("roles", roles(user.getRoles()))
			        .add("clinics", new ArrayList<>(clinics)).add("retired", user.getRetired()));
		}
		List<Role> assignable = new ArrayList<>();
		// Core 2.8 lists roles only for Manage Roles, which an administrator no longer holds: it would let
		// a site administrator change role definitions. The caller was checked above; this only reads.
		Context.addProxyPrivilege(PrivilegeConstants.MANAGE_ROLES);
		try {
			for (Role role : Context.getUserService().getAllRoles()) {
				if (!isBuiltIn(role) && ClinicUsers.mayGive(role)) {
					assignable.add(role);
				}
			}
		}
		finally {
			Context.removeProxyPrivilege(PrivilegeConstants.MANAGE_ROLES);
		}
		return new SimpleObject().add("clinicLimited", limited).add("clinics", limited ? new ArrayList<>(mine) : null)
		        .add("assignableRoles", roles(assignable)).add("users", users);
	}
	
	private static List<SimpleObject> roles(Iterable<Role> roles) {
		List<SimpleObject> out = new ArrayList<>();
		if (roles != null) {
			for (Role role : roles) {
				out.add(new SimpleObject().add("uuid", role.getUuid()).add("name", role.getRole()));
			}
		}
		return out;
	}
	
	private static boolean isBuiltIn(Role role) {
		return RoleConstants.ANONYMOUS.equals(role.getRole()) || RoleConstants.AUTHENTICATED.equals(role.getRole());
	}
}
