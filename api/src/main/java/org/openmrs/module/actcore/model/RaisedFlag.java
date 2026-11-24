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

import java.io.Serializable;
import java.util.Date;
import java.util.Objects;

import javax.persistence.Column;
import javax.persistence.Embeddable;
import javax.persistence.EmbeddedId;
import javax.persistence.Entity;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;

import org.openmrs.module.actcore.ActCoreConstants;

/**
 * A flag raised for a patient, as {@link org.openmrs.module.actcore.api.RaisedFlagService} keeps
 * it: since when it has been raised, and, once it is dismissed, who dismissed it and when.
 */
@Entity
@Table(name = ActCoreConstants.RAISED_FLAG_TABLE)
public class RaisedFlag {
	
	/** The key of a raised flag: the patient and the flag, which together identify it. */
	@Embeddable
	public static class Key implements Serializable {
		
		@Column(name = "patient_id")
		private Integer patientId;
		
		@Column(name = "flag_id")
		private Integer flagId;
		
		protected Key() {
		}
		
		public Key(Integer patientId, Integer flagId) {
			this.patientId = patientId;
			this.flagId = flagId;
		}
		
		public Integer getPatientId() {
			return patientId;
		}
		
		public Integer getFlagId() {
			return flagId;
		}
		
		/** @see Object#equals(Object) */
		@Override
		public boolean equals(Object other) {
			return other instanceof Key && Objects.equals(patientId, ((Key) other).patientId)
			        && Objects.equals(flagId, ((Key) other).flagId);
		}
		
		/** @see Object#hashCode() */
		@Override
		public int hashCode() {
			return Objects.hash(patientId, flagId);
		}
	}
	
	@EmbeddedId
	private Key key;
	
	@Temporal(TemporalType.TIMESTAMP)
	@Column(name = "raised_since", nullable = false)
	private Date raisedSince;
	
	@Column(name = "dismissed_by")
	private Integer dismissedBy;
	
	@Temporal(TemporalType.TIMESTAMP)
	@Column(name = "date_dismissed")
	private Date dateDismissed;
	
	protected RaisedFlag() {
	}
	
	public RaisedFlag(Key key, Date raisedSince) {
		this.key = key;
		this.raisedSince = raisedSince;
	}
	
	public Key getKey() {
		return key;
	}
	
	public Date getRaisedSince() {
		return raisedSince;
	}
	
	public Integer getDismissedBy() {
		return dismissedBy;
	}
	
	public Date getDateDismissed() {
		return dateDismissed;
	}
}
