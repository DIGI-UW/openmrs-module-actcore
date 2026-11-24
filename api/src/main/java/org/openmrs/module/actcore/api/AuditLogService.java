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

import java.util.Collection;
import java.util.Map;

import org.openmrs.annotation.Authorized;
import org.openmrs.api.OpenmrsService;
import org.openmrs.module.actcore.ActCoreConstants;
import org.openmrs.module.actcore.api.search.ActivitySearchCriteria;
import org.openmrs.module.actcore.api.search.ReadSearchCriteria;
import org.openmrs.module.actcore.model.ActivityDetails;
import org.openmrs.module.actcore.model.ActivityPage;
import org.openmrs.module.actcore.model.LogUser;
import org.openmrs.module.actcore.model.ReadPage;

/**
 * The service behind the Logs page: what ACT's users changed, read from the audit log module's and
 * Envers' tables, and who opened which patient's record, read from the read audit. It also names
 * the users the Downloads tab lists.
 */
public interface AuditLogService extends OpenmrsService {
	
	/**
	 * Gets a page of the changes the filter matches, newest first, including ACT Core's flag dismissals
	 * unless the filter names a form or a kind other than flag.
	 *
	 * @param filter the filters that choose the changes and the page of them to return
	 * @return the page of entries; empty when the filter names a user, patient or form that does not
	 *         exist
	 * @throws TooManyResultsException when a kind filter has more changes to sort than one request may
	 *             sort
	 * @throws org.openmrs.api.APIAuthenticationException when the user lacks
	 *             {@link ActCoreConstants#AUDITLOGWEB_VIEW_AUDIT_LOGS}
	 */
	@Authorized(ActCoreConstants.AUDITLOGWEB_VIEW_AUDIT_LOGS)
	ActivityPage getActivity(ActivitySearchCriteria filter);
	
	/**
	 * Gets the fields one revision's save changed, and the reason given for a void.
	 *
	 * @param revisionId the revision's id
	 * @return the entry's fields and void reason; null when no audited table has the revision or the
	 *         log leaves it out
	 * @throws org.openmrs.api.APIAuthenticationException when the user lacks
	 *             {@link ActCoreConstants#AUDITLOGWEB_VIEW_AUDIT_LOGS}
	 */
	@Authorized(ActCoreConstants.AUDITLOGWEB_VIEW_AUDIT_LOGS)
	ActivityDetails getActivityDetails(int revisionId);
	
	/**
	 * Gets a page of the reads of patients' data that the filter matches, folded into rows, the row
	 * with the latest first read first.
	 *
	 * @param filter the filters that choose the reads and the page of rows to return
	 * @return the page of rows; empty when no patient matches the filter's patient or no read falls in
	 *         its days
	 * @throws TooManyResultsException when more patients match the filter's patient than one request
	 *             may read
	 * @throws org.openmrs.api.APIAuthenticationException when the user lacks
	 *             {@link ActCoreConstants#AUDITLOGWEB_VIEW_READ_AUDIT_LOGS}
	 */
	@Authorized(ActCoreConstants.AUDITLOGWEB_VIEW_READ_AUDIT_LOGS)
	ReadPage getReads(ReadSearchCriteria filter);
	
	/**
	 * Gets the id of the user with this username, or of the user with this system ID and no username,
	 * since a log names a user without a username by their system ID.
	 *
	 * @param username the username or system ID
	 * @return the user's id, or null when no user has it
	 * @throws org.openmrs.api.APIAuthenticationException when the user holds neither
	 *             {@link ActCoreConstants#AUDITLOGWEB_VIEW_AUDIT_LOGS} nor
	 *             {@link ActCoreConstants#AUDITLOGWEB_VIEW_READ_AUDIT_LOGS}
	 */
	@Authorized({ ActCoreConstants.AUDITLOGWEB_VIEW_AUDIT_LOGS, ActCoreConstants.AUDITLOGWEB_VIEW_READ_AUDIT_LOGS })
	Integer getUserId(String username);
	
	/**
	 * Gets the users with these uuids as a log names them, with their name and ACT role, read by SQL:
	 * the site administrator, who reads the logs, is limited by ACT Core's user administration advice
	 * to the users of its clinics, and a log names everyone.
	 *
	 * @param uuids the users' uuids
	 * @return each user found, by uuid; a uuid that no user has is absent
	 * @throws org.openmrs.api.APIAuthenticationException when the user holds neither
	 *             {@link ActCoreConstants#AUDITLOGWEB_VIEW_AUDIT_LOGS} nor
	 *             {@link ActCoreConstants#AUDITLOGWEB_VIEW_READ_AUDIT_LOGS}
	 */
	@Authorized({ ActCoreConstants.AUDITLOGWEB_VIEW_AUDIT_LOGS, ActCoreConstants.AUDITLOGWEB_VIEW_READ_AUDIT_LOGS })
	Map<String, LogUser> getUsers(Collection<String> uuids);
}
