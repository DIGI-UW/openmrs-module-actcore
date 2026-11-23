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
	 * Records a download by the signed-in user, in a transaction of its own, so that a download that
	 * cannot be recorded fails.
	 *
	 * @param formId the form downloaded, or null when the download held several forms
	 * @param detail what the download held, as JSON
	 */
	@Authorized({ ActCorePrivileges.DOWNLOAD_DATA, ActCorePrivileges.DOWNLOAD_RESEARCH_DATA })
	void recordDownload(Integer formId, String detail);
	
	/**
	 * Gets the downloads that match the filters, newest first.
	 *
	 * @param userId the user who downloaded, or null for every user
	 * @param from the first day, or null for no lower bound
	 * @param to the last day, included, or null for no upper bound
	 * @return the matching downloads, an empty list when there are none
	 */
	@Authorized(DownloadLog.VIEW_PRIVILEGE)
	List<DownloadLog.Entry> getDownloads(Integer userId, LocalDate from, LocalDate to);
}
