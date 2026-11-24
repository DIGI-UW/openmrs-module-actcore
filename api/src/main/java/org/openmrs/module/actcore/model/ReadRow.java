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

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * One row of the read log: a user's reads of one patient, or of one entity when the reads found no
 * single patient, that fall within an hour of the first of them.
 */
public class ReadRow {
	
	/** An id for the row, made of the user, the patient and the id of the row's first read. */
	public String id;
	
	/** When the row's first read happened. */
	public Date from;
	
	/** When the row's last read happened. */
	public Date to;
	
	/** The user who read. */
	public LogUser user;
	
	/** The patient read, or null when the reads found no single patient. */
	public ReadPatient patient;
	
	/** The row's reads, oldest first. */
	public final List<ReadEvent> reads = new ArrayList<ReadEvent>();
	
	/**
	 * A short label for the session the first read was made in, derived from the session id rather than
	 * the id itself, or null when no session was recorded.
	 */
	public String sessionId;
	
	/** The IP address the first read came from. */
	public String ip;
	
	/** The user agent of the browser that made the first read. */
	public String userAgent;
	
	/**
	 * Whether the user and the patient share a clinic. It is null when the reads found no single
	 * patient, when the patient is purged, when the user has no clinics on their account, or when the
	 * patient has none recorded.
	 */
	public Boolean sameClinicAsUser;
}
