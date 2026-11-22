/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.download;

import java.util.Date;

/**
 * Each download, as ACT 2.0's audit log kept it. The audit log module records reads and changes but
 * offers no way for another module to record an event of its own, so downloads are kept here.
 */
public class DownloadLog {
	
	public static final String TABLE = "actcore_download_log";
	
	/** The audit log module's, as a download is a read of patients' data. */
	public static final String VIEW_PRIVILEGE = "View Read Audit Logs";
	
	/** One download, with the form it was of, null once the form is purged. */
	public static class Entry {
		
		public Date date;
		
		public String userUuid;
		
		public String formUuid;
		
		public String formName;
		
		public String detail;
	}
}
