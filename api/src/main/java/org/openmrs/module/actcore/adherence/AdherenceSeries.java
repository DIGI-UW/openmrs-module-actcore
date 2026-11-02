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
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedSet;

import org.openmrs.api.context.Context;
import org.openmrs.util.PrivilegeConstants;

/**
 * Each patient's adherence as ACT 2.0 would have held it on past days, replayed in memory from the
 * records as they are now; nothing is written.
 */
public final class AdherenceSeries {
	
	private AdherenceSeries() {
	}
	
	/**
	 * By patient, then by day, the adherence held on that day: absent for a day the patient held no row
	 * or no adherence. Each history is read and replayed once.
	 */
	public static Map<Integer, Map<LocalDate, Double>> adherenceOn(Collection<Integer> patientIds,
	        SortedSet<LocalDate> days) {
		Map<Integer, Map<LocalDate, Double>> adherence = new HashMap<Integer, Map<LocalDate, Double>>();
		if (patientIds.isEmpty() || days.isEmpty()) {
			return adherence;
		}
		Map<Integer, AdherenceReplay.History> histories;
		try {
			Context.addProxyPrivilege(PrivilegeConstants.SQL_LEVEL_ACCESS);
			histories = new AdherenceRefresh().read(Context.getAdministrationService(), patientIds);
		}
		finally {
			Context.removeProxyPrivilege(PrivilegeConstants.SQL_LEVEL_ACCESS);
		}
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
