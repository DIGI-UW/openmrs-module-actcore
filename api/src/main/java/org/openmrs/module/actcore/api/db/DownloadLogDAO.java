/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.api.db;

import java.util.Date;
import java.util.List;

import org.openmrs.module.actcore.download.DownloadLog;
import org.openmrs.module.actcore.download.DownloadRecord;

/** Database access for {@link org.openmrs.module.actcore.api.DownloadLogService}. */
public interface DownloadLogDAO {
	
	/**
	 * Saves a download.
	 *
	 * @param record the download to save
	 */
	void saveDownloadRecord(DownloadRecord record);
	
	/**
	 * Gets the downloads that match the filters, newest first.
	 *
	 * @param userId the user who downloaded, or null for every user
	 * @param from the earliest time, included, or null for no lower bound
	 * @param before the time the downloads must be before, or null for no upper bound
	 * @return the matching downloads, an empty list when there are none
	 */
	List<DownloadLog.Entry> getDownloads(Integer userId, Date from, Date before);
}
