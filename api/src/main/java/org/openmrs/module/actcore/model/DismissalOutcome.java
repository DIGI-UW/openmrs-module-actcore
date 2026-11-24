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

/**
 * The outcome of a dismissal: the flag was dismissed, it is not raised for the patient now, it may
 * not be dismissed, or the full refresh is running.
 *
 * @see org.openmrs.module.actcore.api.RaisedFlagService#dismiss(org.openmrs.Patient,
 *      org.openmrs.module.patientflags.Flag)
 */
public enum DismissalOutcome {
	DISMISSED,
	NOT_RAISED,
	NOT_DISMISSIBLE,
	REFRESH_RUNNING
}
