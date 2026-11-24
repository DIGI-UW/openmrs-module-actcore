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
	
	/** What a patient held on a day: the adherence, and when the next dose was due. */
	public static final class Day {
		
		public final double adherence;
		
		/** Null when the replay set none. */
		public final LocalDate nextDue;
		
		Day(double adherence, LocalDate nextDue) {
			this.adherence = adherence;
			this.nextDue = nextDue;
		}
		
		/** Covered on the day: the next dose not yet due, as the care cascade's Covered today reads it. */
		public boolean coveredOn(LocalDate day) {
			return nextDue != null && !nextDue.isBefore(day);
		}
	}
	
	/**
	 * By patient, then by day, the adherence held on that day and when the next dose was due: absent
	 * for a day the patient held no row or no adherence. Each history is read and replayed once.
	 */
	public static Map<Integer, Map<LocalDate, Day>> adherenceOn(Collection<Integer> patientIds, SortedSet<LocalDate> days) {
		Map<Integer, Map<LocalDate, Day>> adherence = new HashMap<Integer, Map<LocalDate, Day>>();
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
			Map<LocalDate, Day> byDay = new HashMap<LocalDate, Day>();
			for (Map.Entry<LocalDate, AdherenceReplay.Row> row : AdherenceReplay
			        .replayOn(entry.getValue(), days, oralFirstDueDays).entrySet()) {
				if (row.getValue() != null && row.getValue().adherence != null) {
					byDay.put(row.getKey(), new Day(row.getValue().adherence, row.getValue().nextDue));
				}
			}
			adherence.put(entry.getKey(), byDay);
		}
		return adherence;
	}
}
