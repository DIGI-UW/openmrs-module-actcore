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

/** The patient a read log row's reads are of, as the Logs page names them. */
public class ReadPatient {
	
	/** The patient's uuid. */
	private String uuid;
	
	/** The patient's name, or the name the read audit recorded once the patient is purged. */
	private String display;
	
	/** The patient's ACT ID, or null when they have none or are purged. */
	private String actId;
	
	/** The name of the patient's primary care clinic, or null when none is recorded. */
	private String primaryCareClinic;
	
	/** The name of the patient's cardiac clinic, or null when none is recorded. */
	private String cardiacClinic;
	
	public String getUuid() {
		return uuid;
	}
	
	public void setUuid(String uuid) {
		this.uuid = uuid;
	}
	
	public String getDisplay() {
		return display;
	}
	
	public void setDisplay(String display) {
		this.display = display;
	}
	
	public String getActId() {
		return actId;
	}
	
	public void setActId(String actId) {
		this.actId = actId;
	}
	
	public String getPrimaryCareClinic() {
		return primaryCareClinic;
	}
	
	public void setPrimaryCareClinic(String primaryCareClinic) {
		this.primaryCareClinic = primaryCareClinic;
	}
	
	public String getCardiacClinic() {
		return cardiacClinic;
	}
	
	public void setCardiacClinic(String cardiacClinic) {
		this.cardiacClinic = cardiacClinic;
	}
}
