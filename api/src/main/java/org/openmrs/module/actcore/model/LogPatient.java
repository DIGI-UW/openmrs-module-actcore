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

/** A patient as a log names them, by name and ACT ID. */
public class LogPatient {
	
	/** The patient's person id. */
	public Integer id;
	
	/** The patient's uuid. */
	public String uuid;
	
	/** The patient's name, or their uuid when they have no name. */
	public String display;
	
	/** The patient's ACT ID, or null when they have no identifier that can serve as one. */
	public String actId;
}
