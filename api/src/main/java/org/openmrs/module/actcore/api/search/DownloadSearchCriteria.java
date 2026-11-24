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

/**
 * Which recorded downloads to list, and which page of them. A filter left null is not applied.
 *
 * @see org.openmrs.module.actcore.api.DownloadLogService#getDownloads(DownloadSearchCriteria)
 */
public class DownloadSearchCriteria {
	
	/** The number of downloads a page holds when the filter does not say how many it wants. */
	public static final int DEFAULT_LIMIT = 100;
	
	/** The most downloads a page holds, whatever the filter asks for. */
	public static final int MAX_LIMIT = 500;
	
	/** The username, or the system ID of a user without one, of the user whose downloads to list. */
	public String username;
	
	/** The first day whose downloads to list. */
	public LocalDate from;
	
	/** The last day whose downloads to list. */
	public LocalDate to;
	
	/**
	 * The uuid of a form the downloads must hold, either as the one form downloaded or as one of
	 * several.
	 */
	public String formUuid;
	
	/**
	 * What the downloads must have held: "research" for research data only, "with" for any other
	 * download that gave identifiers, and "without" for the rest.
	 */
	public String identifiers;
	
	/** The index, from 0, of the first matching download to return. */
	public int startIndex;
	
	/**
	 * The most downloads to return, capped at {@link #MAX_LIMIT}; below 1 for {@link #DEFAULT_LIMIT}.
	 */
	public int limit = DEFAULT_LIMIT;
}
