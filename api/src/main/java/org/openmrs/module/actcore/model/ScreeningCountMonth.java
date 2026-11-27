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

/**
 * One clinic's screening counts and notes for one month, as the Screening counts page shows them.
 */
public class ScreeningCountMonth {
	
	/**
	 * Whether counts have been saved for the month. A month that has not been saved has every count at
	 * zero.
	 */
	private boolean saved;
	
	/** Each column's count, keyed by the column's name. */
	private Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
	
	/** The notes saved with the counts, or null when there are none. */
	private String notes;
	
	public boolean isSaved() {
		return saved;
	}
	
	public void setSaved(boolean saved) {
		this.saved = saved;
	}
	
	public Map<String, Integer> getCounts() {
		return counts;
	}
	
	public void setCounts(Map<String, Integer> counts) {
		this.counts = counts;
	}
	
	public String getNotes() {
		return notes;
	}
	
	public void setNotes(String notes) {
		this.notes = notes;
	}
}
