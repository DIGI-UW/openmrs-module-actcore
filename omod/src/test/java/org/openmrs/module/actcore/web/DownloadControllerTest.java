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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.apache.commons.lang3.StringUtils;
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
import org.openmrs.api.db.hibernate.DbSessionFactory;
import org.openmrs.messagesource.MessageSourceService;
import org.openmrs.messagesource.MutableMessageSource;
import org.openmrs.messagesource.PresentationMessage;
import org.openmrs.messagesource.impl.CachedMessageSource;
import org.openmrs.module.actcore.ActCoreConstants;
import org.openmrs.module.actcore.ActCorePrivileges;
import org.openmrs.module.actcore.ModuleTables;
import org.openmrs.module.actcore.api.DownloadLogService;
import org.openmrs.module.actcore.api.db.hibernate.HibernateDownloadLogDAO;
import org.openmrs.module.actcore.api.search.DownloadSearchCriteria;
import org.openmrs.module.actcore.model.Download;
import org.openmrs.module.actcore.model.DownloadLogEntry;
import org.openmrs.module.actcore.model.DownloadPage;
import org.openmrs.module.actcore.utils.Transactions;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.response.IllegalRequestException;
import org.openmrs.util.PrivilegeConstants;
import org.openmrs.web.test.BaseModuleWebContextSensitiveTest;
import org.springframework.beans.factory.annotation.Autowired;

public class DownloadControllerTest extends BaseModuleWebContextSensitiveTest {
	
	/** What the distribution's roles give a user who reads patients' forms. */
	private static final String[] READS = { PrivilegeConstants.GET_FORMS, PrivilegeConstants.GET_CONCEPTS,
	        PrivilegeConstants.GET_ENCOUNTERS, PrivilegeConstants.GET_OBS, PrivilegeConstants.GET_PATIENTS };
	
	private static final String ASIDE = "actcore_download_log_aside";
	
	@Autowired
	private DownloadController controller;
	
	private Form form;
	
	private Concept question;
	
