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
import org.openmrs.module.actcore.api.UserClinicsService;
import org.openmrs.module.actcore.api.db.UserClinicsDAO;
import org.openmrs.module.actcore.api.impl.UserClinicsServiceImpl;

/**
 * A Hibernate interceptor that checks a limited administrator's change to a user's clinics or roles
 * at the moment Hibernate writes it, while the database still holds the old values. It closes the
 * gap the UserService advice leaves, where a flush before the save would write the change without
 * passing the rules.
 */
public class UserClinicsFlushCheck extends EmptyInterceptor {
	
	private static final long serialVersionUID = 1L;
	
	/**
	 * Set on the thread while a check runs, so that a flush caused by the check's own reads does not
	 * start another check.
	 */
	private static final ThreadLocal<Boolean> CHECKING = new ThreadLocal<Boolean>();
	
	/** @see org.hibernate.Interceptor#onCollectionUpdate(Object, Serializable) */
	@Override
	public void onCollectionUpdate(Object collection, Serializable key) {
		check(collection);
	}
	
	/** @see org.hibernate.Interceptor#onCollectionRecreate(Object, Serializable) */
	@Override
	public void onCollectionRecreate(Object collection, Serializable key) {
		check(collection);
	}
	
	/** @see org.hibernate.Interceptor#onCollectionRemove(Object, Serializable) */
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
		UserClinicsService clinics = storedReads();
		boolean clinicsChange = !UserClinics.of(user).equals(clinics.getStoredClinics(user.getUserId()));
		boolean rolesChange = !UserClinics.roleUuids(user).equals(clinics.getStoredRoles(user.getUserId()));
		if (clinicsChange || rolesChange) {
			// A new user has nothing stored, so is checked as it will be saved.
			ClinicScopedUserAdministration.refuseUnlessMayChange(me, user, null, clinics);
		}
	}
	
	/**
	 * Gets the service's implementation without its proxy, reading through the DAO, so that no
	 * transaction begins or joins in the middle of the flush. The DAO cannot be injected: core builds
	 * the session factory from the interceptors, and the DAO needs the session factory.
	 */
	private static UserClinicsService storedReads() {
		UserClinicsServiceImpl reads = new UserClinicsServiceImpl();
		reads.setDao(Context.getRegisteredComponents(UserClinicsDAO.class).get(0));
		return reads;
	}
}
