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
 * Holds the {@link Entry} that
 * {@link org.openmrs.module.actcore.api.DownloadLogService#getDownloads(Integer, java.time.LocalDate, java.time.LocalDate)}
 * lists.
 */
public class DownloadLog {
	
	/** One recorded download, as the Logs page lists it. */
	public static class Entry {
		
		/** When the download was recorded. */
		public Date date;
		
		/** The uuid of the user who downloaded. */
		public String userUuid;
		
		/**
		 * The uuid of the form downloaded when the download held exactly one form. It is null for any other
		 * download, and once the form is purged.
		 */
		public String formUuid;
		
		/** The name users see for the form. It is null whenever {@link #formUuid} is null. */
		public String formName;
		
		/** What the download held, such as its forms, columns and filters, as JSON. */
		public String detail;
	}
}
