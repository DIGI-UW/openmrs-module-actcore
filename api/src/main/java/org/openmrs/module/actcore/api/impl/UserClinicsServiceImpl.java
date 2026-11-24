/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.api.impl;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.openmrs.api.impl.BaseOpenmrsService;
import org.openmrs.module.actcore.ActCoreConstants;
import org.openmrs.module.actcore.api.UserClinicsService;
import org.openmrs.module.actcore.api.db.UserClinicsDAO;
import org.openmrs.module.actcore.clinics.UserClinics;
import org.springframework.transaction.annotation.Transactional;

/** Default implementation of {@link org.openmrs.module.actcore.api.UserClinicsService}. */
public class UserClinicsServiceImpl extends BaseOpenmrsService implements UserClinicsService {
	
	private UserClinicsDAO dao;
	
	public void setDao(UserClinicsDAO dao) {
		this.dao = dao;
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.UserClinicsService#getStoredClinics(Integer)
	 */
	@Override
	@Transactional(readOnly = true)
	public Set<String> getStoredClinics(Integer userId) {
		return userId == null ? new LinkedHashSet<String>()
		        : UserClinics.parse(dao.getUserProperty(userId, ActCoreConstants.USER_PROPERTY_CLINICS));
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.UserClinicsService#getStoredRoles(Integer)
	 */
	@Override
	@Transactional(readOnly = true)
	public Set<String> getStoredRoles(Integer userId) {
		return userId == null ? new LinkedHashSet<String>() : new LinkedHashSet<String>(dao.getRoleUuids(userId));
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.UserClinicsService#getRolesIn(String)
	 */
	@Override
	@Transactional(readOnly = true)
	public Set<String> getRolesIn(String property) {
		return UserClinics.parse(dao.getGlobalProperty(property));
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.UserClinicsService#getRoleNames(Set)
	 */
	@Override
	@Transactional(readOnly = true)
	public List<String> getRoleNames(Set<String> uuids) {
		List<String> names = new ArrayList<String>();
		for (String uuid : uuids) {
			String name = dao.getRoleName(uuid);
			names.add(name == null ? uuid : name);
		}
		return names;
	}
}
