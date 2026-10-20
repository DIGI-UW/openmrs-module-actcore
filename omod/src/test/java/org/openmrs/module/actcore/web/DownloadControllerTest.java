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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.Before;
import org.junit.Test;
import org.openmrs.Concept;
import org.openmrs.ConceptName;
import org.openmrs.Encounter;
import org.openmrs.Form;
import org.openmrs.Patient;
import org.openmrs.Person;
import org.openmrs.PersonAttribute;
import org.openmrs.PersonAttributeType;
import org.openmrs.PersonName;
import org.openmrs.Privilege;
import org.openmrs.Role;
import org.openmrs.User;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.UserService;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.Transactions;
import org.openmrs.module.actcore.download.DownloadLog;
import org.openmrs.module.actcore.download.FormDownload;
import org.openmrs.module.actcore.studies.StudyEnrolmentRules;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.response.IllegalRequestException;
import org.openmrs.util.PrivilegeConstants;
import org.openmrs.web.test.BaseModuleWebContextSensitiveTest;
import org.springframework.beans.factory.annotation.Autowired;

public class DownloadControllerTest extends BaseModuleWebContextSensitiveTest {
	
	/** What the distribution's roles give a user who reads patients' forms. */
	private static final String[] READS = { PrivilegeConstants.GET_FORMS, PrivilegeConstants.GET_CONCEPTS,
	        PrivilegeConstants.GET_ENCOUNTERS, PrivilegeConstants.GET_OBS, PrivilegeConstants.GET_PATIENTS };
	
	@Autowired
	private DownloadController controller;
	
	private Form form;
	
	private Concept question;
	
	/**
	 * The module's liquibase changeset is not run by the test context, so the table is made here. The
	 * log writes in a transaction of its own, which outlives the test, so the rows are cleared first.
	 */
	private static void createTable() {
		Context.getAdministrationService()
		        .executeSQL("create table if not exists " + DownloadLog.TABLE
		                + " (download_id int auto_increment primary key, user_id int not null, form_id int, detail clob,"
		                + " date_created datetime not null)",
		            false);
		Context.getAdministrationService().executeSQL("delete from " + DownloadLog.TABLE, false);
	}
	
	@Before
	public void setUp() {
		// Outside the test's transaction, which would otherwise hold a lock that a drop waits on.
		Transactions.inNewTransaction(DownloadControllerTest::createTable);
		form = new Form();
		form.setName("RHD Echocardiogram");
		form.setVersion("1");
		form.setEncounterType(Context.getEncounterService().getEncounterType(1));
		Context.getFormService().saveForm(form);
		question = new Concept();
		question.setFullySpecifiedName(new ConceptName("Mitral regurgitation", Context.getLocale()));
		question.setConceptClass(Context.getConceptService().getConceptClass(1));
		question.setDatatype(Context.getConceptService().getConceptDatatypeByName("Text"));
		Context.getConceptService().saveConcept(question);
	}
	
	@Test
	public void refusesAUserWithoutADownloadPrivilege() {
		authenticateWith(READS);
		try {
			controller.download(body());
			fail("expected the download to be refused");
		}
		catch (APIAuthenticationException e) {
			assertTrue(e.getMessage().contains(DownloadController.DOWNLOAD_PRIVILEGE));
		}
		catch (Exception e) {
			fail(e.toString());
		}
	}
	
	@Test
	public void leavesOutTheIdentifiersWithoutTheIdentifiersPrivilege() throws Exception {
		authenticateWith(with(DownloadController.DOWNLOAD_PRIVILEGE));
		
		SimpleObject download = controller.download(body());
		
		assertEquals(Boolean.FALSE, download.get("identifiers"));
		assertEquals(Boolean.FALSE, download.get("researchOnly"));
		assertFalse(((List<?>) download.get("headers")).contains("Given name"));
		assertEquals("Mitral regurgitation", last((List<?>) download.get("headers")));
	}
	
	@Test
	public void keepsTheIdentifiersWithBothPrivileges() throws Exception {
		authenticateWith(with(DownloadController.DOWNLOAD_PRIVILEGE, DownloadController.IDENTIFIERS_PRIVILEGE));
		
		SimpleObject download = controller.download(body());
		
		assertEquals(Boolean.TRUE, download.get("identifiers"));
		assertTrue(((List<?>) download.get("headers")).contains("Given name"));
	}
	
