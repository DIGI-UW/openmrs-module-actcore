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

/**
 * One entry of the activity log, such as a form saved or a flag dismissed. It tells what was done,
 * when it was done, which user did it when that is known, and what it was done to.
 */
public class ActivityEntry {
	
	/**
	 * The id of the Envers revision the save was recorded under, by which its fields can be listed. It
	 * is null for a flag dismissal, which ACT Core records outside the audit tables.
	 */
	public Integer revisionId;
	
	/** When the save was made. */
	public Date time;
	
	/** The user who made the save, or null when the user is not known. */
	public LogUser user;
	
	/**
	 * The kind of entry, one of
	 * {@link org.openmrs.module.actcore.api.search.ActivitySearchCriteria#KINDS}.
	 */
	public String kind;
	
	/** What was done, as the page shows it, such as "Saved RHD Consultation Visit". */
	public String action;
	
	/** A short account of what changed, or null when there is nothing to add to the action. */
	public String summary;
	
	/** What the change was made to, or null when the entry names no target. */
	public ActivityTarget target;
	
	/** The encounter the change was saved on, or null for a change not saved on an encounter. */
	public ActivityEncounter encounter;
}
