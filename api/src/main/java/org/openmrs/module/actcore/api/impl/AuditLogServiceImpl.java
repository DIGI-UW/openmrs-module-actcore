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

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.openmrs.api.impl.BaseOpenmrsService;
import org.openmrs.module.actcore.api.AuditLogService;
import org.openmrs.module.actcore.api.db.AuditLogDAO;
import org.springframework.transaction.annotation.Transactional;

/** Default implementation of {@link org.openmrs.module.actcore.api.AuditLogService}. */
public class AuditLogServiceImpl extends BaseOpenmrsService implements AuditLogService {
	
	private AuditLogDAO dao;
	
	public void setDao(AuditLogDAO dao) {
		this.dao = dao;
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.AuditLogService#getActivity(ActivityLog.Filter)
	 */
	@Override
	@Transactional(readOnly = true)
	public ActivityLog.Page getActivity(ActivityLog.Filter filter) {
		return new ActivityLog(dao).list(filter);
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.AuditLogService#getActivityDetails(int)
	 */
	@Override
	@Transactional(readOnly = true)
	public ActivityLog.Details getActivityDetails(int revisionId) {
		return new ActivityLog(dao).details(revisionId);
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.AuditLogService#getReads(ReadLog.Filter)
	 */
	@Override
	@Transactional(readOnly = true)
	public ReadLog.Page getReads(ReadLog.Filter filter) {
		return new ReadLog(dao).list(filter);
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.AuditLogService#getUserId(String)
	 */
	@Override
	@Transactional(readOnly = true)
	public Integer getUserId(String username) {
		return new Names(dao).userId(username);
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.AuditLogService#getUsers(Collection)
	 */
	@Override
	@Transactional(readOnly = true)
	public Map<String, Names.Who> getUsers(Collection<String> uuids) {
		Names names = new Names(dao);
		Map<String, Names.Who> users = new HashMap<String, Names.Who>();
		for (String uuid : uuids) {
			Names.Who who = names.user(uuid);
			if (who != null) {
				users.put(uuid, who);
			}
		}
		return users;
	}
}
