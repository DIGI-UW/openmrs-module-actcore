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

/** Database access for {@link org.openmrs.module.actcore.api.ScreeningCountService}. */
public interface ScreeningCountDAO {
	
	/**
	 * Gets the clinic's counts for the month that are not voided.
	 *
	 * @param locationId the clinic's location id
	 * @param month the first day of the month
	 * @return the current counts, an empty list when the month has none
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
	 * Saves a month's counts.
	 *
	 * @param count the counts to save
	 */
	void saveScreeningCount(ScreeningCount count);
	
	/**
	 * Gets the sums of the counts that are not voided, from start to end, both included.
	 *
	 * @param locationIds the clinics to sum, or null for every clinic
	 * @param start the first day of the first month
	 * @param end the first day of the last month
	 * @return the sums, all zero when nothing matches
	 */
	ScreeningCounts.Summary getSummary(Collection<Integer> locationIds, Date start, Date end);
}
