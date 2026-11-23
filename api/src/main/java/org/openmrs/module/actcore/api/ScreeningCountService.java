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

import java.time.YearMonth;
import java.util.Collection;
import java.util.Map;

import org.openmrs.Location;
import org.openmrs.annotation.Authorized;
import org.openmrs.api.OpenmrsService;
import org.openmrs.module.actcore.ActCorePrivileges;
import org.openmrs.module.actcore.screening.ScreeningCounts;

/** ACT 2.0's monthly screening counts per clinic, read, saved and summed. */
public interface ScreeningCountService extends OpenmrsService {
	
	/**
	 * Gets the clinic's counts for the month.
	 *
	 * @param clinic the clinic
	 * @param month the month
	 * @return the month's counts, all zero and not saved when the month has no counts yet
	 */
	@Authorized({ ActCorePrivileges.SCREENING_COUNTS, ActCorePrivileges.DASHBOARD,
	        ActCorePrivileges.ENTER_SCREENING_COUNTS })
	ScreeningCounts.Month getMonth(Location clinic, YearMonth month);
	
	/**
	 * Saves the clinic's counts for the month, voiding the counts they replace.
	 *
	 * @param clinic the clinic
	 * @param month the month, which must have started
	 * @param counts each column's count; a column not given is zero
	 * @param notes free text, or null
	 * @throws IllegalArgumentException when the counts do not hold together, saying what does not
	 */
	@Authorized(ActCorePrivileges.ENTER_SCREENING_COUNTS)
	void saveMonth(Location clinic, YearMonth month, Map<String, Integer> counts, String notes);
	
	/**
	 * Gets the sums of the clinics' counts from start to end, both months included.
	 *
	 * @param clinics the location ids to sum, null for every clinic, empty for none
	 * @param start the first month
	 * @param end the last month
	 * @return the sums and how many clinic months were saved, all zero when nothing matches
	 */
	@Authorized({ ActCorePrivileges.SCREENING_COUNTS, ActCorePrivileges.DASHBOARD,
	        ActCorePrivileges.ENTER_SCREENING_COUNTS })
	ScreeningCounts.Summary getSummary(Collection<Integer> clinics, YearMonth start, YearMonth end);
}
