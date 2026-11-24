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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Holds the {@link Filter} that
 * {@link org.openmrs.module.actcore.api.DownloadLogService#getDownloads(Filter)} takes and the
 * {@link Page} of {@link Download}s it returns, and the {@link Entry} rows the download log's DAO
 * reads.
 */
public class DownloadLog {
	
	/** The number of downloads a page holds when the filter does not say how many it wants. */
	public static final int DEFAULT_LIMIT = 100;
	
	/** The most downloads a page holds, whatever the filter asks for. */
	public static final int MAX_LIMIT = 500;
	
	/** One recorded download, as the log holds it. */
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
	
	/** Which recorded downloads to list, and which page of them. A filter left null is not applied. */
	public static class Filter {
		
		/** The username, or the system ID of a user without one, of the user whose downloads to list. */
		public String username;
		
		/** The first day whose downloads to list. */
		public LocalDate from;
		
		/** The last day whose downloads to list. */
		public LocalDate to;
		
		/**
		 * The uuid of a form the downloads must hold, either as the one form downloaded or as one of
		 * several.
		 */
		public String formUuid;
		
		/**
		 * What the downloads must have held: "research" for research data only, "with" for any other
		 * download that gave identifiers, and "without" for the rest.
		 */
		public String identifiers;
		
		/** The index, from 0, of the first matching download to return. */
		public int startIndex;
		
		/**
		 * The most downloads to return, capped at {@link #MAX_LIMIT}; below 1 for {@link #DEFAULT_LIMIT}.
		 */
		public int limit = DEFAULT_LIMIT;
	}
	
	/** One page of the downloads a filter matches, with how many it matches in all. */
	public static class Page {
		
		/** The downloads on the page, newest first. */
		public final List<Download> results = new ArrayList<Download>();
		
		/** How many downloads the filter matches across every page. */
		public int totalCount;
	}
	
	/** One recorded download, as the Logs page lists it. */
	public static class Download {
		
		/** When the download was recorded. */
		public Date date;
		
		/** The user who downloaded, or null when no user has the uuid recorded. */
		public LogUser user;
		
		/** The uuid of the form downloaded when the download held exactly one form, else null. */
		public String formUuid;
		
		/** The name users see for the form. It is null whenever {@link #formUuid} is null. */
		public String formName;
		
		/** What the download held, read from its JSON, or null when nothing was recorded. */
		public Map<?, ?> detail;
	}
}
