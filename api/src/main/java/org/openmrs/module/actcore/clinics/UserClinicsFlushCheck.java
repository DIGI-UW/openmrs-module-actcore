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

import java.io.Serializable;

import org.hibernate.EmptyInterceptor;
import org.hibernate.collection.spi.PersistentCollection;
import org.openmrs.User;
import org.openmrs.api.context.Context;

/**
 * Checks a limited administrator's change to a user's clinics or roles as Hibernate writes it,
 * while the database still holds the old values, so a flush before the save cannot get past the
 * rules.
 */
public class UserClinicsFlushCheck extends EmptyInterceptor {
	
	private static final long serialVersionUID = 1L;
	
	/** Set while a check runs, so nothing it calls checks again. */
	private static final ThreadLocal<Boolean> CHECKING = new ThreadLocal<Boolean>();
	
	@Override
	public void onCollectionUpdate(Object collection, Serializable key) {
		check(collection);
	}
	
	@Override
	public void onCollectionRecreate(Object collection, Serializable key) {
		check(collection);
	}
	
	@Override
	public void onCollectionRemove(Object collection, Serializable key) {
		check(collection);
	}
	
	private static void check(Object collection) {
		if (!(collection instanceof PersistentCollection) || Boolean.TRUE.equals(CHECKING.get())) {
			return;
		}
		CHECKING.set(true);
		try {
			checkUser((PersistentCollection) collection);
		}
		finally {
			CHECKING.remove();
		}
	}
	
	private static void checkUser(PersistentCollection persistent) {
		String role = persistent.getRole();
		if (role == null || !(role.endsWith("User.userProperties") || role.endsWith("User.roles"))
		        || !(persistent.getOwner() instanceof User)) {
			return;
		}
		User me = Context.getAuthenticatedUser();
		if (!UserClinics.isLimited(me)) {
			return;
		}
		User user = (User) persistent.getOwner();
		boolean clinicsChange = !UserClinics.of(user).equals(UserClinics.stored(user.getUserId()));
		boolean rolesChange = !UserClinics.roleUuids(user).equals(UserClinics.storedRoles(user.getUserId()));
		if (clinicsChange || rolesChange) {
			// A new user has nothing stored, so is checked as it will be saved.
			ClinicScopedUserAdministration.refuseUnlessMayChange(me, user, null);
		}
	}
}
