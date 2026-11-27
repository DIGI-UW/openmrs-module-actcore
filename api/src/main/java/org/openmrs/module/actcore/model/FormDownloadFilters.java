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

import org.openmrs.Location;

/**
 * The filters ACT 2.0's Download offered for choosing which patients' saves to give: their status
 * in the registry, their cardiac clinic and their primary care clinic. A filter left null is not
 * applied.
 *
 * @see org.openmrs.module.actcore.api.FormDownloadService
 */
public class FormDownloadFilters {
	
	private final String status;
	
	private final Location cardiacClinic;
	
	private final Location primaryCareClinic;
	
	public FormDownloadFilters(String status, Location cardiacClinic, Location primaryCareClinic) {
		this.status = status;
		this.cardiacClinic = cardiacClinic;
		this.primaryCareClinic = primaryCareClinic;
	}
	
	/**
	 * @return the status to keep: active for a living patient whose latest RHD Registry enrolment is
	 *         open, inactive for anyone else, or null for both
	 */
	public String getStatus() {
		return status;
	}
	
	/** @return the cardiac clinic a patient must be recorded at, or null for any */
	public Location getCardiacClinic() {
		return cardiacClinic;
	}
	
	/** @return the primary care clinic a patient must be recorded at, or null for any */
	public Location getPrimaryCareClinic() {
		return primaryCareClinic;
	}
}
