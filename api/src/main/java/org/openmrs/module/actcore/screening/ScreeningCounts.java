/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.screening;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ACT 2.0's monthly screening counts: per clinic and month, for each setting, the people screened,
 * their age groups, the females and the positives, kept in actcore_screening_count.
 */
public class ScreeningCounts {
	
	public static final String TABLE = "actcore_screening_count";
	
	public static final List<String> SETTINGS = Collections
	        .unmodifiableList(Arrays.asList("school", "community", "facility"));
	
	public static final List<String> METRICS = Collections
	        .unmodifiableList(Arrays.asList("screened", "children", "adolescents", "adults", "females", "positive"));
	
	/** The table's count columns, setting_metric, in the order of SETTINGS and then METRICS. */
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
	
	public static final int NOTES_LENGTH = 1000;
	
	public static final String REPLACED_REASON = "Replaced by a later save of the month";
	
	/** One clinic's counts and notes for one month. */
	public static class Month {
		
		/** Whether the month has been saved; one that has not is all zeros. */
		public boolean saved;
		
		/** Every column's count. */
		public Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
		
		public String notes;
	}
	
	/** The counts of the clinic months saved in a range, summed. */
	public static class Summary {
		
		/** How many clinic months were saved in the range. */
		public int months;
		
		/** Every column's sum. */
		public Map<String, Long> sums = new LinkedHashMap<String, Long>();
		
		/**
		 * A metric summed over the settings.
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
		 * The people screened, over the settings.
		 *
		 * @return the sum of the settings' screened counts
		 */
		public Long screened() {
			return total("screened");
		}
		
		/**
		 * The people screened positive, over the settings.
		 *
		 * @return the sum of the settings' positive counts
		 */
		public Long positive() {
			return total("positive");
		}
		
		/**
		 * The people screened and not positive, over the settings.
		 *
		 * @return the people screened less those positive
		 */
		public Long negative() {
			return screened() - positive();
		}
	}
}
