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

import org.openmrs.BaseOpenmrsData;
import org.openmrs.module.actcore.ActCoreConstants;

/**
 * One saved set of a clinic's screening counts for a month, with a count field for each column of
 * {@link ScreeningCounts#COLUMNS}, which {@link #getCounts()} returns keyed by column name. A later
 * save of the month voids this one rather than changing it.
 */
@Entity
@Table(name = ActCoreConstants.SCREENING_COUNT_TABLE)
public class ScreeningCount extends BaseOpenmrsData {
	
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
	
	protected ScreeningCount() {
	}
	
	public ScreeningCount(Integer locationId, Date month, Map<String, Integer> counts, String notes) {
		this.locationId = locationId;
		this.month = month;
		setCounts(counts);
		this.notes = notes;
	}
	
	/**
	 * Gets every count keyed by its column name, in the order of {@link ScreeningCounts#COLUMNS}.
	 */
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
		schoolScreened = counts.get("school_screened");
		schoolChildren = counts.get("school_children");
		schoolAdolescents = counts.get("school_adolescents");
		schoolAdults = counts.get("school_adults");
		schoolFemales = counts.get("school_females");
		schoolPositive = counts.get("school_positive");
		communityScreened = counts.get("community_screened");
		communityChildren = counts.get("community_children");
		communityAdolescents = counts.get("community_adolescents");
		communityAdults = counts.get("community_adults");
		communityFemales = counts.get("community_females");
		communityPositive = counts.get("community_positive");
		facilityScreened = counts.get("facility_screened");
		facilityChildren = counts.get("facility_children");
		facilityAdolescents = counts.get("facility_adolescents");
		facilityAdults = counts.get("facility_adults");
		facilityFemales = counts.get("facility_females");
		facilityPositive = counts.get("facility_positive");
	}
	
	/** @see org.openmrs.OpenmrsObject#getId() */
	@Override
	public Integer getId() {
		return screeningCountId;
	}
	
	/** @see org.openmrs.OpenmrsObject#setId(Integer) */
	@Override
	public void setId(Integer id) {
		screeningCountId = id;
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
	
}
