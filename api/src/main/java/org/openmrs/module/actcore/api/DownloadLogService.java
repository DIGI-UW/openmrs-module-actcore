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

import org.openmrs.annotation.Authorized;
import org.openmrs.api.OpenmrsService;
import org.openmrs.module.actcore.ActCoreConstants;
import org.openmrs.module.actcore.ActCorePrivileges;
import org.openmrs.module.actcore.api.search.DownloadSearchCriteria;
import org.openmrs.module.actcore.model.DownloadPage;

/**
 * The service that records each download of patient data and lists the downloads recorded, as ACT
 * 2.0's audit log did. The download endpoints record through it, and the Logs page's Downloads tab
 * lists through it. The audit log module records reads and changes, but offers no way for another
 * module to record an event of its own, so ACT Core keeps the downloads in
 * {@link ActCoreConstants#DOWNLOAD_LOG_TABLE}.
 */
public interface DownloadLogService extends OpenmrsService {
	
	/**
	 * Records a download made by the signed-in user, dated now. The record is saved in a transaction of
	 * its own, and the caller records before it hands the data over, so that a download that cannot be
	 * recorded fails rather than going unrecorded.
	 *
	 * @param formId the id of the form downloaded when the download held exactly one form, or null
	 *            otherwise, as for a download of several forms or a list the app made itself
	 * @param detail what the download held, such as its forms, columns and filters, as a JSON object,
	 *            or null when nothing is recorded of it
	 * @throws org.openmrs.api.APIAuthenticationException when the user holds neither
	 *             {@link ActCorePrivileges#DOWNLOAD_DATA} nor
	 *             {@link ActCorePrivileges#DOWNLOAD_RESEARCH_DATA}
	 * @throws org.openmrs.api.ValidationException when the detail is not a JSON object, or is longer
	 *             than {@link ActCoreConstants#MAX_DOWNLOAD_DETAIL_BYTES} bytes of UTF-8
	 */
	@Authorized({ ActCorePrivileges.DOWNLOAD_DATA, ActCorePrivileges.DOWNLOAD_RESEARCH_DATA })
	void recordDownload(Integer formId, String detail);
	
	/**
	 * Gets a page of the downloads that match the filter, newest first, each with its user named and
	 * its detail read. A detail that cannot be read as JSON is logged and left out, so the download is
	 * listed without it, matched as having given identifiers and, when it was recorded without one
	 * form, as holding every form.
	 *
	 * @param filter the user, days, form, identifiers and page to list
	 * @return the page and how many downloads match in all; empty when none matches, as when no user
	 *         has the username or no form has the uuid
	 * @throws org.openmrs.api.APIAuthenticationException when the user lacks
	 *             {@link ActCoreConstants#AUDITLOGWEB_VIEW_READ_AUDIT_LOGS}
	 */
	@Authorized(ActCoreConstants.AUDITLOGWEB_VIEW_READ_AUDIT_LOGS)
	DownloadPage getDownloads(DownloadSearchCriteria filter);
}
