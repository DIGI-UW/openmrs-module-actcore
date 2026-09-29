/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.rhdflags.adherence;

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
	
	@Test
	public void batchEntered_shouldHoldTheLastBatchUntilItsIntervalHasPassed() {
		LocalDate today = LocalDate.of(2026, 9, 29);
		LocalDate consulted = today.minusDays(89);
		// ACT 2.0 skipped the patient while fewer than the interval's days had passed.
		assertEquals(consulted, AdherenceRefresh.batchEntered(today, 90, consulted, today.minusDays(120)));
		assertEquals(today, AdherenceRefresh.batchEntered(today, 90, today.minusDays(90), null));
		// The later of the consultation and the last injection counts.
		assertEquals(today.minusDays(10), AdherenceRefresh.batchEntered(today, 90, consulted, today.minusDays(10)));
		assertEquals(today, AdherenceRefresh.batchEntered(today, null, consulted, null));
		assertEquals(today, AdherenceRefresh.batchEntered(today, 90, null, null));
	}
}
