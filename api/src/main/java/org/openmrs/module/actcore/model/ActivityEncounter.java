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

import java.util.Date;

/**
 * The encounter an activity log entry's change was saved on, as it stands now, or as the revision
 * recorded it once the encounter is purged.
 */
public class ActivityEncounter {
	
	/** The encounter's uuid. */
	private String uuid;
	
	/** The uuid of the form the encounter was entered through, or null when no form recorded it. */
	private String formUuid;
	
	/** The name users see for the form, or null when no form recorded the encounter. */
	private String formName;
	
	/** The name users see for the encounter's type. */
	private String typeName;
	
	/** The encounter's date and time. */
	private Date visitDate;
	
	public String getUuid() {
		return uuid;
	}
	
	public void setUuid(String uuid) {
		this.uuid = uuid;
	}
	
	public String getFormUuid() {
		return formUuid;
	}
	
	public void setFormUuid(String formUuid) {
		this.formUuid = formUuid;
	}
	
	public String getFormName() {
		return formName;
	}
	
	public void setFormName(String formName) {
		this.formName = formName;
	}
	
	public String getTypeName() {
		return typeName;
	}
	
	public void setTypeName(String typeName) {
		this.typeName = typeName;
	}
	
	public Date getVisitDate() {
		return visitDate;
	}
	
	public void setVisitDate(Date visitDate) {
		this.visitDate = visitDate;
	}
}
