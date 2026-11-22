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
	
	/** The clinic's counts for the month, or zeros when it has not been saved. */
	@Authorized({ ActCorePrivileges.SCREENING_COUNTS, ActCorePrivileges.DASHBOARD,
	        ActCorePrivileges.ENTER_SCREENING_COUNTS })
	ScreeningCounts.Month getMonth(Location clinic, YearMonth month);
	
	/**
	 * Replaces the clinic's month with these counts, a column not given being zero, once they hold
	 * together, voiding the counts it replaces; an IllegalArgumentException says what does not hold.
	 */
	@Authorized(ActCorePrivileges.ENTER_SCREENING_COUNTS)
	void saveMonth(Location clinic, YearMonth month, Map<String, Integer> counts, String notes);
	
	/**
	 * The sums of the clinics' months from start to end, both included: of every clinic when clinics is
	 * null, of none when it is empty.
	 */
	@Authorized({ ActCorePrivileges.SCREENING_COUNTS, ActCorePrivileges.DASHBOARD,
	        ActCorePrivileges.ENTER_SCREENING_COUNTS })
	ScreeningCounts.Summary getSummary(Collection<Integer> clinics, YearMonth start, YearMonth end);
}
