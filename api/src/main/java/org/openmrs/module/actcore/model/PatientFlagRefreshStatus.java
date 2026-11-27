/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.model;

/**
 * Whether the patient flag refresh is running now, and when it last finished.
 *
 * @see org.openmrs.module.actcore.api.PatientFlagRefreshService#getStatus()
 */
public class PatientFlagRefreshStatus {
	
	private final String lastFinished;
	
	private final boolean running;
	
	public PatientFlagRefreshStatus(String lastFinished, boolean running) {
		this.lastFinished = lastFinished;
		this.running = running;
	}
	
	/**
	 * @return when the refresh last finished, as an ISO-8601 instant, or null before the first run
	 *         finishes
	 */
	public String getLastFinished() {
		return lastFinished;
	}
	
	/** @return true while a run holds the refresh, whoever started it */
	public boolean isRunning() {
		return running;
	}
}
