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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.commons.io.IOUtils;
import org.openmrs.api.context.Context;
import org.openmrs.api.db.hibernate.DbSessionFactory;
import org.openmrs.module.actcore.ModuleTables;
import org.openmrs.module.actcore.Transactions;

/**
 * Makes the module's tables and the audit tables of audit-tables.sql, which the test context does
 * not create, and empties them.
 */
public final class AuditTables {
	
	private AuditTables() {
	}
	
	public static void create() {
		ModuleTables.create();
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
	
	/**
	 * Runs a query as the logs run theirs, giving each row as a map of column label to value, looked up
	 * ignoring case.
	 */
	public static List<Map<String, Object>> select(String sql, Object... params) {
		return Context.getRegisteredComponent("dbSessionFactory", DbSessionFactory.class).getCurrentSession()
		        .doReturningWork(connection -> {
			        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
			        try (PreparedStatement statement = connection.prepareStatement(sql)) {
				        for (int i = 0; i < params.length; i++) {
					        statement.setObject(i + 1, params[i]);
				        }
				        ResultSet results = statement.executeQuery();
				        ResultSetMetaData columns = results.getMetaData();
				        while (results.next()) {
					        Map<String, Object> row = new TreeMap<String, Object>(String.CASE_INSENSITIVE_ORDER);
					        for (int i = 1; i <= columns.getColumnCount(); i++) {
						        row.put(columns.getColumnLabel(i), results.getObject(i));
					        }
					        rows.add(row);
				        }
			        }
			        return rows;
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
