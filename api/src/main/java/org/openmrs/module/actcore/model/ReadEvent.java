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

/** One read as the audit log module recorded it, as a row of the read log lists it. */
public class ReadEvent {
	
	/** When the read happened. */
	public Date time;
	
	/** The name of the entity read, such as Patient or Encounter. */
	public String entity;
	
	/**
	 * What was read, as the page shows it, such as "the patient record", "2 encounters" or "a search: 4
	 * patients", prefixed with "failed: " for a read that failed.
	 */
	public String description;
}
