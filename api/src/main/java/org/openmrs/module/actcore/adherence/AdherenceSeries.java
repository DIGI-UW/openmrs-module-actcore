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

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedSet;

/**
 * Replays patients' adherence as ACT 2.0 would have held it on past days. The replay runs in memory
 * over the records as they stand now and writes nothing, so the run charts can show adherence on
 * days no refresh stored.
 */
public final class AdherenceSeries {
	
	private AdherenceSeries() {
	}
	
	/**
	 * Gets the adherence each patient held on each of the days, keyed by patient id and then by day.
	 * Each patient's history is replayed once for all the days. A day on which the patient held no row,
	 * or a row without adherence, is absent from that patient's map.
	 *
	 * @param histories the histories of the patients to replay, by patient id
	 * @param days the days to replay each patient on
	 * @return each patient's adherence by day, for each patient with a history
	 */
	static Map<Integer, Map<LocalDate, Double>> adherenceOn(Map<Integer, AdherenceReplay.History> histories,
	        SortedSet<LocalDate> days) {
		Map<Integer, Map<LocalDate, Double>> adherence = new HashMap<Integer, Map<LocalDate, Double>>();
		Integer oralFirstDueDays = AdherenceRefresh.oralFirstDueDays();
		for (Map.Entry<Integer, AdherenceReplay.History> entry : histories.entrySet()) {
			Map<LocalDate, Double> byDay = new HashMap<LocalDate, Double>();
			for (Map.Entry<LocalDate, AdherenceReplay.Row> row : AdherenceReplay
			        .replayOn(entry.getValue(), days, oralFirstDueDays).entrySet()) {
				if (row.getValue() != null && row.getValue().adherence != null) {
					byDay.put(row.getKey(), row.getValue().adherence);
				}
			}
			adherence.put(entry.getKey(), byDay);
		}
		return adherence;
	}
}
