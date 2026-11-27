/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.api.search;

import java.time.LocalDate;

import org.openmrs.module.actcore.ActCoreConstants;

/**
 * Which recorded downloads to list, and which page of them. A filter left null is not applied.
 *
 * @see org.openmrs.module.actcore.api.DownloadLogService#getDownloads(DownloadSearchCriteria)
 */
public class DownloadSearchCriteria {
	
	/** The username, or the system ID of a user without one, of the user whose downloads to list. */
	private String username;
	
	/** The first day whose downloads to list. */
	private LocalDate from;
	
	/** The last day whose downloads to list. */
	private LocalDate to;
	
	/**
	 * The uuid of a form the downloads must hold, either as the one form downloaded or as one of
	 * several. A download recorded without one form whose recorded detail cannot be read as a JSON
	 * object matches every form, since its forms cannot be told.
	 */
	private String formUuid;
	
	/**
	 * What the downloads must have held: "research" for research data only, "with" for any other
	 * download that gave identifiers, and "without" for the rest. A download whose recorded detail
	 * cannot be read as a JSON object matches "with", since it may have given identifiers.
	 */
	private String identifiers;
	
	/** The index, from 0, of the first matching download to return. */
	private int startIndex;
	
	/**
	 * The most downloads to return, capped at {@link ActCoreConstants#DOWNLOAD_MAX_LIMIT}; below 1 for
	 * {@link ActCoreConstants#DOWNLOAD_DEFAULT_LIMIT}.
	 */
	private int limit = ActCoreConstants.DOWNLOAD_DEFAULT_LIMIT;
	
	public String getUsername() {
		return username;
	}
	
	public void setUsername(String username) {
		this.username = username;
	}
	
	public LocalDate getFrom() {
		return from;
	}
	
	public void setFrom(LocalDate from) {
		this.from = from;
	}
	
	public LocalDate getTo() {
		return to;
	}
	
	public void setTo(LocalDate to) {
		this.to = to;
	}
	
	public String getFormUuid() {
		return formUuid;
	}
	
	public void setFormUuid(String formUuid) {
		this.formUuid = formUuid;
	}
	
	public String getIdentifiers() {
		return identifiers;
	}
	
	public void setIdentifiers(String identifiers) {
		this.identifiers = identifiers;
	}
	
	public int getStartIndex() {
		return startIndex;
	}
	
	public void setStartIndex(int startIndex) {
		this.startIndex = startIndex;
	}
	
	public int getLimit() {
		return limit;
	}
	
	public void setLimit(int limit) {
		this.limit = limit;
	}
}
