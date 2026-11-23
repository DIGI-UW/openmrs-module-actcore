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

import java.util.Date;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;

/**
 * A patient's prophylaxis adherence and next due date, as the latest adherence refresh computed
 * them. The refresh writes these rows in bulk by SQL rather than through this entity.
 */
@Entity
@Table(name = AdherenceRefresh.TABLE)
public class ProphylaxisAdherence {
	
	@Id
	@Column(name = "patient_id")
	private Integer patientId;
	
	@Column(name = "regimen_concept_id")
	private Integer regimenConceptId;
	
	@Column(name = "injection_interval_days")
	private Integer injectionIntervalDays;
	
	@Column(name = "adherence")
	private Double adherence;
	
	@Temporal(TemporalType.DATE)
	@Column(name = "last_given")
	private Date lastGiven;
	
	@Temporal(TemporalType.DATE)
	@Column(name = "next_due")
	private Date nextDue;
	
	@Temporal(TemporalType.TIMESTAMP)
	@Column(name = "date_computed", nullable = false)
	private Date dateComputed;
	
	public Integer getPatientId() {
		return patientId;
	}
	
	public Integer getRegimenConceptId() {
		return regimenConceptId;
	}
	
	public Integer getInjectionIntervalDays() {
		return injectionIntervalDays;
	}
	
	public Double getAdherence() {
		return adherence;
	}
	
	public Date getLastGiven() {
		return lastGiven;
	}
	
	public Date getNextDue() {
		return nextDue;
	}
	
	public Date getDateComputed() {
		return dateComputed;
	}
}
