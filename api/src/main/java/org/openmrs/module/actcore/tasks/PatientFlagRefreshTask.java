/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.tasks;

import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.api.PatientFlagRefreshService;
import org.openmrs.scheduler.tasks.AbstractTask;

/**
 * The scheduled task that refreshes the patient flags, as
 * {@link PatientFlagRefreshService#refreshAll()} describes. A run that starts while another is in
 * progress is skipped. The scheduler stores this class's name, so it keeps its name and package.
 */
public class PatientFlagRefreshTask extends AbstractTask {
	
	/** @see org.openmrs.scheduler.tasks.AbstractTask#execute() */
	@Override
	public void execute() {
		Context.getService(PatientFlagRefreshService.class).refreshAll();
	}
}
