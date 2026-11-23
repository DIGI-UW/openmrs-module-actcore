/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.task;

import java.util.Date;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;

import org.openmrs.BaseOpenmrsObject;

/** One dismissal of a patient's flag, kept after the flag lowers or is purged. */
@Entity
@Table(name = RaisedFlags.DISMISSAL_TABLE)
public class FlagDismissal extends BaseOpenmrsObject {
	
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "dismissal_id")
	private Integer dismissalId;
	
	@Column(name = "patient_id", nullable = false)
	private Integer patientId;
	
	@Column(name = "flag_id", nullable = false)
	private Integer flagId;
	
	@Column(name = "dismissed_by")
	private Integer dismissedBy;
	
	@Temporal(TemporalType.TIMESTAMP)
	@Column(name = "date_dismissed", nullable = false)
	private Date dateDismissed;
	
	protected FlagDismissal() {
	}
	
	public FlagDismissal(Integer patientId, Integer flagId, Integer dismissedBy, Date dateDismissed) {
		this.patientId = patientId;
		this.flagId = flagId;
		this.dismissedBy = dismissedBy;
		this.dateDismissed = dateDismissed;
	}
	
	/** @see org.openmrs.OpenmrsObject#getId() */
	@Override
	public Integer getId() {
		return dismissalId;
	}
	
	/** @see org.openmrs.OpenmrsObject#setId(Integer) */
	@Override
	public void setId(Integer id) {
		dismissalId = id;
	}
	
	public Integer getPatientId() {
		return patientId;
	}
	
	public Integer getFlagId() {
		return flagId;
	}
	
	public Integer getDismissedBy() {
		return dismissedBy;
	}
	
	public Date getDateDismissed() {
		return dateDismissed;
	}
}
