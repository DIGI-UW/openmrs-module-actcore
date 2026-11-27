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

import java.util.ArrayList;
import java.util.List;

/**
 * One page of the downloads a search matches, with how many it matches in all.
 *
 * @see org.openmrs.module.actcore.api.DownloadLogService#getDownloads(org.openmrs.module.actcore.api.search.DownloadSearchCriteria)
 */
public class DownloadPage {
	
	/** The downloads on the page, newest first. */
	private final List<DownloadLogItem> results = new ArrayList<DownloadLogItem>();
	
	/** How many downloads the search matches across every page. */
	private int totalCount;
	
	public List<DownloadLogItem> getResults() {
		return results;
	}
	
	public int getTotalCount() {
		return totalCount;
	}
	
	public void setTotalCount(int totalCount) {
		this.totalCount = totalCount;
	}
}
