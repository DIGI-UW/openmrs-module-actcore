/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.api;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.time.YearMonth;
import java.util.Collections;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openmrs.Location;
import org.openmrs.Person;
import org.openmrs.PersonName;
import org.openmrs.User;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.ActCorePrivileges;
import org.openmrs.module.actcore.ModuleTables;
import org.openmrs.module.actcore.download.DownloadLog;
import org.openmrs.test.BaseModuleContextSensitiveTest;

public class ActCoreServicesContextTest extends BaseModuleContextSensitiveTest {
	
	private static final YearMonth MONTH = YearMonth.of(2026, 9);
	
	private Location clinic;
	
	@Before
	public void signInWithoutPrivileges() {
		ModuleTables.create();
		clinic = Context.getLocationService().getLocation(1);
		Person person = new Person();
		person.addName(new PersonName("Nobody", null, "Tester"));
		person.setGender("F");
		User user = new User(person);
		user.setUsername("noprivileges");
		Context.getUserService().createUser(user, "Password123");
		Context.flushSession();
		Context.becomeUser("noprivileges");
	}
	
	@After
	public void signBackIn() {
		Context.logout();
		authenticate();
	}
	
	@Test
	public void screeningCountService_shouldRefuseAUserWithoutTheScreeningPrivileges() {
		ScreeningCountService service = Context.getService(ScreeningCountService.class);
		assertNotNull(service);
		refused(() -> service.getMonth(clinic, MONTH), ActCorePrivileges.SCREENING_COUNTS);
		refused(() -> service.saveMonth(clinic, MONTH, Collections.<String, Integer> emptyMap(), null),
		    ActCorePrivileges.ENTER_SCREENING_COUNTS);
		refused(() -> service.getSummary(null, MONTH, MONTH), ActCorePrivileges.DASHBOARD);
	}
	
	@Test
	public void downloadLogService_shouldRefuseAUserWithoutTheDownloadPrivileges() {
		DownloadLogService service = Context.getService(DownloadLogService.class);
		assertNotNull(service);
		refused(() -> service.recordDownload(null, "{}"), ActCorePrivileges.DOWNLOAD_DATA);
		refused(() -> service.getDownloads(null, null, null), DownloadLog.VIEW_PRIVILEGE);
	}
	
	private static void refused(Runnable call, String privilege) {
		try {
			call.run();
			fail("Expected a refusal naming " + privilege);
		}
		catch (APIAuthenticationException e) {
			assertTrue(e.getMessage(), e.getMessage().contains(privilege));
		}
	}
}
