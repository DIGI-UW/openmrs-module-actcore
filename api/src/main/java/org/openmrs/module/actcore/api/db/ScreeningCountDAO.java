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

import java.util.Collection;
import java.util.Date;
import java.util.List;

import org.openmrs.module.actcore.screening.ScreeningCount;
import org.openmrs.module.actcore.screening.ScreeningCounts;

/**
 * The database access behind {@link org.openmrs.module.actcore.api.ScreeningCountService}, which
 * reads, locks, saves and sums the screening counts.
 */
public interface ScreeningCountDAO {
	
	/**
	 * Gets the clinic's counts for the month that are not voided.
	 *
	 * @param locationId the clinic's location id
	 * @param month the first day of the month
	 * @return the current counts; an empty list when the month has none
	 */
	List<ScreeningCount> getCurrentCounts(Integer locationId, Date month);
	
	/**
	 * Locks the clinic's location row until the transaction ends, so that saves for one clinic take
	 * turns.
	 *
	 * @param locationId the clinic's location id
	 */
	void lockLocation(Integer locationId);
	
	/**
	 * Saves one clinic's counts for a month in the current session.
	 *
	 * @param count the counts to save
	 */
	void saveScreeningCount(ScreeningCount count);
	
	/**
	 * Gets the sums of the counts that are not voided, over the months from start to end, both
	 * included, with how many clinic months they come from.
	 *
	 * @param locationIds the location ids of the clinics to sum, or null for every clinic; it must not
	 *            be empty, since the query cannot list no clinics
	 * @param start the first day of the first month
	 * @param end the first day of the last month
	 * @return each column's sum and the number of clinic months summed; every sum is zero when nothing
	 *         matches
	 */
	ScreeningCounts.Summary getSummary(Collection<Integer> locationIds, Date start, Date end);
}
