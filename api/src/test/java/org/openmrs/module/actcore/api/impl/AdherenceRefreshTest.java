/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.api.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;

import org.junit.Test;
import org.openmrs.api.APIException;
import org.openmrs.module.actcore.api.db.AdherenceDAO;

public class AdherenceRefreshTest {
	
	private static final LocalDate DAY = LocalDate.of(2026, 9, 2);
	
	@Test
	public void localDate_shouldReadADateColumnHoweverTheDriverGivesIt() {
		// H2 gives a Timestamp, the MariaDB driver on OpenMRS 2.8 a LocalDateTime.
		assertEquals(DAY, AdherenceRefresh.localDate(Timestamp.valueOf(DAY.atTime(23, 30))));
		assertEquals(DAY, AdherenceRefresh.localDate(LocalDateTime.of(DAY, java.time.LocalTime.of(0, 15))));
		assertEquals(DAY, AdherenceRefresh.localDate(DAY));
	}
	
	@Test(timeout = 10000)
	public void refreshAll_shouldStopWhenAPatientsRefreshHoldsTheRowsPastTheWait() throws InterruptedException {
		CountDownLatch holding = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		Thread patient = new Thread(() -> AdherenceRefresh.unlessRefreshingAll(7, () -> {
			holding.countDown();
			try {
				release.await();
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}));
		patient.start();
		holding.await();
		try {
			new AdherenceRefresh(mock(AdherenceDAO.class)) {
				
				@Override
				long rowsWaitMillis() {
					return 100;
				}
			}.refreshAll(DAY);
			fail("The refresh ran while a patient's refresh held the rows");
		}
		catch (APIException e) {
			assertTrue(e.getMessage(), e.getMessage().startsWith("The adherence refresh did not run"));
		}
		finally {
			release.countDown();
			patient.join();
		}
	}
}
