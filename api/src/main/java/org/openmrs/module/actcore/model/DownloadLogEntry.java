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

/** One recorded download, as the download log holds it. */
public class DownloadLogEntry {
	
	/** The uuid of the download's record. */
	private String uuid;
	
	/** When the download was recorded. */
	private Date date;
	
	/** The uuid of the user who downloaded. */
	private String userUuid;
	
	/**
	 * The uuid of the form downloaded when the download held exactly one form. It is null for any other
	 * download, and once the form is purged.
	 */
	private String formUuid;
	
	/** The form's stored name. It is null whenever {@link #formUuid} is null. */
	private String formName;
	
	/** What the download held, such as its forms, columns and filters, as JSON. */
	private String detail;
	
	public String getUuid() {
		return uuid;
	}
	
	public void setUuid(String uuid) {
		this.uuid = uuid;
	}
	
	public Date getDate() {
		return date;
	}
	
	public void setDate(Date date) {
		this.date = date;
	}
	
	public String getUserUuid() {
		return userUuid;
	}
	
	public void setUserUuid(String userUuid) {
		this.userUuid = userUuid;
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
	
	public String getDetail() {
		return detail;
	}
	
	public void setDetail(String detail) {
		this.detail = detail;
	}
}
