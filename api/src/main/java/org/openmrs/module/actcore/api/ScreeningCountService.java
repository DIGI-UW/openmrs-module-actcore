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
import org.openmrs.module.actcore.model.ScreeningCounts;

/**
 * The service that reads, saves and sums the monthly screening counts that each clinic enters, as
 * ACT 2.0 kept them. The Screening counts page reads and saves a clinic's month through it, and the
 * Dashboard reads the sums.
 */
public interface ScreeningCountService extends OpenmrsService {
	
	/**
	 * Gets the clinic's current counts for the month, with the notes saved alongside them.
	 *
	 * @param clinic the clinic whose counts to get
	 * @param month the month whose counts to get
	 * @return the month's counts and notes; when nothing has been saved for the month, every count is
	 *         zero, the notes are null and the month is marked as not saved
	 * @throws org.openmrs.api.APIAuthenticationException when the user holds none of
	 *             {@link ActCorePrivileges#SCREENING_COUNTS}, {@link ActCorePrivileges#DASHBOARD} and
	 *             {@link ActCorePrivileges#ENTER_SCREENING_COUNTS}
	 */
	@Authorized({ ActCorePrivileges.SCREENING_COUNTS, ActCorePrivileges.DASHBOARD,
	        ActCorePrivileges.ENTER_SCREENING_COUNTS })
	ScreeningCounts.Month getMonth(Location clinic, YearMonth month);
	
	/**
	 * Saves the clinic's counts for the month, voiding the counts saved before for that month. Saves
	 * for one clinic take turns, so a month does not end up with two current sets of counts.
	 *
	 * @param clinic the clinic whose counts to save
	 * @param month the month the counts are for, which must have started somewhere in the world
	 * @param counts each column's count, keyed by a column of {@link ScreeningCounts#COLUMNS}; a column
	 *            not given is saved as zero
	 * @param notes free text saved with the counts, trimmed, or null for none
	 * @throws IllegalArgumentException when the month has not started, a column is not recognised, a
	 *             count is negative, the notes are too long, a setting's age groups do not add up to
	 *             its people screened, or its females or positives outnumber them; the message says
	 *             which
	 * @throws org.openmrs.api.APIAuthenticationException when the user lacks
	 *             {@link ActCorePrivileges#ENTER_SCREENING_COUNTS}
	 */
	@Authorized(ActCorePrivileges.ENTER_SCREENING_COUNTS)
	void saveMonth(Location clinic, YearMonth month, Map<String, Integer> counts, String notes);
	
	/**
	 * Gets the sums of the clinics' current counts over the months from start to end, both included.
	 *
	 * @param clinics the location ids of the clinics to sum; null sums every clinic, and an empty
	 *            collection sums none
	 * @param start the first month to sum
	 * @param end the last month to sum
	 * @return each column's sum and how many clinic months were saved in the range; every sum is zero
	 *         when nothing matches
	 * @throws org.openmrs.api.APIAuthenticationException when the user holds none of
	 *             {@link ActCorePrivileges#SCREENING_COUNTS}, {@link ActCorePrivileges#DASHBOARD} and
	 *             {@link ActCorePrivileges#ENTER_SCREENING_COUNTS}
	 */
	@Authorized({ ActCorePrivileges.SCREENING_COUNTS, ActCorePrivileges.DASHBOARD,
	        ActCorePrivileges.ENTER_SCREENING_COUNTS })
	ScreeningCounts.Summary getSummary(Collection<Integer> clinics, YearMonth start, YearMonth end);
}
