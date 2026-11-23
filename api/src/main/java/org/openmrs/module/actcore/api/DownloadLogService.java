/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.api;

import java.time.LocalDate;
import java.util.List;

import org.openmrs.annotation.Authorized;
import org.openmrs.api.OpenmrsService;
import org.openmrs.module.actcore.ActCorePrivileges;
import org.openmrs.module.actcore.download.DownloadLog;

/** Records each download and lists them, as ACT 2.0's audit log did. */
public interface DownloadLogService extends OpenmrsService {
	
	/**
	 * Records the download for the signed-in user in a transaction of its own, so a failure fails the
	 * caller.
	 */
	@Authorized({ ActCorePrivileges.DOWNLOAD_DATA, ActCorePrivileges.DOWNLOAD_RESEARCH_DATA })
	void recordDownload(Integer formId, String detail);
	
	/** Every download matching the filters, newest first; each filter is left out when null. */
	@Authorized(DownloadLog.VIEW_PRIVILEGE)
	List<DownloadLog.Entry> getDownloads(Integer userId, LocalDate from, LocalDate to);
}