	@Test
	public void givesTheResearchPrivilegeAloneOnlyAResearchDownloadNeverIdentified() throws Exception {
		authenticateWith(with(DownloadController.RESEARCH_PRIVILEGE, DownloadController.IDENTIFIERS_PRIVILEGE));
		
		SimpleObject download = controller.download(body());
		
		assertEquals(Boolean.FALSE, download.get("identifiers"));
		assertEquals(Boolean.TRUE, download.get("researchOnly"));
	}
	
	@Test
	public void withholdsAnIdentifyingQuestionFromAResearchDownloadAndSaysWhich() throws Exception {
		Context.getAdministrationService().setGlobalProperty(FormDownload.CONCEPTS_PROPERTY, question.getUuid());
		authenticateWith(with(DownloadController.RESEARCH_PRIVILEGE, DownloadController.IDENTIFIERS_PRIVILEGE));
		
		SimpleObject download = controller.download(body());
		
		assertFalse(((List<?>) download.get("headers")).contains("Mitral regurgitation"));
		assertEquals(Collections.singletonList("Mitral regurgitation"), download.get("withheld"));
	}
	
	@Test
	public void givesTheResearchPrivilegeAloneOnlyThePatientsWhoOptedIn() throws Exception {
		PersonAttributeType optIn = new PersonAttributeType();
		optIn.setName("Research Opt-In");
		optIn.setFormat("org.openmrs.Concept");
		Context.getPersonService().savePersonAttributeType(optIn);
		Concept yes = Context.getConceptService().getConceptByName("Yes");
		Context.getAdministrationService().setGlobalProperty(StudyEnrolmentRules.OPT_IN_PROPERTY, optIn.getUuid());
		Context.getAdministrationService().setGlobalProperty(StudyEnrolmentRules.YES_PROPERTY, yes.getUuid());
		encounter(7);
		Patient optedIn = encounter(2).getPatient();
		optedIn.addAttribute(new PersonAttribute(optIn, yes.getConceptId().toString()));
		Context.getPatientService().savePatient(optedIn);
		authenticateWith(with(DownloadController.RESEARCH_PRIVILEGE));
		
		List<?> rows = (List<?>) controller.download(body()).get("rows");
		
		assertEquals(1, rows.size());
		assertEquals("101-6", ((List<?>) rows.get(0)).get(0));
	}
	
	@Test
	public void failsTheDownloadWhenItCannotBeRecorded() throws Exception {
		// In a transaction of its own, so the DDL does not commit what setUp saved.
		Transactions.inNewTransaction(
		    () -> Context.getAdministrationService().executeSQL("drop table " + DownloadLog.TABLE, false));
		authenticateWith(with(DownloadController.RESEARCH_PRIVILEGE));
		try {
			controller.download(body());
			fail("expected the download to fail without its record");
		}
		catch (RuntimeException expected) {
			assertTrue(expected.toString(), causes(expected).toUpperCase().contains(DownloadLog.TABLE.toUpperCase()));
		}
	}
	
	@Test
	public void recordsEachDownloadForWhoeverMayReadTheReadAuditLog() throws Exception {
		Context.getAdministrationService().setGlobalProperty(FormDownload.CONCEPTS_PROPERTY, question.getUuid());
		authenticateWith(with(DownloadController.RESEARCH_PRIVILEGE));
		controller.download(body());
		String downloader = Context.getAuthenticatedUser().getUuid();
		signInAs("reader", DownloadLog.VIEW_PRIVILEGE);
		
		List<?> results = (List<?>) controller.getDownloads(null).get("results");
		
		assertEquals(1, results.size());
		SimpleObject entry = (SimpleObject) results.get(0);
		assertEquals(downloader, ((SimpleObject) entry.get("user")).get("uuid"));
		assertEquals("RHD Echocardiogram", ((SimpleObject) entry.get("form")).get("name"));
		Map<String, Object> detail = new LinkedHashMap<String, Object>();
		detail.put("fields", Collections.emptyList());
		detail.put("withheld", Collections.singletonList("Mitral regurgitation"));
		detail.put("startDate", "2026-01-01");
		detail.put("endDate", "2026-06-30");
		detail.put("rows", 0);
		detail.put("identifiers", false);
		detail.put("researchOnly", true);
		assertEquals(detail, entry.get("detail"));
	}
	
