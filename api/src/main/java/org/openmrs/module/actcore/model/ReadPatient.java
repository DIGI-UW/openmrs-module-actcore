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

/** The patient a read log row's reads are of, as the Logs page names them. */
public class ReadPatient {
	
	/** The patient's uuid. */
	public String uuid;
	
	/** The patient's name, or the name the read audit recorded once the patient is purged. */
	public String display;
	
	/** The patient's ACT ID, or null when they have none or are purged. */
	public String actId;
	
	/** The name of the patient's primary care clinic, or null when none is recorded. */
	public String primaryCareClinic;
	
	/** The name of the patient's cardiac clinic, or null when none is recorded. */
	public String cardiacClinic;
}
