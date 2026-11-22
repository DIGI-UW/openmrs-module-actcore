/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.download;

import java.util.Date;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;

/** One download, as {@link DownloadLog} records it. */
@Entity
@Table(name = DownloadLog.TABLE)
public class DownloadRecord {
	
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "download_id")
	private Integer downloadId;
	
	@Column(name = "user_id", nullable = false)
	private Integer userId;
	
	// No foreign key, so a purged form leaves the id of what was downloaded.
	@Column(name = "form_id")
	private Integer formId;
	
	@Column(name = "detail", columnDefinition = "text")
	private String detail;
	
	@Temporal(TemporalType.TIMESTAMP)
	@Column(name = "date_created", nullable = false)
	private Date dateCreated;
	
	protected DownloadRecord() {
	}
	
	public DownloadRecord(Integer userId, Integer formId, String detail, Date dateCreated) {
		this.userId = userId;
		this.formId = formId;
		this.detail = detail;
		this.dateCreated = dateCreated;
	}
	
	public Integer getDownloadId() {
		return downloadId;
	}
	
	public Integer getUserId() {
		return userId;
	}
	
	public Integer getFormId() {
		return formId;
	}
	
	public String getDetail() {
		return detail;
	}
	
	public Date getDateCreated() {
		return dateCreated;
	}
}
