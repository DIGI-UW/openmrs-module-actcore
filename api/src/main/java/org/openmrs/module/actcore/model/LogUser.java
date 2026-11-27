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
 * A user as the Users page shows one, which is how the logs and the list of downloads name a user.
 */
public class LogUser {
	
	/** The user's uuid. */
	private String uuid;
	
	/** The user's username, or their system ID when they have no username. */
	private String username;
	
	/** The user's person name, or their username when the person has no name. */
	private String display;
	
	/**
	 * The user's organizational role, shortened as the Users page shows it, such as Clinician, or null
	 * when they have none.
	 */
	private String role;
	
	public String getUuid() {
		return uuid;
	}
	
	public void setUuid(String uuid) {
		this.uuid = uuid;
	}
	
	public String getUsername() {
		return username;
	}
	
	public void setUsername(String username) {
		this.username = username;
	}
	
	public String getDisplay() {
		return display;
	}
	
	public void setDisplay(String display) {
		this.display = display;
	}
	
	public String getRole() {
		return role;
	}
	
	public void setRole(String role) {
		this.role = role;
	}
}
