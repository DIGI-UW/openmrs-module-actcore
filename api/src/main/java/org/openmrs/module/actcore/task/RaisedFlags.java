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

import org.openmrs.module.actcore.ActCorePrivileges;

/**
 * The tables, settings and outcomes of {@link org.openmrs.module.actcore.api.RaisedFlagService},
 * which keeps the date each flag was raised for each patient, and the flag's dismissal. ACT Core
 * keeps these in tables of its own because the patientflags module recreates its own rows on every
 * save, so neither the date_created of its row nor a voided row outlives the next save.
 */
public class RaisedFlags {
	
	/**
	 * The table that holds one row per flag raised for a patient, with when it was raised and any
	 * dismissal. A row is deleted once its flag lowers.
	 */
	public static final String TABLE = "actcore_raised_flag";
	
	/**
	 * The table that keeps each dismissal for good, since the refresh deletes a row of {@link #TABLE}
	 * once its flag lowers.
	 */
	public static final String DISMISSAL_TABLE = "actcore_flag_dismissal";
	
	/** The privilege a user needs, unless they are a superuser, to dismiss a flag. */
	public static final String DISMISS_PRIVILEGE = ActCorePrivileges.DISMISS_FLAGS;
	
	/**
	 * The name of the global property that holds the tag a flag must carry to be dismissible. While it
	 * is blank, every flag may be dismissed.
	 */
	public static final String DISMISS_TAG_PROPERTY = "actcore.dismissFlagTag";
	
	/**
	 * The outcome of a dismissal: the flag was dismissed, it is not raised for the patient now, it may
	 * not be dismissed, or the full refresh is running.
	 *
	 * @see org.openmrs.module.actcore.api.RaisedFlagService#dismiss(org.openmrs.Patient,
	 *      org.openmrs.module.patientflags.Flag)
	 */
	public enum Dismissal {
		DISMISSED,
		NOT_RAISED,
		NOT_DISMISSIBLE,
		REFRESH_RUNNING
	}
}
