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
 * What failed in a run of the patient flag refresh, which the refresh endpoint reports.
 *
 * @see org.openmrs.module.actcore.api.PatientFlagRefreshService#refreshAll()
 */
public class PatientFlagRefreshResult {
	
	private final int flagsFailed;
	
	private final int listsFailed;
	
	private final boolean adherenceFailed;
	
	public PatientFlagRefreshResult(int flagsFailed, int listsFailed, boolean adherenceFailed) {
		this.flagsFailed = flagsFailed;
		this.listsFailed = listsFailed;
		this.adherenceFailed = adherenceFailed;
	}
	
	/** @return how many flags failed to be evaluated or written */
	public int getFlagsFailed() {
		return flagsFailed;
	}
	
	/** @return how many flag lists failed to sync */
	public int getListsFailed() {
		return listsFailed;
	}
	
	/** @return whether the adherence refresh that runs first failed */
	public boolean isAdherenceFailed() {
		return adherenceFailed;
	}
}