	@Test
	public void recordsTheFieldsADownloadGave() throws Exception {
		authenticateWith(with(DownloadController.DOWNLOAD_PRIVILEGE));
		controller.download(body());
		
		String detail = new DownloadLog().latest(10).get(0).detail;
		
		assertTrue(detail, detail.startsWith("{\"fields\":[\"Mitral regurgitation\"],\"withheld\":[]"));
	}
	
	@Test
	public void refusesTheDownloadsToAUserWhoMayNotReadTheReadAuditLog() throws Exception {
		authenticateWith(with(DownloadController.DOWNLOAD_PRIVILEGE, DownloadController.IDENTIFIERS_PRIVILEGE));
		try {
			controller.getDownloads(null);
			fail("expected the downloads to be refused");
		}
		catch (APIAuthenticationException e) {
			assertTrue(e.getMessage().contains(DownloadLog.VIEW_PRIVILEGE));
		}
	}
	
	@Test
	public void refusesAnUnknownConceptOrAStartAfterTheEnd() throws Exception {
		authenticateWith(with(DownloadController.DOWNLOAD_PRIVILEGE));
		Map<String, Object> unknown = body();
		unknown.put("fields", Collections.singletonList(Collections.singletonMap("concept", "no-such-concept")));
		Map<String, Object> backwards = body();
		backwards.put("startDate", "2026-07-01");
		
		for (Map<String, Object> body : Arrays.asList(unknown, backwards)) {
			try {
				controller.download(body);
				fail("expected " + body + " to be refused");
			}
			catch (IllegalRequestException expected) {}
		}
		assertTrue(new DownloadLog().latest(10).isEmpty());
	}
	
	private Map<String, Object> body() {
		Map<String, Object> field = new HashMap<String, Object>();
		field.put("concept", question.getUuid());
		field.put("label", "Mitral regurgitation");
		Map<String, Object> body = new HashMap<String, Object>();
		body.put("form", form.getUuid());
		body.put("fields", Collections.singletonList(field));
		body.put("startDate", "2026-01-01");
		body.put("endDate", "2026-06-30");
		return body;
	}
	
	private Encounter encounter(int patientId) {
		Encounter encounter = new Encounter();
		encounter.setPatient(Context.getPatientService().getPatient(patientId));
		encounter.setEncounterType(form.getEncounterType());
		encounter.setForm(form);
		encounter.setLocation(Context.getLocationService().getLocation(1));
		encounter.setEncounterDatetime(Timestamp.valueOf(LocalDate.of(2026, 3, 14).atTime(10, 0)));
		return Context.getEncounterService().saveEncounter(encounter);
	}
	
	private static String causes(Throwable e) {
		StringBuilder messages = new StringBuilder();
		for (Throwable cause = e; cause != null; cause = cause.getCause()) {
			messages.append(cause).append('\n');
		}
		return messages.toString();
	}
	
	private static Object last(List<?> list) {
		return list.get(list.size() - 1);
	}
	
	private static String[] with(String... privileges) {
		String[] all = Arrays.copyOf(READS, READS.length + privileges.length);
		System.arraycopy(privileges, 0, all, READS.length, privileges.length);
		return all;
	}
	
	private void authenticateWith(String... privileges) {
		signInAs("downloadcaller", privileges);
	}
	
	private void signInAs(String username, String... privileges) {
		UserService users = Context.getUserService();
		Context.authenticate("admin", "test");
		Role role = new Role("download caller " + UUID.randomUUID());
		for (String name : privileges) {
			Privilege privilege = users.getPrivilege(name);
			role.addPrivilege(privilege != null ? privilege : users.savePrivilege(new Privilege(name)));
		}
		users.saveRole(role);
		Person person = new Person();
		person.addName(new PersonName("Download", null, "Caller"));
		person.setGender("F");
		User user = new User(person);
		user.setUsername(username);
		user.addRole(role);
		users.createUser(user, "Download123");
		Context.logout();
		Context.authenticate(username, "Download123");
	}
}
