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

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;

/**
 * A clinic's screening counts for a month, as {@link ScreeningCounts} keeps them; a count field per
 * column of {@link ScreeningCounts#COLUMNS}, whose names they are.
 */
@Entity
@Table(name = ScreeningCounts.TABLE)
public class ScreeningCount {
	
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "screening_count_id")
	private Integer screeningCountId;
	
	@Column(name = "location_id", nullable = false)
	private Integer locationId;
	
	@Temporal(TemporalType.DATE)
	@Column(name = "month", nullable = false)
	private Date month;
	
	@Column(name = "school_screened", nullable = false)
	private int schoolScreened;
	
	@Column(name = "school_children", nullable = false)
	private int schoolChildren;
	
	@Column(name = "school_adolescents", nullable = false)
	private int schoolAdolescents;
	
	@Column(name = "school_adults", nullable = false)
	private int schoolAdults;
	
	@Column(name = "school_females", nullable = false)
	private int schoolFemales;
	
	@Column(name = "school_positive", nullable = false)
	private int schoolPositive;
	
	@Column(name = "community_screened", nullable = false)
	private int communityScreened;
	
	@Column(name = "community_children", nullable = false)
	private int communityChildren;
	
	@Column(name = "community_adolescents", nullable = false)
	private int communityAdolescents;
	
	@Column(name = "community_adults", nullable = false)
	private int communityAdults;
	
	@Column(name = "community_females", nullable = false)
	private int communityFemales;
	
	@Column(name = "community_positive", nullable = false)
	private int communityPositive;
	
	@Column(name = "facility_screened", nullable = false)
	private int facilityScreened;
	
	@Column(name = "facility_children", nullable = false)
	private int facilityChildren;
	
	@Column(name = "facility_adolescents", nullable = false)
	private int facilityAdolescents;
	
	@Column(name = "facility_adults", nullable = false)
	private int facilityAdults;
	
	@Column(name = "facility_females", nullable = false)
	private int facilityFemales;
	
	@Column(name = "facility_positive", nullable = false)
	private int facilityPositive;
	
	@Column(name = "notes", length = ScreeningCounts.NOTES_LENGTH)
	private String notes;
	
	@Column(name = "changed_by")
	private Integer changedBy;
	
	@Temporal(TemporalType.TIMESTAMP)
	@Column(name = "date_changed", nullable = false)
	private Date dateChanged;
	
	protected ScreeningCount() {
	}
	
	public ScreeningCount(Integer locationId, Date month, Map<String, Integer> counts, String notes, Integer changedBy,
	    Date dateChanged) {
		this.locationId = locationId;
		this.month = month;
		setCounts(counts);
		this.notes = notes;
		this.changedBy = changedBy;
		this.dateChanged = dateChanged;
	}
	
	/** Every count by its column name, in the order of {@link ScreeningCounts#COLUMNS}. */
	public Map<String, Integer> getCounts() {
		Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
		counts.put("school_screened", schoolScreened);
		counts.put("school_children", schoolChildren);
		counts.put("school_adolescents", schoolAdolescents);
		counts.put("school_adults", schoolAdults);
		counts.put("school_females", schoolFemales);
		counts.put("school_positive", schoolPositive);
		counts.put("community_screened", communityScreened);
		counts.put("community_children", communityChildren);
		counts.put("community_adolescents", communityAdolescents);
		counts.put("community_adults", communityAdults);
		counts.put("community_females", communityFemales);
		counts.put("community_positive", communityPositive);
		counts.put("facility_screened", facilityScreened);
		counts.put("facility_children", facilityChildren);
		counts.put("facility_adolescents", facilityAdolescents);
		counts.put("facility_adults", facilityAdults);
		counts.put("facility_females", facilityFemales);
		counts.put("facility_positive", facilityPositive);
		return counts;
	}
	
	private void setCounts(Map<String, Integer> counts) {
		schoolScreened = counts.getOrDefault("school_screened", 0);
		schoolChildren = counts.getOrDefault("school_children", 0);
		schoolAdolescents = counts.getOrDefault("school_adolescents", 0);
		schoolAdults = counts.getOrDefault("school_adults", 0);
		schoolFemales = counts.getOrDefault("school_females", 0);
		schoolPositive = counts.getOrDefault("school_positive", 0);
		communityScreened = counts.getOrDefault("community_screened", 0);
		communityChildren = counts.getOrDefault("community_children", 0);
		communityAdolescents = counts.getOrDefault("community_adolescents", 0);
		communityAdults = counts.getOrDefault("community_adults", 0);
		communityFemales = counts.getOrDefault("community_females", 0);
		communityPositive = counts.getOrDefault("community_positive", 0);
		facilityScreened = counts.getOrDefault("facility_screened", 0);
		facilityChildren = counts.getOrDefault("facility_children", 0);
		facilityAdolescents = counts.getOrDefault("facility_adolescents", 0);
		facilityAdults = counts.getOrDefault("facility_adults", 0);
		facilityFemales = counts.getOrDefault("facility_females", 0);
		facilityPositive = counts.getOrDefault("facility_positive", 0);
	}
	
	public Integer getScreeningCountId() {
		return screeningCountId;
	}
	
	public Integer getLocationId() {
		return locationId;
	}
	
	public Date getMonth() {
		return month;
	}
	
	public String getNotes() {
		return notes;
	}
	
	public Integer getChangedBy() {
		return changedBy;
	}
	
	public Date getDateChanged() {
		return dateChanged;
	}
}
