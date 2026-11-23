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
import org.openmrs.Patient;
import org.openmrs.PatientProgram;
import org.openmrs.Person;
import org.openmrs.PersonName;
import org.openmrs.Program;
import org.openmrs.User;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.ActCorePrivileges;
import org.openmrs.module.actcore.ModuleTables;
import org.openmrs.module.actcore.download.DownloadLog;
import org.openmrs.module.patientflags.Flag;
import org.openmrs.module.patientflags.PatientFlagsConstants;
import org.openmrs.test.BaseModuleContextSensitiveTest;
import org.openmrs.util.PrivilegeConstants;

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
	
	@Test
	public void saveMonth_shouldRefuseAUserWhoMayOnlyReadTheCounts() {
		ScreeningCountService service = Context.getService(ScreeningCountService.class);
		for (String read : new String[] { ActCorePrivileges.SCREENING_COUNTS, ActCorePrivileges.DASHBOARD }) {
			holding(read,
			    () -> refused(() -> service.saveMonth(clinic, MONTH, Collections.<String, Integer> emptyMap(), null),
			        ActCorePrivileges.ENTER_SCREENING_COUNTS));
		}
	}
	
	@Test
	public void getDownloads_shouldRefuseAUserWhoMayOnlyDownload() {
		DownloadLogService service = Context.getService(DownloadLogService.class);
		for (String download : new String[] { ActCorePrivileges.DOWNLOAD_DATA, ActCorePrivileges.DOWNLOAD_RESEARCH_DATA }) {
			holding(download, () -> refused(() -> service.getDownloads(null, null, null), DownloadLog.VIEW_PRIVILEGE));
		}
	}
	
	@Test
	public void raisedFlagService_shouldRefuseAUserWithoutTheFlagPrivileges() {
		RaisedFlagService service = Context.getService(RaisedFlagService.class);
		assertNotNull(service);
		refused(() -> service.raise(1, 2), PatientFlagsConstants.PRIV_TEST_FLAGS);
		refused(() -> service.lower(1, 2), PatientFlagsConstants.PRIV_TEST_FLAGS);
		refused(() -> service.lowerAll(1), PatientFlagsConstants.PRIV_TEST_FLAGS);
		refused(() -> service.getRaisedPatients(1), PatientFlagsConstants.PRIV_VIEW_PATIENT_FLAGS);
		refused(() -> service.getDismissedPatients(1), PatientFlagsConstants.PRIV_VIEW_PATIENT_FLAGS);
		refused(() -> service.isDismissed(1, 2), PatientFlagsConstants.PRIV_VIEW_PATIENT_FLAGS);
		refused(() -> service.getRaisedSinceByPatient(1), PatientFlagsConstants.PRIV_VIEW_PATIENT_FLAGS);
		refused(() -> service.getRaisedSinceByFlag(2), PatientFlagsConstants.PRIV_VIEW_PATIENT_FLAGS);
		refused(() -> service.mayDismiss(new Flag()), PatientFlagsConstants.PRIV_VIEW_PATIENT_FLAGS);
		refused(() -> service.dismiss(new Patient(2), new Flag()), ActCorePrivileges.DISMISS_FLAGS,
		    PatientFlagsConstants.PRIV_VIEW_PATIENT_FLAGS);
	}
	
	@Test
	public void raisedFlagService_shouldRefuseAUserWhoMayOnlyViewFlagsAnythingButTheReads() {
		RaisedFlagService service = Context.getService(RaisedFlagService.class);
		holding(PatientFlagsConstants.PRIV_VIEW_PATIENT_FLAGS, () -> {
			refused(() -> service.raise(1, 2), PatientFlagsConstants.PRIV_TEST_FLAGS);
			refused(() -> service.lower(1, 2), PatientFlagsConstants.PRIV_TEST_FLAGS);
			refused(() -> service.lowerAll(1), PatientFlagsConstants.PRIV_TEST_FLAGS);
			refused(() -> service.dismiss(new Patient(2), new Flag()), ActCorePrivileges.DISMISS_FLAGS);
		});
		holding(PatientFlagsConstants.PRIV_TEST_FLAGS,
		    () -> refused(() -> service.mayDismiss(new Flag()), PatientFlagsConstants.PRIV_VIEW_PATIENT_FLAGS));
		holding(ActCorePrivileges.DISMISS_FLAGS,
		    () -> refused(() -> service.dismiss(new Patient(2), new Flag()), PatientFlagsConstants.PRIV_VIEW_PATIENT_FLAGS));
	}
	
	@Test
	public void studyService_shouldRefuseAUserWithoutTheStudyPrivileges() {
		StudyService service = Context.getService(StudyService.class);
		assertNotNull(service);
		refused(() -> service.getStudies(), PrivilegeConstants.GET_PROGRAMS);
		refused(() -> service.getStudyByUuid("uuid"), PrivilegeConstants.GET_PROGRAMS, ActCorePrivileges.MANAGE_STUDIES);
		refused(() -> service.createStudy("ARC"), ActCorePrivileges.MANAGE_STUDIES);
		refused(() -> service.renameStudy(new Program(1), "ARC"), ActCorePrivileges.MANAGE_STUDIES);
		refused(() -> service.getSavedProgram(new PatientProgram(1)), PrivilegeConstants.EDIT_PATIENT_PROGRAMS);
		refused(() -> service.isSavedOpenWithoutAttribute(new PatientProgram(1), "uuid"),
		    PrivilegeConstants.EDIT_PATIENT_PROGRAMS);
	}
	
	@Test
	public void studyService_shouldRefuseAUserWhoMayOnlyReadProgramsTheWrites() {
		StudyService service = Context.getService(StudyService.class);
		holding(PrivilegeConstants.GET_PROGRAMS, () -> {
			refused(() -> service.createStudy("ARC"), ActCorePrivileges.MANAGE_STUDIES);
			refused(() -> service.renameStudy(new Program(1), "ARC"), ActCorePrivileges.MANAGE_STUDIES);
		});
		holding(PrivilegeConstants.GET_PATIENT_PROGRAMS,
		    () -> refused(() -> service.getSavedProgram(new PatientProgram(1)), PrivilegeConstants.ADD_PATIENT_PROGRAMS));
	}
	
	@Test
	public void getDismissedFlagsAndIsStudy_shouldRefuseACallerWhoIsNotSignedIn() {
		Context.logout();
		refusedSignedOut(() -> Context.getService(RaisedFlagService.class).getDismissedFlags(2));
		refusedSignedOut(() -> Context.getService(StudyService.class).isStudy(new Program(1)));
	}
	
	private static void holding(String privilege, Runnable call) {
		Context.addProxyPrivilege(privilege);
		try {
			call.run();
		}
		finally {
			Context.removeProxyPrivilege(privilege);
		}
	}
	
	// Core names the first privilege missing of those a method needs all of, in no set order.
	private static void refused(Runnable call, String... privileges) {
		try {
			call.run();
			fail("Expected a refusal naming " + String.join(" or ", privileges));
		}
		catch (APIAuthenticationException e) {
			boolean named = false;
			for (String privilege : privileges) {
				named |= e.getMessage().contains(privilege);
			}
			assertTrue(e.getMessage(), named);
		}
	}
	
	private static void refusedSignedOut(Runnable call) {
		try {
			call.run();
		}
		catch (APIAuthenticationException e) {
			return;
		}
		fail("Expected a refusal for a caller who is not signed in");
	}
}
