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

import org.junit.Test;
import org.openmrs.api.context.Context;

public class FullAdherenceRefreshContextTest extends AdherenceContextTest {
	
	@Test
	public void runIfIdle_shouldStoreTheAdherenceOfAPatientPrescribedARegimen() {
		prescribe(encounter(7, 40), q28, 40, null);
		Context.flushSession();
		
		FullAdherenceRefresh.runIfIdle();
		
		assertEquals(1, adherenceRows(7));
	}
	
	@Test
	public void refreshBeforeFlags_shouldStoreTheAdherenceOfAPatientPrescribedARegimen() {
		prescribe(encounter(7, 40), q28, 40, null);
		Context.flushSession();
		
		FullAdherenceRefresh.refreshBeforeFlags();
		
		assertEquals(1, adherenceRows(7));
	}
	
	private static int adherenceRows(int patientId) {
		return ((Number) Context.getAdministrationService()
		        .executeSQL("select count(*) from actcore_prophylaxis_adherence where patient_id = " + patientId, true)
		        .get(0).get(0)).intValue();
	}
}
