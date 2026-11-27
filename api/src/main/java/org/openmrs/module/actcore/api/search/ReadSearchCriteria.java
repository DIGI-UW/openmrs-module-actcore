/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.api.search;

import java.time.LocalDate;

import org.openmrs.module.actcore.ActCoreConstants;

/**
 * The filters that choose which reads of patient data the read log lists, and which page of the
 * matching rows to return.
 * <p>
 * A filter left null is not applied. {@code patient} matches a patient by uuid, or else matches the
 * patients with an unvoided identifier equal to it, such as an ACT ID or a National ID, or with a
 * given, middle or family name containing it. {@code from} and {@code to} are both included, each
 * as a whole day. {@code startIndex} and {@code limit} pick the page, the limit defaulting to
 * {@link ActCoreConstants#LOG_DEFAULT_LIMIT}.
 *
 * @see org.openmrs.module.actcore.api.AuditLogService#getReads(ReadSearchCriteria)
 */
public class ReadSearchCriteria {
	
	/** The username, as the read audit recorded it, of the user whose reads to list. */
	private String username;
	
	/**
	 * The patient whose reads to list: a patient uuid, or else an identifier to match exactly or part
	 * of a name to search for.
	 */
	private String patient;
	
	/** The first day whose reads to list. */
	private LocalDate from;
	
	/** The last day whose reads to list. */
	private LocalDate to;
	
	/** The index, from 0, of the first matching row to return. */
	private int startIndex;
	
	/** The most rows to return. */
	private int limit = ActCoreConstants.LOG_DEFAULT_LIMIT;
	
	public String getUsername() {
		return username;
	}
	
	public void setUsername(String username) {
		this.username = username;
	}
	
	public String getPatient() {
		return patient;
	}
	
	public void setPatient(String patient) {
		this.patient = patient;
	}
	
	public LocalDate getFrom() {
		return from;
	}
	
	public void setFrom(LocalDate from) {
		this.from = from;
	}
	
	public LocalDate getTo() {
		return to;
	}
	
	public void setTo(LocalDate to) {
		this.to = to;
	}
	
	public int getStartIndex() {
		return startIndex;
	}
	
	public void setStartIndex(int startIndex) {
		this.startIndex = startIndex;
	}
	
	public int getLimit() {
		return limit;
	}
	
	public void setLimit(int limit) {
		this.limit = limit;
	}
}
