/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The shape of ACT 2.0's monthly screening counts. For each clinic and month, and for each setting
 * where people were screened, the counts record the people screened, how many were children,
 * adolescents and adults, how many were female, and how many screened positive. They are kept in
 * {@link org.openmrs.module.actcore.ActCoreConstants#SCREENING_COUNT_TABLE}.
 */
public class ScreeningCounts {
	
	/** The settings where people are screened, in the order their columns take. */
	public static final List<String> SETTINGS = Collections
	        .unmodifiableList(Arrays.asList("school", "community", "facility"));
	
	/** The figures counted for each setting, in the order their columns take. */
	public static final List<String> METRICS = Collections
	        .unmodifiableList(Arrays.asList("screened", "children", "adolescents", "adults", "females", "positive"));
	
	/**
	 * The table's count columns, each named setting_metric, such as school_screened, in the order of
	 * {@link #SETTINGS} and then of {@link #METRICS}.
	 */
	public static final List<String> COLUMNS;
	
	static {
		List<String> columns = new ArrayList<String>();
		for (String setting : SETTINGS) {
			for (String metric : METRICS) {
				columns.add(setting + "_" + metric);
			}
		}
		COLUMNS = Collections.unmodifiableList(columns);
	}
	
	/** The most characters a month's notes may hold, after trimming. */
	public static final int NOTES_LENGTH = 1000;
	
	/** The void reason given to a month's counts when a later save of the month replaces them. */
	public static final String REPLACED_REASON = "Replaced by a later save of the month";
	
	/** One clinic's counts and notes for one month, as the Screening counts page shows them. */
	public static class Month {
		
		/**
		 * Whether counts have been saved for the month. A month that has not been saved has every count at
		 * zero.
		 */
		public boolean saved;
		
		/** Each column's count, keyed by the column's name. */
		public Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
		
		/** The notes saved with the counts, or null when there are none. */
		public String notes;
	}
	
	/** The sums of the counts of the clinic months saved in a range. */
	public static class Summary {
		
		/** How many clinic months were saved in the range. */
		public int months;
		
		/** Each column's sum, keyed by the column's name. */
		public Map<String, Long> sums = new LinkedHashMap<String, Long>();
		
		/**
		 * Sums a metric over every setting.
		 *
		 * @param metric one of {@link ScreeningCounts#METRICS}
		 * @return the metric's sum over {@link ScreeningCounts#SETTINGS}
		 */
		public Long total(String metric) {
			long total = 0;
			for (String setting : SETTINGS) {
				total += sums.get(setting + "_" + metric);
			}
			return total;
		}
		
		/**
		 * Gets how many people were screened across every setting.
		 *
		 * @return the sum of the settings' screened counts
		 */
		public Long screened() {
			return total("screened");
		}
		
		/**
		 * Gets how many people screened positive across every setting.
		 *
		 * @return the sum of the settings' positive counts
		 */
		public Long positive() {
			return total("positive");
		}
		
		/**
		 * Gets how many people screened negative across every setting, which is everyone screened who did
		 * not screen positive.
		 *
		 * @return the people screened less those positive
		 */
		public Long negative() {
			return screened() - positive();
		}
	}
}
