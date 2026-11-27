/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.api;

import org.openmrs.annotation.Authorized;
import org.openmrs.api.OpenmrsService;
import org.openmrs.module.actcore.ActCorePrivileges;
import org.openmrs.module.actcore.model.PatientFlagRefreshResult;
import org.openmrs.module.actcore.model.PatientFlagRefreshStatus;

/**
 * The service that refreshes the patient flags. The patientflags module evaluates a flag only when
 * a patient's data is written, so without this refresh a criterion that becomes true with the
 * passing of time would never raise its flag. A full refresh first refreshes adherence, then
 * re-evaluates every enabled, unretired flag, writing only the flag rows that changed, and then
 * syncs the flag lists. The scheduler and the refresh endpoint run it, and the advice on a
 * patient's saves refreshes that one patient through it.
 */
public interface PatientFlagRefreshService extends OpenmrsService {
	
	/**
	 * Runs the full refresh now unless another run is in progress, and reports what failed in it. When
	 * the run finishes, it refreshes the patients whose own refresh was left to it. Call it outside a
	 * transaction, since each flag and list is written as a unit of its own. The scheduler, and the
	 * refresh endpoint on a daemon thread, call it.
	 *
	 * @return the flags and lists that failed and whether the adherence refresh failed, or null when
	 *         another run was in progress
	 * @throws org.openmrs.api.APIAuthenticationException when the user lacks
	 *             {@link ActCorePrivileges#REFRESH_FLAGS}
	 * @throws org.openmrs.api.APIException when a patient's refresh still holds the rows after a
	 *             minute, or the wait for them is interrupted; the next run can then start
	 */
	@Authorized(ActCorePrivileges.REFRESH_FLAGS)
	PatientFlagRefreshResult refreshAll();
	
	/**
	 * Gets when the full refresh last finished and whether it is running now.
	 *
	 * @return the status; its last finished is null before the first run finishes
	 * @throws org.openmrs.api.APIAuthenticationException when the user lacks
	 *             {@link ActCorePrivileges#REFRESH_FLAGS}
	 */
	@Authorized(ActCorePrivileges.REFRESH_FLAGS)
	PatientFlagRefreshStatus getStatus();
	
	/**
	 * Re-evaluates one patient's flags and updates the patient's place on the flag lists, after a save
	 * that the patientflags module's own pass may have evaluated before it committed. Each flag and
	 * list is written as a unit of its own, so call it outside a transaction. While a full refresh of
	 * the flags, or of adherence running on its own, is writing the rows, the patient is left to that
	 * run at once, which refreshes them when it ends. It needs no privilege beyond signing in, because
	 * it runs once the save of the patient or of any of their encounters commits, as whoever saved it,
	 * and it writes only the patient's own flag rows and list memberships.
	 *
	 * @param patientId the patient whose flags to refresh
	 * @throws org.openmrs.api.APIAuthenticationException when no user is signed in
	 */
	@Authorized
	void refreshPatient(int patientId);
}
