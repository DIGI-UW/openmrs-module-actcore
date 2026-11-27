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
import java.util.Map;

/** One recorded download, as the Logs page lists it. */
public class DownloadLogItem {
	
	/** When the download was recorded. */
	private Date date;
	
	/** The user who downloaded. */
	private LogUser user;
	
	/** The uuid of the form downloaded when the download held exactly one form, else null. */
	private String formUuid;
	
	/** The name users see for the form. It is null whenever {@link #formUuid} is null. */
	private String formName;
	
	/**
	 * What the download held, read from its JSON, or null when nothing was recorded or what was
	 * recorded cannot be read as a JSON object of the shape every download records.
	 */
	private Map<?, ?> detail;
	
	/**
	 * Whether a detail was recorded that cannot be read as a JSON object of the shape every download
	 * records.
	 */
	private boolean unreadable;
	
	/**
	 * The value of the identifiers filter that matches the download: "with", "without" or "research".
	 * It is "with" for an {@link #unreadable} download.
	 */
	private String identifiers;
	
	public Date getDate() {
		return date;
	}
	
	public void setDate(Date date) {
		this.date = date;
	}
	
	public LogUser getUser() {
		return user;
	}
	
	public void setUser(LogUser user) {
		this.user = user;
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
	
	public Map<?, ?> getDetail() {
		return detail;
	}
	
	public void setDetail(Map<?, ?> detail) {
		this.detail = detail;
	}
	
	public boolean isUnreadable() {
		return unreadable;
	}
	
	public void setUnreadable(boolean unreadable) {
		this.unreadable = unreadable;
	}
	
	public String getIdentifiers() {
		return identifiers;
	}
	
	public void setIdentifiers(String identifiers) {
		this.identifiers = identifiers;
	}
}
