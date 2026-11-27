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

import org.apache.commons.lang3.StringUtils;
import org.openmrs.api.context.Context;
import org.openmrs.api.impl.BaseOpenmrsService;
import org.openmrs.module.actcore.ActCoreConstants;
import org.openmrs.module.actcore.api.PatientFlagRefreshService;
import org.openmrs.module.actcore.model.PatientFlagRefreshResult;
import org.openmrs.module.actcore.model.PatientFlagRefreshStatus;
import org.openmrs.util.PrivilegeConstants;
import org.springframework.transaction.annotation.Transactional;

/** Default implementation of {@link org.openmrs.module.actcore.api.PatientFlagRefreshService}. */
public class PatientFlagRefreshServiceImpl extends BaseOpenmrsService implements PatientFlagRefreshService {
	
	/**
	 * @see org.openmrs.module.actcore.api.PatientFlagRefreshService#refreshAll()
	 */
	@Override
	// No @Transactional: each flag and list commits in its own, before the rows are let go.
	public PatientFlagRefreshResult refreshAll() {
		return new PatientFlagRefresh().runIfIdle();
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.PatientFlagRefreshService#getStatus()
	 */
	@Override
	@Transactional(readOnly = true)
	public PatientFlagRefreshStatus getStatus() {
		String lastFinished;
		Context.addProxyPrivilege(PrivilegeConstants.GET_GLOBAL_PROPERTIES);
		try {
			lastFinished = Context.getAdministrationService().getGlobalProperty(ActCoreConstants.GP_REFRESH_LAST_FINISHED);
		}
		finally {
			Context.removeProxyPrivilege(PrivilegeConstants.GET_GLOBAL_PROPERTIES);
		}
		return new PatientFlagRefreshStatus(StringUtils.defaultIfBlank(lastFinished, null), PatientFlagRefresh.isRunning());
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.PatientFlagRefreshService#refreshPatient(int)
	 */
	@Override
	// No @Transactional: it runs after the save commits, each flag and list in a unit of its own.
	public void refreshPatient(int patientId) {
		PatientFlagRefresh.refreshPatient(patientId);
	}
}
