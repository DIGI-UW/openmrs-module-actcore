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
import java.util.Map;

/** One recorded download, as the Logs page lists it. */
public class DownloadLogItem {
	
	/** When the download was recorded. */
	public Date date;
	
	/** The user who downloaded. */
	public LogUser user;
	
	/** The uuid of the form downloaded when the download held exactly one form, else null. */
	public String formUuid;
	
	/** The name users see for the form. It is null whenever {@link #formUuid} is null. */
	public String formName;
	
	/**
	 * What the download held, read from its JSON, or null when nothing was recorded or what was
	 * recorded cannot be read as JSON.
	 */
	public Map<?, ?> detail;
}