	@Before
	public void setUp() {
		ModuleTables.create();
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
			assertTrue(e.getMessage().contains(ActCorePrivileges.DOWNLOAD_DATA));
		}
		catch (Exception e) {
			fail(e.toString());
		}
	}
	
	@Test
	public void leavesOutTheIdentifiersWithoutTheIdentifiersPrivilege() throws Exception {
		authenticateWith(with(ActCorePrivileges.DOWNLOAD_DATA));
		
		SimpleObject download = controller.download(body());
		
		assertEquals(Boolean.FALSE, download.get("identifiers"));
		assertEquals(Boolean.FALSE, download.get("researchOnly"));
		assertFalse(((List<?>) download.get("headers")).contains("Given name"));
		assertEquals("Mitral regurgitation", last((List<?>) download.get("headers")));
	}
	
	@Test
	public void keepsTheIdentifiersWithBothPrivileges() throws Exception {
		authenticateWith(with(ActCorePrivileges.DOWNLOAD_DATA, ActCorePrivileges.DOWNLOAD_IDENTIFIERS));
		
		SimpleObject download = controller.download(body());
		
		assertEquals(Boolean.TRUE, download.get("identifiers"));
		assertTrue(((List<?>) download.get("headers")).contains("Given name"));
	}
	
	@Test
	public void givesTheResearchPrivilegeAloneOnlyAResearchDownloadNeverIdentified() throws Exception {
		authenticateWith(with(ActCorePrivileges.DOWNLOAD_RESEARCH_DATA, ActCorePrivileges.DOWNLOAD_IDENTIFIERS));
		
		SimpleObject download = controller.download(body());
		
		assertEquals(Boolean.FALSE, download.get("identifiers"));
		assertEquals(Boolean.TRUE, download.get("researchOnly"));
	}
	
	@Test
	public void withholdsAnIdentifyingQuestionFromAResearchDownloadAndSaysWhich() throws Exception {
		Context.getAdministrationService().setGlobalProperty(ActCoreConstants.GP_DOWNLOAD_IDENTIFYING_CONCEPTS,
		    question.getUuid());
		authenticateWith(with(ActCorePrivileges.DOWNLOAD_RESEARCH_DATA, ActCorePrivileges.DOWNLOAD_IDENTIFIERS));
		
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
		Context.getAdministrationService().setGlobalProperty(ActCoreConstants.GP_STUDIES_OPT_IN_ATTRIBUTE_TYPE,
		    optIn.getUuid());
		Context.getAdministrationService().setGlobalProperty(ActCoreConstants.GP_STUDIES_YES_CONCEPT, yes.getUuid());
		encounter(7);
		Patient optedIn = encounter(2).getPatient();
		optedIn.addAttribute(new PersonAttribute(optIn, yes.getConceptId().toString()));
		Context.getPatientService().savePatient(optedIn);
		authenticateWith(with(ActCorePrivileges.DOWNLOAD_RESEARCH_DATA));
		
		List<?> rows = (List<?>) controller.download(body()).get("rows");
		
		assertEquals(1, rows.size());
		assertEquals("101-6", ((List<?>) rows.get(0)).get(0));
	}
	
	@Test
	public void failsTheDownloadWhenItCannotBeRecorded() throws Exception {
		// Set aside, not dropped: the changelog runs once, so a dropped table would not come back.
		renameDownloadLog(ActCoreConstants.DOWNLOAD_LOG_TABLE, ASIDE);
		authenticateWith(with(ActCorePrivileges.DOWNLOAD_RESEARCH_DATA));
		try {
			controller.download(body());
			fail("expected the download to fail without its record");
		}
		catch (RuntimeException expected) {
			assertTrue(expected.toString(),
			    causes(expected).toUpperCase().contains(ActCoreConstants.DOWNLOAD_LOG_TABLE.toUpperCase()));
		}
		finally {
			Context.logout();
			Context.authenticate("admin", "test");
			renameDownloadLog(ASIDE, ActCoreConstants.DOWNLOAD_LOG_TABLE);
		}
	}
	
	/** In a transaction of its own, so the DDL does not commit what setUp saved. */
	private static void renameDownloadLog(String from, String to) {
		Transactions.inNewTransaction(
		    () -> Context.getAdministrationService().executeSQL("alter table " + from + " rename to " + to, false));
	}
	
	@Test
	public void recordsEachDownloadForWhoeverMayReadTheReadAuditLog() throws Exception {
		Context.getAdministrationService().setGlobalProperty(ActCoreConstants.GP_DOWNLOAD_IDENTIFYING_CONCEPTS,
		    question.getUuid());
		authenticateWith(with(ActCorePrivileges.DOWNLOAD_RESEARCH_DATA));
		controller.download(body());
		String downloader = Context.getAuthenticatedUser().getUuid();
		signInAs("reader", ActCoreConstants.AUDITLOGWEB_VIEW_READ_AUDIT_LOGS);
		
		List<?> results = (List<?>) controller.getDownloads(null, null, null, null, null, null, null).get("results");
		
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
		Map<String, Object> echo = new LinkedHashMap<String, Object>();
		echo.put("uuid", form.getUuid());
		echo.put("name", "RHD Echocardiogram");
		echo.put("fields", Collections.emptyList());
		detail.put("forms", Collections.singletonList(echo));
		detail.put("rowPerPatient", false);
		Map<String, Object> filters = new LinkedHashMap<String, Object>();
		filters.put("status", null);
		filters.put("cardiacClinic", null);
		filters.put("primaryCareClinic", null);
		detail.put("filters", filters);
		detail.put("patient", Arrays.asList("sex", "age"));
		assertEquals(detail, entry.get("detail"));
	}
	
	@Test
	public void namesADownloadedFormByTheNameUsersSee() throws Exception {
		MessageSourceService service = Context.getMessageSourceService();
		MutableMessageSource previous = service.getActiveMessageSource();
		CachedMessageSource shown = new CachedMessageSource();
		shown.addPresentation(
		    new PresentationMessage("ui.i18n.Form.name." + form.getUuid(), Context.getLocale(), "Echocardiogram", null));
		service.setActiveMessageSource(shown);
		try {
			Context.getAdministrationService().setGlobalProperty(ActCoreConstants.GP_DOWNLOAD_IDENTIFYING_CONCEPTS,
			    question.getUuid());
			authenticateWith(with(ActCorePrivileges.DOWNLOAD_RESEARCH_DATA));
			controller.download(body());
			signInAs("reader", ActCoreConstants.AUDITLOGWEB_VIEW_READ_AUDIT_LOGS);
			
			SimpleObject entry = (SimpleObject) ((List<?>) controller.getDownloads(null, null, null, null, null, null, null)
			        .get("results")).get(0);
			assertEquals("Echocardiogram", ((SimpleObject) entry.get("form")).get("name"));
		}
		finally {
			service.setActiveMessageSource(previous);
		}
	}
	
	@Test
	public void recordsTheFieldsADownloadGave() throws Exception {
		authenticateWith(with(ActCorePrivileges.DOWNLOAD_DATA));
		controller.download(body());
		
		String detail = logged().get(0).detail;
		
		assertTrue(detail, detail.startsWith("{\"fields\":[\"Mitral regurgitation\"],\"withheld\":[]"));
	}
	
	@Test
	public void refusesTheDownloadsToAUserWhoMayNotReadTheReadAuditLog() throws Exception {
		authenticateWith(with(ActCorePrivileges.DOWNLOAD_DATA, ActCorePrivileges.DOWNLOAD_IDENTIFIERS));
		try {
			controller.getDownloads(null, null, null, null, null, null, null);
			fail("expected the downloads to be refused");
		}
		catch (APIAuthenticationException e) {
			assertTrue(e.getMessage().contains(ActCoreConstants.AUDITLOGWEB_VIEW_READ_AUDIT_LOGS));
		}
	}
	
	@Test
	public void recordsAListTheAppDownloadedItselfForTheReadAuditLogReaders() throws Exception {
		authenticateWith(with(ActCorePrivileges.DOWNLOAD_DATA, ActCorePrivileges.DOWNLOAD_IDENTIFIERS));
		Map<String, Object> body = new HashMap<String, Object>();
		body.put("list", "registry");
		body.put("columns", Arrays.asList("ACT ID", "Name", "Primary Phone"));
		body.put("rows", 486);
		body.put("identifiers", true);
		body.put("filters", Collections.singletonMap("clinic", "Akunalaber HCIII"));
		
		assertEquals(Boolean.TRUE, controller.record(body).get("recorded"));
		
		signInAs("reader", ActCoreConstants.AUDITLOGWEB_VIEW_READ_AUDIT_LOGS);
		SimpleObject page = controller.getDownloads(null, null, null, null, null, null, null);
		assertEquals(Integer.valueOf(1), page.get("totalCount"));
		SimpleObject entry = (SimpleObject) ((List<?>) page.get("results")).get(0);
		assertNull(entry.get("form"));
		assertEquals("Registry list", ((SimpleObject) entry.get("list")).get("name"));
		assertEquals("registry", ((SimpleObject) entry.get("list")).get("kind"));
		Map<?, ?> detail = (Map<?, ?>) entry.get("detail");
		assertEquals(Arrays.asList("ACT ID", "Name", "Primary Phone"), detail.get("fields"));
		assertEquals(486, detail.get("rows"));
		assertEquals(Boolean.TRUE, detail.get("identifiers"));
		assertEquals(Boolean.FALSE, detail.get("researchOnly"));
		assertEquals(Collections.singletonMap("clinic", "Akunalaber HCIII"), detail.get("filters"));
		assertEquals("Due for prophylaxis worklist", DownloadController.list("worklist:Due for prophylaxis").get("name"));
		assertEquals("Logs: Activity", DownloadController.list("logs:activity").get("name"));
		assertEquals("Logs: Patients opened", DownloadController.list("logs:reads").get("name"));
		assertEquals("Logs: Sign-ins", DownloadController.list("logs:signIns").get("name"));
		assertEquals("Logs: Later", DownloadController.list("logs:later").get("name"));
	}
	
	@Test
	public void recordsAListAResearcherDownloadedAsNotResearchOnly() throws Exception {
		authenticateWith(with(ActCorePrivileges.DOWNLOAD_RESEARCH_DATA));
		Map<String, Object> body = new HashMap<String, Object>();
		body.put("list", "registry");
		body.put("columns", Collections.singletonList("ACT ID"));
		
		controller.record(body);
		
		String detail = logged().get(0).detail;
		assertTrue(detail, detail.contains("\"researchOnly\":false"));
	}
	
	@Test
	public void refusesToRecordFiltersLongerThanTheLogHolds() throws Exception {
		authenticateWith(with(ActCorePrivileges.DOWNLOAD_DATA));
		Map<String, Object> body = new HashMap<String, Object>();
		body.put("list", "registry");
		body.put("columns", Collections.singletonList("ACT ID"));
		body.put("filters", Collections.singletonMap("name", StringUtils.repeat("x", DownloadController.MAX_DETAIL_BYTES)));
		
		try {
			controller.record(body);
			fail("expected the record to be refused");
		}
		catch (IllegalRequestException expected) {}
		assertTrue(logged().isEmpty());
	}
	
	@Test
	public void refusesToRecordAListWithoutADownloadPrivilegeOrAList() throws Exception {
		authenticateWith(READS);
		Map<String, Object> body = new HashMap<String, Object>();
		body.put("list", "registry");
		body.put("columns", Collections.singletonList("ACT ID"));
		try {
			controller.record(body);
			fail("expected the record to be refused");
		}
		catch (APIAuthenticationException expected) {}
		signInAs("researcher", with(ActCorePrivileges.DOWNLOAD_RESEARCH_DATA));
		try {
			controller.record(Collections.<String, Object> singletonMap("columns", Collections.singletonList("ACT ID")));
			fail("expected a record without a list to be refused");
		}
		catch (IllegalRequestException expected) {}
		assertTrue(logged().isEmpty());
	}
	
	@Test
	public void filtersTheDownloadsByUserFormIdentifiersAndDates() throws Exception {
		authenticateWith(with(ActCorePrivileges.DOWNLOAD_DATA, ActCorePrivileges.DOWNLOAD_IDENTIFIERS));
		controller.download(body());
		Map<String, Object> list = new HashMap<String, Object>();
		list.put("list", "registry");
		list.put("columns", Collections.singletonList("ACT ID"));
		list.put("identifiers", true);
		controller.record(list);
		signInAs("researcher", with(ActCorePrivileges.DOWNLOAD_RESEARCH_DATA));
		controller.download(body());
		signInAs("reader", ActCoreConstants.AUDITLOGWEB_VIEW_READ_AUDIT_LOGS);
		
		assertEquals(Integer.valueOf(3),
		    controller.getDownloads(null, null, null, null, null, null, null).get("totalCount"));
		assertEquals(Integer.valueOf(2),
		    controller.getDownloads("downloadcaller", null, null, null, null, null, null).get("totalCount"));
		SimpleObject nobody = controller.getDownloads("nobody", null, null, null, null, null, null);
		assertEquals(Integer.valueOf(0), nobody.get("totalCount"));
		assertEquals(LocalDate.now().minusMonths(12).toString(), nobody.get("from"));
		SimpleObject noForm = controller.getDownloads(null, null, "2026-10-21", "no-such-form", null, null, null);
		assertEquals(Integer.valueOf(0), noForm.get("totalCount"));
		assertEquals("2025-10-21", noForm.get("from"));
		assertEquals("2026-10-21", noForm.get("to"));
		assertEquals(Integer.valueOf(2),
		    controller.getDownloads(null, null, null, form.getUuid(), null, null, null).get("totalCount"));
		assertEquals(Integer.valueOf(1),
		    controller.getDownloads(null, null, null, null, "research", null, null).get("totalCount"));
		assertEquals(Integer.valueOf(2),
		    controller.getDownloads(null, null, null, null, "with", null, null).get("totalCount"));
		assertEquals(Integer.valueOf(0),
		    controller.getDownloads(null, null, null, null, "without", null, null).get("totalCount"));
		String today = LocalDate.now().toString();
		assertEquals(Integer.valueOf(3),
		    controller.getDownloads(null, today, today, null, null, null, null).get("totalCount"));
		assertEquals(Integer.valueOf(0),
		    controller.getDownloads(null, null, LocalDate.now().minusDays(1).toString(), null, null, null, null)
		            .get("totalCount"));
		SimpleObject page = controller.getDownloads(null, null, null, null, null, 1, 1);
		assertEquals(Integer.valueOf(3), page.get("totalCount"));
		assertEquals(1, ((List<?>) page.get("results")).size());
		try {
			controller.getDownloads(null, null, null, null, "some", null, null);
			fail("expected the identifiers filter to be refused");
		}
		catch (IllegalRequestException expected) {}
	}
	
	@Test
	public void service_shouldFilterTheDownloadsByUserFormAndIdentifiersAndPageThem() throws Exception {
		authenticateWith(with(ActCorePrivileges.DOWNLOAD_DATA, ActCorePrivileges.DOWNLOAD_IDENTIFIERS));
		controller.download(body());
		Map<String, Object> list = new HashMap<String, Object>();
		list.put("list", "registry");
		list.put("columns", Collections.singletonList("ACT ID"));
		controller.record(list);
		signInAs("researcher", with(ActCorePrivileges.DOWNLOAD_RESEARCH_DATA));
		controller.download(body());
		signInAs("reader", ActCoreConstants.AUDITLOGWEB_VIEW_READ_AUDIT_LOGS);
		DownloadLogService service = Context.getService(DownloadLogService.class);
		
		DownloadPage every = service.getDownloads(new DownloadSearchCriteria());
		assertEquals(3, every.totalCount);
		assertEquals("researcher", every.results.get(0).user.username);
		assertEquals(Boolean.TRUE, every.results.get(0).detail.get("researchOnly"));
		assertEquals("registry", every.results.get(1).detail.get("list"));
		assertEquals(2, service.getDownloads(filter(f -> f.username = "downloadcaller")).totalCount);
		assertEquals(0, service.getDownloads(filter(f -> f.username = "nobody")).totalCount);
		assertEquals(2, service.getDownloads(filter(f -> f.formUuid = form.getUuid())).totalCount);
		assertEquals(0, service.getDownloads(filter(f -> f.formUuid = "no-such-form")).totalCount);
		assertEquals(1, service.getDownloads(filter(f -> f.identifiers = "research")).totalCount);
		assertEquals(1, service.getDownloads(filter(f -> f.identifiers = "without")).totalCount);
		DownloadPage page = service.getDownloads(filter(f -> {
			f.startIndex = 1;
			f.limit = 1;
		}));
		assertEquals(3, page.totalCount);
		assertEquals(1, page.results.size());
		assertEquals("registry", page.results.get(0).detail.get("list"));
	}
	
	private static DownloadSearchCriteria filter(Consumer<DownloadSearchCriteria> settings) {
		DownloadSearchCriteria filter = new DownloadSearchCriteria();
		settings.accept(filter);
		return filter;
	}
	
	@Test
	public void namesAUserWithNoUsernameByTheirSystemIdAndName() throws Exception {
		authenticateWith(with(ActCorePrivileges.DOWNLOAD_DATA));
		Map<String, Object> list = new HashMap<String, Object>();
		list.put("list", "dashboard:pdf");
		list.put("columns", Collections.singletonList("Registrant status"));
		controller.record(list);
		// As OpenMRS's own super user signs in: by system ID, with no username.
		Context.logout();
		Context.authenticate("admin", "test");
		Context.getAdministrationService().executeSQL(
		    "update users set username = '', system_id = 'caller-system' where username = 'downloadcaller'", false);
		signInAs("reader", ActCoreConstants.AUDITLOGWEB_VIEW_READ_AUDIT_LOGS);
		
		SimpleObject user = (SimpleObject) ((SimpleObject) ((List<?>) controller
		        .getDownloads(null, null, null, null, null, null, null).get("results")).get(0)).get("user");
		assertEquals("caller-system", user.get("username"));
		assertEquals("Download Caller", user.get("display"));
		assertEquals(Integer.valueOf(1),
		    controller.getDownloads("caller-system", null, null, null, null, null, null).get("totalCount"));
	}
	
	@Test
	public void refusesAnUnknownConceptOrAStartAfterTheEnd() throws Exception {
		authenticateWith(with(ActCorePrivileges.DOWNLOAD_DATA));
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
		assertTrue(logged().isEmpty());
	}
	
	@Test
	public void downloadsSeveralFormsAsARowPerPatientAndListsItUnderEach() throws Exception {
		Form consult = secondForm();
		encounter(7);
		Encounter visit = encounter(7);
		visit.setForm(consult);
		Context.getEncounterService().saveEncounter(visit);
		Form other = secondForm();
		authenticateWith(with(ActCorePrivileges.DOWNLOAD_DATA));
		Map<String, Object> body = body();
		body.remove("form");
		body.remove("fields");
		body.put("forms", Arrays.asList(choice(form), choice(consult)));
		
		SimpleObject result = controller.download(body);
		
		List<?> headers = (List<?>) result.get("headers");
		assertTrue(headers.toString(), headers.contains("RHD Echocardiogram 1: Mitral regurgitation"));
		assertTrue(headers.toString(), headers.contains("RHD Consultation Visit 1: Mitral regurgitation"));
		assertEquals(1, ((List<?>) result.get("rows")).size());
		DownloadLogEntry logged = logged().get(0);
		assertNull(logged.formName);
		assertTrue(logged.detail, logged.detail.contains("\"rowPerPatient\":true"));
		signInAs("reader", ActCoreConstants.AUDITLOGWEB_VIEW_READ_AUDIT_LOGS);
		for (Form each : Arrays.asList(form, consult)) {
			assertEquals(Integer.valueOf(1),
			    controller.getDownloads(null, null, null, each.getUuid(), null, null, null).get("totalCount"));
		}
		assertEquals(Integer.valueOf(0),
		    controller.getDownloads(null, null, null, other.getUuid(), null, null, null).get("totalCount"));
	}
	
	@Test
	public void givesOneFormAsARowPerPatientWhenAsked() throws Exception {
		encounter(7);
		encounter(7);
		authenticateWith(with(ActCorePrivileges.DOWNLOAD_DATA));
		Map<String, Object> body = body();
		body.put("rowPerPatient", true);
		
		SimpleObject result = controller.download(body);
		
		assertTrue(((List<?>) result.get("headers")).contains("RHD Echocardiogram 2: Form date"));
		assertEquals(1, ((List<?>) result.get("rows")).size());
	}
	
	@Test
	public void appliesAndRecordsTheStatusAndClinicFilters() throws Exception {
		PersonAttributeType primaryCare = locationAttributeType("Primary care clinic");
		PersonAttributeType cardiac = locationAttributeType("Cardiac clinic");
		Context.getAdministrationService().setGlobalProperty(ActCoreConstants.GP_LOGS_CLINIC_ATTRIBUTE_TYPES,
		    primaryCare.getUuid() + "," + cardiac.getUuid());
		Patient seven = Context.getPatientService().getPatient(7);
		seven.addAttribute(new PersonAttribute(cardiac, "1"));
		Context.getPatientService().savePatient(seven);
		encounter(7);
		encounter(2);
		org.openmrs.Location one = Context.getLocationService().getLocation(1);
		authenticateWith(with(ActCorePrivileges.DOWNLOAD_DATA));
		Map<String, Object> body = body();
		body.put("cardiacClinic", one.getUuid());
		body.put("status", "inactive");
		
		SimpleObject result = controller.download(body);
		
		assertEquals(1, ((List<?>) result.get("rows")).size());
		String detail = logged().get(0).detail;
		assertTrue(detail, detail.contains("\"status\":\"inactive\""));
		assertTrue(detail, detail.contains("\"cardiacClinic\":\"" + one.getName()));
	}
	
	@Test
	public void refusesAnUnknownStatusOrClinic() throws Exception {
		authenticateWith(with(ActCorePrivileges.DOWNLOAD_DATA));
		Map<String, Object> status = body();
		status.put("status", "deceased");
		Map<String, Object> clinic = body();
		clinic.put("primaryCareClinic", "no-such-location");
		
		for (Map<String, Object> body : Arrays.asList(status, clinic)) {
			try {
				controller.download(body);
				fail("expected " + body + " to be refused");
			}
			catch (IllegalRequestException expected) {}
		}
		assertTrue(logged().isEmpty());
	}
	
	@Test
	public void givesOnlyThePatientFieldsChosenWithholdingTheIdentifyingOnesUnlessIdentified() throws Exception {
		encounter(7);
		authenticateWith(with(ActCorePrivileges.DOWNLOAD_DATA));
		Map<String, Object> body = body();
		body.put("patient", Arrays.asList("age", "givenName"));
		
		SimpleObject result = controller.download(body);
		
		assertEquals(Arrays.asList("ACT ID", "Age", "Form date", "Location", "Mitral regurgitation"), result.get("headers"));
		assertEquals(Collections.singletonList("Given name"), result.get("withheld"));
		String detail = logged().get(0).detail;
		assertTrue(detail, detail.contains("\"withheld\":[\"Given name\"]"));
		assertTrue(detail, detail.contains("\"patient\":[\"age\"]"));
		
		signInAs("identified", with(ActCorePrivileges.DOWNLOAD_DATA, ActCorePrivileges.DOWNLOAD_IDENTIFIERS));
		body.put("rowPerPatient", true);
		result = controller.download(body);
		
		assertEquals(Arrays.asList("ACT ID", "Given name", "Age", "RHD Echocardiogram 1: Form date",
		    "RHD Echocardiogram 1: Location", "RHD Echocardiogram 1: Mitral regurgitation"), result.get("headers"));
		assertEquals(Collections.emptyList(), result.get("withheld"));
	}
	
	@Test
	public void recordsIdentifiersOnlyWhenAnIdentifyingPatientFieldOrQuestionWasGiven() throws Exception {
		encounter(7);
		authenticateWith(with(ActCorePrivileges.DOWNLOAD_DATA, ActCorePrivileges.DOWNLOAD_IDENTIFIERS));
		Map<String, Object> body = body();
		for (Object rowPerPatient : Arrays.asList(false, true)) {
			body.put("rowPerPatient", rowPerPatient);
			identifyingQuestions("");
			body.put("patient", Collections.singletonList("age"));
			assertIdentifiers(false, controller.download(body));
			body.put("patient", Arrays.asList("age", "givenName"));
			assertIdentifiers(true, controller.download(body));
			identifyingQuestions(question.getUuid());
			body.put("patient", Collections.singletonList("age"));
			assertIdentifiers(true, controller.download(body));
		}
		
		signInAs("deidentified", with(ActCorePrivileges.DOWNLOAD_DATA));
		body.put("patient", Arrays.asList("age", "givenName"));
		assertIdentifiers(false, controller.download(body));
	}
	
	private static void identifyingQuestions(String uuids) {
		Context.addProxyPrivilege(PrivilegeConstants.MANAGE_GLOBAL_PROPERTIES);
		try {
			Context.getAdministrationService().setGlobalProperty(ActCoreConstants.GP_DOWNLOAD_IDENTIFYING_CONCEPTS, uuids);
		}
		finally {
			Context.removeProxyPrivilege(PrivilegeConstants.MANAGE_GLOBAL_PROPERTIES);
		}
	}
	
	private static void assertIdentifiers(boolean expected, SimpleObject result) {
		assertEquals(expected, result.get("identifiers"));
		String detail = logged().get(0).detail;
		assertTrue(detail, detail.contains("\"identifiers\":" + expected));
	}
	
	@Test
	public void refusesAPatientFieldItDoesNotKnow() throws Exception {
		authenticateWith(with(ActCorePrivileges.DOWNLOAD_DATA, ActCorePrivileges.DOWNLOAD_IDENTIFIERS));
		Map<String, Object> unknown = body();
		unknown.put("patient", Collections.singletonList("address"));
		Map<String, Object> notAList = body();
		notAList.put("patient", "sex");
		
		for (Map<String, Object> body : Arrays.asList(unknown, notAList)) {
			try {
				controller.download(body);
				fail("expected " + body + " to be refused");
			}
			catch (IllegalRequestException expected) {}
		}
		assertTrue(logged().isEmpty());
	}
	
	private Form secondForm() {
		Form second = new Form();
		second.setName("RHD Consultation Visit");
		second.setVersion("1." + Math.abs(UUID.randomUUID().hashCode()));
		second.setEncounterType(form.getEncounterType());
		return Context.getFormService().saveForm(second);
	}
	
	private Map<String, Object> choice(Form chosen) {
		Map<String, Object> field = new HashMap<String, Object>();
		field.put("concept", question.getUuid());
		field.put("label", "Mitral regurgitation");
		Map<String, Object> choice = new HashMap<String, Object>();
		choice.put("form", chosen.getUuid());
		choice.put("fields", Collections.singletonList(field));
		return choice;
	}
	
	private static PersonAttributeType locationAttributeType(String name) {
		PersonAttributeType type = new PersonAttributeType();
		type.setName(name);
		type.setFormat("org.openmrs.Location");
		return Context.getPersonService().savePersonAttributeType(type);
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
	
	/**
	 * Committed, as the log that names the user writes in a transaction of its own; so the user
	 * outlives the test, and a later one signing in by the same name takes it with a new role.
	 */
	private void signInAs(String username, String... privileges) {
		Context.authenticate("admin", "test");
		Transactions.inNewTransaction(() -> {
			UserService users = Context.getUserService();
			Role role = users.getRole("download caller " + username);
			if (role == null) {
				role = new Role("download caller " + username);
			}
			role.setPrivileges(new HashSet<>());
			for (String name : privileges) {
				Privilege privilege = users.getPrivilege(name);
				role.addPrivilege(privilege != null ? privilege : users.savePrivilege(new Privilege(name)));
			}
			users.saveRole(role);
			User user = users.getUserByUsername(username);
			if (user == null) {
				Person person = new Person();
				person.addName(new PersonName("Download", null, "Caller"));
				person.setGender("F");
				user = new User(person);
				user.setUsername(username);
				user.addRole(role);
				users.createUser(user, "Download123");
			} else {
				user.getRoles().clear();
				user.addRole(role);
				users.saveUser(user);
			}
		});
		Context.logout();
		Context.authenticate(username, "Download123");
	}
	
	/** The log as it was recorded, its detail as the JSON saved, whoever is signed in. */
	private static List<DownloadLogEntry> logged() {
		HibernateDownloadLogDAO dao = new HibernateDownloadLogDAO();
		dao.setSessionFactory(Context.getRegisteredComponent("dbSessionFactory", DbSessionFactory.class));
		return dao.getDownloads(null, null, null);
	}
}
