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
 * One page of the read log rows a search matches, with how many rows it matches in all.
 *
 * @see org.openmrs.module.actcore.api.AuditLogService#getReads(org.openmrs.module.actcore.api.search.ReadSearchCriteria)
 */
public class ReadPage {
	
	/** The rows on the page, the row with the latest first read first. */
	public final List<ReadRow> results = new ArrayList<ReadRow>();
	
	/** How many rows the search matches across every page. */
	public int totalCount;
}
