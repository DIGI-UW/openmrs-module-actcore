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
 * What an activity log entry's change was made to: a patient, a person who is not a patient, a
 * user, a role, a clinic or a study.
 */
public class ActivityTarget {
	
	/** The kind of target: patient, person, user, role, location or program. */
	private String kind;
	
	/** The target's uuid, or null when it is not known, as for a role. */
	private String uuid;
	
	/** The target's name as the page shows it. */
	private String display;
	
	/** The patient's ACT ID for a patient, the username for a user, and null for other targets. */
	private String actId;
	
	public String getKind() {
		return kind;
	}
	
	public void setKind(String kind) {
		this.kind = kind;
	}
	
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
}
