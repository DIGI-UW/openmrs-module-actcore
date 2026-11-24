/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.api.db.hibernate;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

import org.junit.Test;
import org.openmrs.api.context.Context;
import org.openmrs.api.db.hibernate.DbSessionFactory;
import org.openmrs.test.BaseModuleContextSensitiveTest;

public class HibernateRegistryDAOTest extends BaseModuleContextSensitiveTest {
	
	@Test
	public void getDeadPatientsWithOpenEnrolments_shouldGiveDeadUnvoidedPatientsWithAnOpenEnrolmentInThePrograms() {
		enrol(6, 1);
		enrol(7, 3);
		enrol(432, 1);
		sql("update person set dead = true where person_id in (2, 7, 8, 432)");
		sql("update patient_program set date_completed = '2009-01-01' where patient_program_id = 4");
		HibernateRegistryDAO dao = new HibernateRegistryDAO();
		dao.setSessionFactory(Context.getRegisteredComponent("dbSessionFactory", DbSessionFactory.class));
		
		assertEquals(Collections.singletonList(2), dao.getDeadPatientsWithOpenEnrolments(Arrays.asList(1, 2)));
	}
	
	private static void enrol(int patientId, int programId) {
		sql("insert into patient_program (patient_id, program_id, date_enrolled, creator, date_created, voided, uuid)"
		        + " values (" + patientId + ", " + programId + ", '2009-01-01', 1, now(), false, '" + UUID.randomUUID()
		        + "')");
	}
	
	private static void sql(String statement) {
		Context.getAdministrationService().executeSQL(statement, false);
	}
}
