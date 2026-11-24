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

import org.openmrs.module.patientflags.Flag;

/**
 * Holds the {@link Dismissal} outcome that
 * {@link org.openmrs.module.actcore.api.RaisedFlagService#dismiss(org.openmrs.Patient, org.openmrs.module.patientflags.Flag)}
 * returns, and the {@link Raised} flags that
 * {@link org.openmrs.module.actcore.api.RaisedFlagService#getRaisedFlags(org.openmrs.Patient)}
 * lists.
 */
public class RaisedFlags {
	
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
	
	/** A flag raised for a patient, as the patient's chart lists it. */
	public static class Raised {
		
		/** The flag. */
		public final Flag flag;
		
		/** When ACT Core first recorded the flag as raised for the patient, or null when it has no date. */
		public final Date since;
		
		/** Whether the signed-in user may dismiss the flag. */
		public final boolean dismissible;
		
		/**
		 * Creates a raised flag.
		 *
		 * @param flag the flag
		 * @param since when the flag was raised, or null
		 * @param dismissible whether the user may dismiss it
		 */
		public Raised(Flag flag, Date since, boolean dismissible) {
			this.flag = flag;
			this.since = since;
			this.dismissible = dismissible;
		}
	}
}
