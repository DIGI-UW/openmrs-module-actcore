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

public interface DownloadLogDAO {
	
	void saveDownloadRecord(DownloadRecord record);
	
	/** Downloads newest first, from and before (exclusive) left out when null. */
	List<DownloadLog.Entry> getDownloads(Integer userId, Date from, Date before);
}
