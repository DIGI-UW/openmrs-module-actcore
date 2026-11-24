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

import java.util.Map;

import org.openmrs.module.actcore.api.db.AdherenceDAO;

/**
 * A full adherence refresh that runs a step after it has read every patient's history and before it
 * computes their rows.
 */
public final class AdherenceRefreshInTest {
	
	private AdherenceRefreshInTest() {
	}
	
	public static AdherenceRefresh afterReadingEveryPatient(AdherenceDAO dao, final Runnable step) {
		return new AdherenceRefresh(dao) {
			
			@Override
			Map<Integer, AdherenceReplay.History> read(Integer patientId) {
				Map<Integer, AdherenceReplay.History> histories = super.read(patientId);
				if (patientId == null) {
					step.run();
				}
				return histories;
			}
		};
	}
}
