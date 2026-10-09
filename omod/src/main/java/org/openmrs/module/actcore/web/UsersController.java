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
import java.util.function.Predicate;

import org.openmrs.Role;
import org.openmrs.User;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.users.ClinicLimitedUserManagement;
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
 * The users an administrator manages, with their clinics and whether it may edit them, and the
 * roles it may give. Saving goes through core REST, which ClinicLimitedUserManagement checks.
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
		Predicate<User> editable = ClinicLimitedUserManagement.editable();
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
			        .add("clinics", new ArrayList<>(clinics)).add("retired", user.getRetired())
			        .add("editable", editable.test(user)));
		}
		List<Role> assignable = new ArrayList<>();
		Set<String> givable = ClinicUsers.givableRoles();
		for (Role role : ClinicUsers.allRoles()) {
			if (!isBuiltIn(role) && ClinicUsers.mayGive(role, givable)) {
				assignable.add(role);
			}
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
