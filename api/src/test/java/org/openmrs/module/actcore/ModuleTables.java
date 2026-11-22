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
import java.util.ArrayList;
import java.util.List;

import liquibase.Contexts;
import org.openmrs.api.context.Context;
import org.openmrs.util.DatabaseUpdater;

/**
 * Makes the module's tables by running its changelog as module start does, then empties them, so a
 * test runs against the schema that ships.
 */
public final class ModuleTables {
	
	private static final String CHANGELOG = "liquibase.xml";
	
	// Set only once the changelog has run, so a broken changeset fails every test that needs the tables.
	private static boolean changelogRan;
	
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
			if (!changelogRan) {
				// The test context's hbm2ddl makes the mapped tables first, and the changelog would skip them.
				Transactions.inNewTransaction(() -> {
					for (String table : moduleTables()) {
						Context.getAdministrationService().executeSQL("drop table " + table, false);
					}
				});
				DatabaseUpdater.executeChangelog(CHANGELOG, new Contexts(), null, loader);
				changelogRan = true;
			}
		}
		catch (Exception e) {
			throw new IllegalStateException("The module's changelog does not run", e);
		}
		// Rows written by a transaction of their own outlive the test, so they are cleared in one too.
		Transactions.inNewTransaction(() -> {
			for (String table : moduleTables()) {
				Context.getAdministrationService().executeSQL("delete from " + table, false);
			}
		});
	}
	
	private static List<String> moduleTables() {
		List<String> tables = new ArrayList<String>();
		for (List<Object> row : Context.getAdministrationService()
		        .executeSQL("select table_name from information_schema.tables where table_schema = 'PUBLIC'"
		                + " and lower(table_name) like 'actcore\\_%'",
		            true)) {
			tables.add((String) row.get(0));
		}
		return tables;
	}
}
