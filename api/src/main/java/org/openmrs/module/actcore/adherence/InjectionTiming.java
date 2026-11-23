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

/**
 * Whether a patient's injection on a day was on time, as the latest adherence refresh found. The
 * refresh writes these rows in bulk by SQL rather than through this entity.
 */
@Entity
@Table(name = AdherenceRefresh.TIMING_TABLE)
public class InjectionTiming {
	
	/** The key of a timing: the patient and the day of the injection, which together identify it. */
	@Embeddable
	public static class Key implements Serializable {
		
		@Column(name = "patient_id")
		private Integer patientId;
		
		@Temporal(TemporalType.DATE)
		@Column(name = "injection_date")
		private Date injectionDate;
		
		protected Key() {
		}
		
		public Key(Integer patientId, Date injectionDate) {
			this.patientId = patientId;
			this.injectionDate = injectionDate;
		}
		
		public Integer getPatientId() {
			return patientId;
		}
		
		public Date getInjectionDate() {
			return injectionDate;
		}
		
		/** @see Object#equals(Object) */
		@Override
		public boolean equals(Object other) {
			return other instanceof Key && Objects.equals(patientId, ((Key) other).patientId)
			        && Objects.equals(injectionDate, ((Key) other).injectionDate);
		}
		
		/** @see Object#hashCode() */
		@Override
		public int hashCode() {
			return Objects.hash(patientId, injectionDate);
		}
	}
	
	@EmbeddedId
	private Key key;
	
	@Column(name = "on_time", nullable = false)
	private boolean onTime;
	
	public Key getKey() {
		return key;
	}
	
	public boolean isOnTime() {
		return onTime;
	}
}
