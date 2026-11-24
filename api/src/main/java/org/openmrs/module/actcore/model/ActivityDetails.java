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

import java.util.ArrayList;
import java.util.List;

/**
 * The expansion of one activity log entry: each field the save changed, with its value before and
 * after, and the reason given when the save voided something.
 */
public class ActivityDetails {
	
	/** The id of the entry's revision. */
	public int revisionId;
	
	/** The fields the save changed. */
	public final List<ActivityField> fields = new ArrayList<ActivityField>();
	
	/** The reason given for a void, or null when the save voided nothing or gave no reason. */
	public String voidReason;
}
