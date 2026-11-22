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

import java.net.URL;

import liquibase.Contexts;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.adherence.AdherenceRefresh;
import org.openmrs.module.actcore.download.DownloadLog;
import org.openmrs.module.actcore.screening.ScreeningCounts;
import org.openmrs.module.actcore.task.RaisedFlags;
import org.openmrs.util.DatabaseUpdater;

/**
 * Makes the module's tables by running its changelog as module start does, then empties them, so a
 * test runs against the schema that ships.
 */
public final class ModuleTables {
	
	private static final String CHANGELOG = "liquibase.xml";
	
	private static final String[] TABLES = { AdherenceRefresh.TABLE, AdherenceRefresh.TIMING_TABLE, DownloadLog.TABLE,
	        RaisedFlags.TABLE, RaisedFlags.DISMISSAL_TABLE, ScreeningCounts.TABLE };
	
	private ModuleTables() {
	}
	
	public static void create() {
		ClassLoader loader = ModuleTables.class.getClassLoader();
		URL changelog = loader.getResource(CHANGELOG);
		// patientflags, cohort and reporting jars each carry a liquibase.xml; this module's must come first.
		if (changelog == null || !"file".equals(changelog.getProtocol())) {
			throw new IllegalStateException(
			        "The module's " + CHANGELOG + " is not first on the test classpath: " + changelog);
		}
		try {
			DatabaseUpdater.executeChangelog(CHANGELOG, new Contexts(), null, loader);
		}
		catch (Exception e) {
			throw new IllegalStateException("The module's changelog does not run", e);
		}
		// Rows written by a transaction of their own outlive the test, so they are cleared in one too.
		Transactions.inNewTransaction(() -> {
			for (String table : TABLES) {
				Context.getAdministrationService().executeSQL("delete from " + table, false);
			}
		});
	}
}
