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
import static org.junit.Assert.assertFalse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;
import org.openmrs.api.context.Context;
import org.openmrs.api.db.hibernate.DbSessionFactory;
import org.openmrs.test.BaseModuleContextSensitiveTest;

public class HibernateRegistryDAOTest extends BaseModuleContextSensitiveTest {
	
	@Test
	public void getDeadPatientsWithOpenEnrolments_shouldGiveThePatientsOfItsSql() {
		Context.getAdministrationService().executeSQL(
		    "update person set dead = true where person_id in (select patient_id from patient_program)", false);
		HibernateRegistryDAO dao = new HibernateRegistryDAO();
		dao.setSessionFactory(Context.getRegisteredComponent("dbSessionFactory", DbSessionFactory.class));
		
		List<Integer> expected = new ArrayList<Integer>();
		for (List<Object> row : Context.getAdministrationService()
		        .executeSQL("select distinct pp.patient_id from patient_program pp"
		                + " join person p on p.person_id = pp.patient_id where p.dead = true and p.voided = false"
		                + " and pp.voided = false and pp.date_completed is null and pp.program_id in (1,2)",
		            true)) {
			expected.add(((Number) row.get(0)).intValue());
		}
		assertFalse(expected.isEmpty());
		assertEquals(expected, dao.getDeadPatientsWithOpenEnrolments(Arrays.asList(1, 2)));
	}
}
