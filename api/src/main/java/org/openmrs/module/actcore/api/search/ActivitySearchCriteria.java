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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.openmrs.module.actcore.ActCoreConstants;

/**
 * The filters that choose which changes the activity log lists, and which page of the matching
 * entries to return.
 * <p>
 * A filter left null is not applied. {@code from} and {@code to} are both included, each as a whole
 * day. {@code startIndex} and {@code limit} pick the page, the limit defaulting to
 * {@link ActCoreConstants#LOG_DEFAULT_LIMIT}.
 *
 * @see org.openmrs.module.actcore.api.AuditLogService#getActivity(ActivitySearchCriteria)
 */
public class ActivitySearchCriteria {
	
	/**
	 * The kinds of entry the log tells apart, which a request may filter by. Each entry has one of
	 * them.
	 */
	public static final List<String> KINDS = Collections.unmodifiableList(Arrays.asList("saved", "edited", "voided",
	    "registered", "patientStatus", "user", "role", "clinic", "study", "flag", "other"));
	
	/** The username, or the system ID of a user without one, of the user whose changes to list. */
	public String username;
	
	/** The first day whose changes to list. */
	public LocalDate from;
	
	/** The last day whose changes to list. */
	public LocalDate to;
	
	/** The kind of entry to list, one of {@link #KINDS}. */
	public String kind;
	
	/** The uuid of the form whose saves to list. */
	public String formUuid;
	
	/** The uuid of the patient whose changes to list. */
	public String patientUuid;
	
	/** The index, from 0, of the first matching entry to return. */
	public int startIndex;
	
	/** The most entries to return. */
	public int limit = ActCoreConstants.LOG_DEFAULT_LIMIT;
}
