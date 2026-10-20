/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openmrs.GlobalProperty;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.task.HideDismissedFlags;
import org.openmrs.module.actcore.task.RaisedFlags;
import org.openmrs.module.patientflags.Flag;
import org.openmrs.module.patientflags.PatientFlag;
import org.openmrs.module.patientflags.Tag;
import org.openmrs.module.patientflags.api.FlagService;
import org.openmrs.module.patientflags.evaluator.SQLFlagEvaluator;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.response.IllegalRequestException;
import org.openmrs.module.webservices.rest.web.response.ObjectNotFoundException;
import org.openmrs.web.test.BaseModuleWebContextSensitiveTest;
import org.springframework.beans.factory.annotation.Autowired;

public class RaisedFlagsControllerTest extends BaseModuleWebContextSensitiveTest {
	
	private static final String PATIENT_7 = "5946f880-b197-400b-9caa-a3c661d23041";
	
	private static final String MATCHES_7 = "select p.patient_id from patient p where p.patient_id = 7";
	
	@Autowired
	private RaisedFlagsController controller;
	
	private final HideDismissedFlags hide = new HideDismissedFlags();
	
	private Tag critical;
	
	@Before
	public void setUp() {
		// The module's liquibase changeset is not run by the test context; H2 commits DDL at once.
		Context.getAdministrationService().executeSQL("create table if not exists " + RaisedFlags.TABLE
		        + " (patient_id int not null, flag_id int not null, raised_since datetime not null, dismissed_by int,"
		        + " date_dismissed datetime, primary key (patient_id, flag_id))",
		    false);
		Context.getAdministrationService().executeSQL("delete from " + RaisedFlags.TABLE, false);
		Context.getAdministrationService()
		        .saveGlobalProperty(new GlobalProperty(RaisedFlags.DISMISS_TAG_PROPERTY, "Critical data"));
		critical = new Tag("Critical data");
		Context.getService(FlagService.class).saveTag(critical);
		Context.addAdvice(FlagService.class, hide);
	}
	
	@After
	public void removeAdvice() {
		Context.removeAdvice(FlagService.class, hide);
	}
	
	@Test
	public void listsEachRaisedFlagWithWhenItWasRaisedAndWhetherTheUserMayDismissIt() {
		Flag missing = raised(saveFlag("missing data", MATCHES_7, critical), "2026-01-05 10:00:00");
		Flag risk = raised(saveFlag("overdue", MATCHES_7, null), null);
		
		List<Map<String, Object>> results = controller.getRaisedFlags(PATIENT_7).get("results");
		
		assertEquals(2, results.size());
		Map<String, Object> first = byFlag(results, missing);
		assertEquals("missing data", first.get("display"));
		assertTrue(first.get("raisedSince").toString().startsWith("2026-01-05T10:00:00.000"));
		assertEquals(Boolean.TRUE, first.get("dismissible"));
		Map<String, Object> second = byFlag(results, risk);
		assertNull(second.get("raisedSince"));
		assertEquals(Boolean.FALSE, second.get("dismissible"));
	}
	
	@Test
	public void leavesADismissedFlagOutOnceItIsDismissed() {
		Flag missing = raised(saveFlag("missing data", MATCHES_7, critical), "2026-01-05 10:00:00");
		
		SimpleObject response = controller.dismiss(body(PATIENT_7, missing.getUuid()));
		
		assertEquals(Boolean.TRUE, response.get("dismissed"));
		assertTrue(((List<?>) controller.getRaisedFlags(PATIENT_7).get("results")).isEmpty());
	}
	
	@Test(expected = IllegalRequestException.class)
	public void refusesToDismissAFlagWithoutTheDismissTag() {
		Flag risk = raised(saveFlag("overdue", MATCHES_7, null), null);
		
		controller.dismiss(body(PATIENT_7, risk.getUuid()));
	}
	
	@Test(expected = IllegalRequestException.class)
	public void refusesToDismissAFlagThePatientDoesNotMatch() {
		Flag missing = saveFlag("missing data", "select p.patient_id from patient p where p.patient_id = -1", critical);
		
		controller.dismiss(body(PATIENT_7, missing.getUuid()));
	}
	
	@Test(expected = IllegalRequestException.class)
	public void requiresTheFlagToDismiss() {
		controller.dismiss(body(PATIENT_7, " "));
	}
	
	@Test(expected = IllegalRequestException.class)
	public void requiresThePatient() {
		controller.getRaisedFlags(null);
	}
	
	@Test(expected = ObjectNotFoundException.class)
	public void answersNotFoundForAnUnknownFlag() {
		controller.dismiss(body(PATIENT_7, UUID.randomUUID().toString()));
	}
	
	@Test(expected = APIAuthenticationException.class)
	public void refusesAUserWhoMayNotSeeFlags() {
		Context.logout();
		
		controller.getRaisedFlags(PATIENT_7);
	}
	
	private Flag saveFlag(String name, String criteria, Tag tag) {
		Flag flag = new Flag();
		flag.setUuid(UUID.randomUUID().toString());
		flag.setName(name);
		flag.setCriteria(criteria);
		flag.setEvaluator(SQLFlagEvaluator.class.getName());
		flag.setMessage(name);
		flag.setEnabled(Boolean.TRUE);
		if (tag != null) {
			flag.setTags(new HashSet<Tag>(Collections.singleton(tag)));
		}
		Context.getService(FlagService.class).saveFlag(flag);
		return flag;
	}
	
	/** A row as patientflags writes it, and, given a date, ACT Core's record of when it was raised. */
	private Flag raised(Flag flag, String since) {
		Context.getService(FlagService.class)
		        .savePatientFlag(new PatientFlag(Context.getPatientService().getPatient(7), flag, flag.getMessage()));
		Context.flushSession();
		if (since != null) {
			Context.getAdministrationService().executeSQL("insert into " + RaisedFlags.TABLE
			        + " (patient_id, flag_id, raised_since) values (7, " + flag.getFlagId() + ", '" + since + "')",
			    false);
		}
		return flag;
	}
	
	private static Map<String, Object> byFlag(List<Map<String, Object>> results, Flag flag) {
		for (Map<String, Object> result : results) {
			if (flag.getUuid().equals(result.get("flag"))) {
				return result;
			}
		}
		throw new AssertionError("no result for " + flag.getName());
	}
	
	private static Map<String, Object> body(String patient, String flag) {
		Map<String, Object> body = new HashMap<String, Object>();
		body.put("patient", patient);
		body.put("flag", flag);
		return body;
	}
}
