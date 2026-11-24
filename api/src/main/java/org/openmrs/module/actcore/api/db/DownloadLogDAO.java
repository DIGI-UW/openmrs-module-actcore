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

import org.openmrs.module.actcore.model.DownloadLogEntry;
import org.openmrs.module.actcore.model.DownloadRecord;

/**
 * The database access behind {@link org.openmrs.module.actcore.api.DownloadLogService}, which saves
 * download records and reads them back.
 */
public interface DownloadLogDAO {
	
	/**
	 * Saves a download record in the current session.
	 *
	 * @param record the download record to save
	 */
	void saveDownloadRecord(DownloadRecord record);
	
	/**
	 * Gets the downloads that match the filters, newest first, each with its user's uuid and its form's
	 * uuid and name. A filter left null is not applied.
	 *
	 * @param userId the id of the user who downloaded, or null for every user
	 * @param from the earliest time to include, or null for no lower bound
	 * @param before the time every download returned must come before, which is excluded, or null for
	 *            no upper bound
	 * @return the matching downloads; an empty list when there are none
	 */
	List<DownloadLogEntry> getDownloads(Integer userId, Date from, Date before);
}
