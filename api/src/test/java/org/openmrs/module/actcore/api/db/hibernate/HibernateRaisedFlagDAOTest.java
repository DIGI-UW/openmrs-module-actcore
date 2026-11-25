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
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.Before;
import org.junit.Test;
import org.openmrs.api.context.Context;
import org.openmrs.api.db.hibernate.DbSessionFactory;
import org.openmrs.module.patientflags.Flag;
import org.openmrs.module.patientflags.api.FlagService;
import org.openmrs.test.BaseModuleContextSensitiveTest;

/** The reads and deletes of the patientflags module's rows and the reads of cohorts. */
public class HibernateRaisedFlagDAOTest extends BaseModuleContextSensitiveTest {
	
	private HibernateRaisedFlagDAO dao;
	
	private Flag flag;
	
	@Before
	public void setUp() {
		dao = new HibernateRaisedFlagDAO();
		dao.setSessionFactory(Context.getRegisteredComponent("dbSessionFactory", DbSessionFactory.class));
		flag = new Flag();
		flag.setName("RHD prophylaxis not prescribed");
		flag.setCriteria("select p.patient_id from patient p");
		flag.setEvaluator("org.openmrs.module.patientflags.evaluator.SQLFlagEvaluator");
		flag.setMessage("not prescribed");
		flag.setEnabled(Boolean.TRUE);
		Context.getService(FlagService.class).saveFlag(flag);
		row(2, "first", false);
		row(7, "second", false);
		row(7, "again", false);
		row(8, "voided", true);
	}
	
	@Test
	public void getPatientFlagRows_shouldGiveTheRowsOfItsSql() {
		String sql = "select patient_id, message from patientflags_patient_flag where flag_id = " + flag.getFlagId()
		        + " and voided = false";
		List<List<Object>> every = Context.getAdministrationService().executeSQL(sql, true);
		assertEquals(3, every.size());
		assertEquals(every, dao.getPatientFlagRows(flag.getFlagId(), null));
		assertEquals(Context.getAdministrationService().executeSQL(sql + " and patient_id = 7", true),
		    dao.getPatientFlagRows(flag.getFlagId(), 7));
	}
	
	@Test
	public void deletePatientFlagRows_shouldDeleteThePatientsUnvoidedRowsOfTheFlagOnly() {
		row(8, "live", false);
		
		dao.deletePatientFlagRows(flag.getFlagId(), 7);
		dao.deletePatientFlagRows(flag.getFlagId(), 8);
		dao.deletePatientFlagRows(flag.getFlagId(), 7);
		
		assertEquals("2 first false, 8 voided true", rows());
	}
	
	@Test
	public void hasCohort_shouldTellWhetherItsSqlCountsACohort() {
		String uuid = UUID.randomUUID().toString();
		assertFalse(dao.hasCohort(uuid));
		Context.getAdministrationService()
		        .executeSQL("insert into cohort (name, description, definition_handler, creator, date_created, voided, uuid)"
		                + " values ('Voided list', 'A list', 'none', 1, now(), true, '" + uuid + "')",
		            false);
		assertTrue(dao.hasCohort(uuid));
		assertFalse(dao.hasCohort("o'brien"));
	}
	
	private String rows() {
		StringBuilder rows = new StringBuilder();
		for (List<Object> row : Context.getAdministrationService().executeSQL(
		    "select patient_id, message, voided from patientflags_patient_flag where flag_id = " + flag.getFlagId()
		            + " order by patient_id, message",
		    true)) {
			rows.append(rows.length() == 0 ? "" : ", ").append(row.get(0)).append(' ').append(row.get(1)).append(' ')
			        .append(row.get(2));
		}
		return rows.toString();
	}
	
	private void row(int patientId, String message, boolean voided) {
		Context.getAdministrationService().executeSQL(
		    "insert into patientflags_patient_flag (patient_id, flag_id, message, voided, creator, date_created, uuid)"
		            + " values (" + patientId + ", " + flag.getFlagId() + ", '" + message + "', " + voided + ", 1, now(), '"
		            + UUID.randomUUID() + "')",
		    false);
	}
}
