/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.adherence;

import static org.junit.Assert.assertEquals;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.Test;

public class AdherenceRefreshTest {
	
	private static final LocalDate DAY = LocalDate.of(2026, 9, 2);
	
	@Test
	public void localDate_shouldReadADateColumnHoweverTheDriverGivesIt() {
		// H2 gives a Timestamp, the MariaDB driver on OpenMRS 2.8 a LocalDateTime.
		assertEquals(DAY, AdherenceRefresh.localDate(Timestamp.valueOf(DAY.atTime(23, 30))));
		assertEquals(DAY, AdherenceRefresh.localDate(LocalDateTime.of(DAY, java.time.LocalTime.of(0, 15))));
		assertEquals(DAY, AdherenceRefresh.localDate(DAY));
	}
}
