/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.logs;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.apache.commons.io.IOUtils;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.Transactions;

/**
 * Makes the audit tables of audit-tables.sql, which the test context does not create, and clears
 * them; H2 commits DDL at once, so the tables outlive the test and only their rows are cleared.
 */
public final class AuditTables {
	
	private AuditTables() {
	}
	
	public static void create() {
		Transactions.inNewTransaction(() -> {
			for (String statement : script().replaceAll("(?m)^--.*$", "").split(";")) {
				if (statement.trim().startsWith("create table")) {
					Context.getAdministrationService().executeSQL(statement.trim(), false);
					String table = statement.trim().split("\\s+")[5];
					Context.getAdministrationService().executeSQL("delete from " + table, false);
				}
			}
		});
	}
	
	private static String script() {
		try (InputStream script = AuditTables.class.getClassLoader().getResourceAsStream("audit-tables.sql")) {
			return IOUtils.toString(script, StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}
}
