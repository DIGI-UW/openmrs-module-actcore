/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore;

/**
 * The privileges ACT Core checks, declared in its config.xml so a deployment without the
 * distribution still has them; the only place each name is spelled.
 */
public final class ActCorePrivileges {
	
	public static final String ALL_CLINICS = "Task: act.allClinics";
	
	public static final String DISMISS_FLAGS = "Task: act.dismissFlags";
	
	public static final String DOWNLOAD_DATA = "Task: act.downloadData";
	
	public static final String DOWNLOAD_IDENTIFIERS = "Task: act.downloadIdentifiers";
	
	public static final String DOWNLOAD_RESEARCH_DATA = "Task: act.downloadResearchData";
	
	public static final String ENTER_SCREENING_COUNTS = "Task: act.enterScreeningCounts";
	
	public static final String MANAGE_STUDIES = "Task: act.manageStudies";
	
	public static final String REFRESH_FLAGS = "Task: act.refreshFlags";
	
	public static final String DASHBOARD = "App: act.dashboard";
	
	public static final String SCREENING_COUNTS = "App: act.screeningCounts";
	
	private ActCorePrivileges() {
	}
}
