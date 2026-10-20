/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.task;

import static org.junit.Assert.assertEquals;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.Test;

public class RaisedFlagsTest {
	
	private static final LocalDateTime RAISED = LocalDateTime.of(2026, 9, 2, 23, 30);
	
	@Test
	public void date_shouldReadADatetimeColumnHoweverTheDriverGivesIt() {
		// H2 gives a Timestamp, the MariaDB driver on OpenMRS 2.8 a LocalDateTime.
		assertEquals(Timestamp.valueOf(RAISED), RaisedFlags.date(Timestamp.valueOf(RAISED)));
		assertEquals(Timestamp.valueOf(RAISED), RaisedFlags.date(RAISED));
		assertEquals(Timestamp.valueOf(RAISED.toLocalDate().atStartOfDay()), RaisedFlags.date(LocalDate.of(2026, 9, 2)));
	}
}
