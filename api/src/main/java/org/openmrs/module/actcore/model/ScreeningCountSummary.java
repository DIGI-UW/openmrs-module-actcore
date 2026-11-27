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

import java.util.LinkedHashMap;
import java.util.Map;

/** The sums of the screening counts of the clinic months saved in a range. */
public class ScreeningCountSummary {
	
	/** How many clinic months were saved in the range. */
	private int months;
	
	/** Each column's sum, keyed by the column's name. */
	private Map<String, Long> sums = new LinkedHashMap<String, Long>();
	
	/**
	 * Sums a metric over every setting.
	 *
	 * @param metric one of {@link ScreeningCount#METRICS}
	 * @return the metric's sum over {@link ScreeningCount#SETTINGS}
	 */
	public Long total(String metric) {
		long total = 0;
		for (String setting : ScreeningCount.SETTINGS) {
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
	
	public int getMonths() {
		return months;
	}
	
	public void setMonths(int months) {
		this.months = months;
	}
	
	public Map<String, Long> getSums() {
		return sums;
	}
	
	public void setSums(Map<String, Long> sums) {
		this.sums = sums;
	}
}
