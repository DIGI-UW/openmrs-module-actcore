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

import static org.mockito.Mockito.mock;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.openmrs.module.actcore.api.db.AdherenceDAO;

/**
 * A full adherence refresh that runs a step after it has read every patient's history and before it
 * computes their rows, given as the function that runs it as of a day and returns how many patients
 * it wrote a row for.
 */
public final class AdherenceRefreshInTest {
	
	private AdherenceRefreshInTest() {
	}
	
	public static Function<LocalDate, Integer> afterReadingEveryPatient(AdherenceDAO dao, final Runnable step) {
		return new AdherenceRefresh(dao) {
			
			@Override
			Map<Integer, AdherenceReplay.History> read(Integer patientId) {
				Map<Integer, AdherenceReplay.History> histories = super.read(patientId);
				if (patientId == null) {
					step.run();
				}
				return histories;
			}
		}::refreshAll;
	}
	
	/**
	 * Runs a full adherence refresh that reads no history and writes nothing, and runs the step while
	 * it holds every patient's rows, as it holds them while it rewrites them.
	 *
	 * @param step what to run while the refresh holds the rows
	 * @return the patients the refresh read one by one, as it does for those left to it
	 */
	public static List<Integer> whileRewritingEveryPatient(final Runnable step) {
		final List<Integer> readAfter = new ArrayList<Integer>();
		new AdherenceRefresh(mock(AdherenceDAO.class)) {
			
			@Override
			Map<Integer, AdherenceReplay.History> read(Integer patientId) {
				if (patientId == null) {
					step.run();
				} else {
					readAfter.add(patientId);
				}
				return new HashMap<Integer, AdherenceReplay.History>();
			}
		}.refreshAll(LocalDate.now());
		return readAfter;
	}
}
