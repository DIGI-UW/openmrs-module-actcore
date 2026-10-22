/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.task;

import org.openmrs.api.context.Context;

/**
 * The module's liquibase changeset is not run by the test context, so the table is made here; H2
 * commits DDL at once, so it outlives the test and only its rows are cleared.
 */
public final class RaisedFlagsTable {
	
	private RaisedFlagsTable() {
	}
	
	public static void create() {
		Context.getAdministrationService().executeSQL("create table if not exists " + RaisedFlags.TABLE
		        + " (patient_id int not null, flag_id int not null, raised_since datetime not null, dismissed_by int,"
		        + " date_dismissed datetime, primary key (patient_id, flag_id))",
		    false);
		Context.getAdministrationService().executeSQL("delete from " + RaisedFlags.TABLE, false);
		Context.getAdministrationService()
		        .executeSQL("create table if not exists " + RaisedFlags.DISMISSAL_TABLE
		                + " (dismissal_id int auto_increment primary key, patient_id int not null, flag_id int not null,"
		                + " dismissed_by int, date_dismissed datetime not null)",
		            false);
		Context.getAdministrationService().executeSQL("delete from " + RaisedFlags.DISMISSAL_TABLE, false);
	}
}
