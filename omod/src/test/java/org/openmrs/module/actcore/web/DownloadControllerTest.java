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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
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
import org.openmrs.api.ValidationException;
import org.openmrs.api.context.Context;
import org.openmrs.api.db.hibernate.DbSession;
import org.openmrs.api.db.hibernate.DbSessionFactory;
import org.openmrs.messagesource.MessageSourceService;
import org.openmrs.messagesource.MutableMessageSource;
import org.openmrs.messagesource.PresentationMessage;
import org.openmrs.messagesource.impl.CachedMessageSource;
import org.openmrs.module.actcore.ActCoreConstants;
import org.openmrs.module.actcore.ActCorePrivileges;
import org.openmrs.module.actcore.ModuleTables;
import org.openmrs.module.actcore.api.DownloadLogService;
import org.openmrs.module.actcore.api.FormDownloadService;
import org.openmrs.module.actcore.api.db.hibernate.HibernateDownloadLogDAO;
import org.openmrs.module.actcore.api.impl.DownloadLogServiceImpl;
import org.openmrs.module.actcore.api.search.DownloadSearchCriteria;
import org.openmrs.module.actcore.model.DownloadLogEntry;
import org.openmrs.module.actcore.model.DownloadPage;
import org.openmrs.module.actcore.model.FormDownloadChoice;
import org.openmrs.module.actcore.model.FormDownloadFilters;
import org.openmrs.module.actcore.model.FormDownloadTable;
import org.openmrs.module.actcore.utils.TransactionsInTest;
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
	
	/** Details without the shape every download records, though each is a JSON object. */
	private static final List<String> SHAPELESS = Arrays.asList("{}", "{\"rows\":3}",
	    recorded("ACT ID", false, false).replace("[\"ACT ID\"]", "\"ACT ID\""),
	    recorded("ACT ID", false, false).replace("\"withheld\":[],", ""),
	    recorded("ACT ID", false, false).replace("}", ",\"filters\":{\"status\":{\"value\":\"active\"}}}"),
	    recorded("ACT ID", false, false).replace("}", ",\"filters\":{\"cardiacClinic\":[\"Lira RRH\"]}}"),
	    recorded("ACT ID", false, false).replace("}", ",\"filters\":{\"primaryCareClinic\":1}}"),
	    recorded("ACT ID", false, false).replace("}", ",\"forms\":[{}]}"),
	    recorded("ACT ID", false, false).replace("}", ",\"forms\":[{\"name\":{}}]}"),
	    recorded("ACT ID", false, false).replace("}", ",\"forms\":[{\"name\":\"RHD Echocardiogram\"},{}]}"),
	    recorded("ACT ID", false, false).replace("\"withheld\":[]", "\"withheld\":[\"Given name\",1]"),
	    recorded("ACT ID", false, false).replace("}", ",\"list\":\"registry\",\"filters\":\"x\"}"));
	
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
	public void service_shouldRefuseAUserWithoutADownloadPrivilegeAndRecordNothing() {
		int before = logged().size();
		authenticateWith(with(ActCorePrivileges.DOWNLOAD_IDENTIFIERS));
		try {
			download(false);
			fail("expected the download to be refused");
		}
		catch (APIAuthenticationException e) {
			assertTrue(e.getMessage(), e.getMessage().contains(ActCorePrivileges.DOWNLOAD_DATA));
		}
		assertEquals(before, logged().size());
	}
	
	@Test
	public void service_shouldGiveIdentifiersOnlyWithBothFullDownloadPrivilegesAndRecordEachDownload() {
		int before = logged().size();
		authenticateWith(with(ActCorePrivileges.DOWNLOAD_DATA));
		FormDownloadTable without = download(false);
		signInAs("identifiedcaller", with(ActCorePrivileges.DOWNLOAD_DATA, ActCorePrivileges.DOWNLOAD_IDENTIFIERS));
		FormDownloadTable identified = download(false);
		
		assertFalse(without.isIdentifiers());
		assertFalse(without.isResearchOnly());
		assertFalse(without.getHeaders().contains("Given name"));
		assertTrue(identified.isIdentifiers());
		assertFalse(identified.isResearchOnly());
		assertTrue(identified.getHeaders().contains("Given name"));
		assertEquals(before + 2, logged().size());
	}
	
	@Test
	public void service_shouldGiveTheResearchPrivilegeAloneOnlyAResearchDownloadNeverIdentified() {
		Context.getAdministrationService().setGlobalProperty(ActCoreConstants.GP_DOWNLOAD_IDENTIFYING_CONCEPTS,
		    question.getUuid());
		authenticateWith(with(ActCorePrivileges.DOWNLOAD_RESEARCH_DATA, ActCorePrivileges.DOWNLOAD_IDENTIFIERS));
		
		for (boolean rowPerPatient : new boolean[] { false, true }) {
			FormDownloadTable research = download(rowPerPatient);
			
			assertTrue(research.isResearchOnly());
			assertFalse(research.isIdentifiers());
			assertFalse(research.getHeaders().contains("Given name"));
			assertTrue(research.getWithheld().contains("Given name"));
			assertTrue(
			    research.getWithheld().contains(rowPerPatient ? "Echo: Mitral regurgitation" : "Mitral regurgitation"));
		}
	}
	
	/** The service's download of the form's one question, every patient field asked for. */
	private FormDownloadTable download(boolean rowPerPatient) {
		return Context.getService(FormDownloadService.class).download(
		    Collections.singletonList(new FormDownloadChoice(form, "Echo", Collections.singletonList(question),
		            Collections.singletonList("Mitral regurgitation"))),
		    rowPerPatient, new FormDownloadFilters(null, null, null), null, null,
		    new ArrayList<String>(ActCoreConstants.DOWNLOAD_PATIENT_FIELDS.keySet()));
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
		TransactionsInTest.inNewTransaction(
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
		
		String detail = logged().get(0).getDetail();
		
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
		
		String detail = logged().get(0).getDetail();
		assertTrue(detail, detail.contains("\"researchOnly\":false"));
	}
	
	@Test
	public void refusesToRecordFiltersLongerThanTheLogHolds() throws Exception {
		authenticateWith(with(ActCorePrivileges.DOWNLOAD_DATA));
		Map<String, Object> body = new HashMap<String, Object>();
		body.put("list", "registry");
		body.put("columns", Collections.singletonList("ACT ID"));
		body.put("filters",
		    Collections.singletonMap("name", StringUtils.repeat("x", ActCoreConstants.MAX_DOWNLOAD_DETAIL_BYTES)));
		
		try {
			controller.record(body);
			fail("expected the record to be refused");
		}
		catch (IllegalRequestException expected) {}
		assertTrue(logged().isEmpty());
	}
	
	@Test
	public void refusesToRecordADetailTheListingCannotReadOrTheLogCannotHold() {
		authenticateWith(with(ActCorePrivileges.DOWNLOAD_DATA));
		DownloadLogService service = Context.getService(DownloadLogService.class);
		int room = ActCoreConstants.MAX_DOWNLOAD_DETAIL_BYTES - recorded("", false, false).length();
		String fits = recorded(StringUtils.repeat("x", room), false, false);
		String overInBytesOnly = recorded(StringUtils.repeat("\u00e9", room / 2 + 1), false, false);
		assertTrue(overInBytesOnly.length() <= ActCoreConstants.MAX_DOWNLOAD_DETAIL_BYTES);
		
		for (String refused : Arrays.asList("{not json", "[1]", "null", "", overInBytesOnly)) {
			try {
				service.recordDownload(null, refused);
				fail("expected the detail to be refused: " + StringUtils.left(refused, 20));
			}
			catch (ValidationException expected) {}
		}
		assertTrue(logged().isEmpty());
		service.recordDownload(null, fits);
		service.recordDownload(null, null);
		assertEquals(2, logged().size());
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
		assertEquals(3, every.getTotalCount());
		assertEquals("researcher", every.getResults().get(0).getUser().getUsername());
		assertEquals(Boolean.TRUE, every.getResults().get(0).getDetail().get("researchOnly"));
		assertEquals("registry", every.getResults().get(1).getDetail().get("list"));
		assertEquals(2, service.getDownloads(filter(f -> f.setUsername("downloadcaller"))).getTotalCount());
		assertEquals(0, service.getDownloads(filter(f -> f.setUsername("nobody"))).getTotalCount());
		assertEquals(2, service.getDownloads(filter(f -> f.setFormUuid(form.getUuid()))).getTotalCount());
		assertEquals(0, service.getDownloads(filter(f -> f.setFormUuid("no-such-form"))).getTotalCount());
		assertEquals(1, service.getDownloads(filter(f -> f.setIdentifiers("research"))).getTotalCount());
		assertEquals(1, service.getDownloads(filter(f -> f.setIdentifiers("without"))).getTotalCount());
		DownloadPage page = service.getDownloads(filter(f -> {
			f.setStartIndex(1);
			f.setLimit(1);
		}));
		assertEquals(3, page.getTotalCount());
		assertEquals(1, page.getResults().size());
		assertEquals("registry", page.getResults().get(0).getDetail().get("list"));
	}
	
	@Test
	public void listsADownloadWhoseDetailCannotBeReadWithoutItAndAsPossiblyHoldingIdentifiers() throws Exception {
		Form other = new Form();
		other.setName("RHD Follow-up");
		other.setVersion("1");
		other.setEncounterType(Context.getEncounterService().getEncounterType(1));
		Context.getFormService().saveForm(other);
		authenticateWith(with(ActCorePrivileges.DOWNLOAD_DATA, ActCorePrivileges.DOWNLOAD_IDENTIFIERS));
		controller.download(body());
		controller.download(body());
		String spoiltFormUuid = spoilLastDetail("{not json");
		Map<String, Object> list = new HashMap<String, Object>();
		list.put("list", "registry");
		list.put("columns", Collections.singletonList("ACT ID"));
		controller.record(list);
		String spoiltListUuid = spoilLastDetail("{not json");
		signInAs("reader", ActCoreConstants.AUDITLOGWEB_VIEW_READ_AUDIT_LOGS);
		List<LogEvent> warned = new ArrayList<LogEvent>();
		
		SimpleObject every = warningsOf(warned, () -> controller.getDownloads(null, null, null, null, null, null, null));
		
		assertEquals(2, warned.size());
		for (LogEvent warning : warned) {
			assertEquals(Level.WARN, warning.getLevel());
		}
		String first = warned.get(0).getMessage().getFormattedMessage();
		assertEquals(warningFor(spoiltListUuid), first);
		String second = warned.get(1).getMessage().getFormattedMessage();
		assertEquals(warningFor(spoiltFormUuid), second);
		assertEquals(Integer.valueOf(3), every.get("totalCount"));
		List<?> results = (List<?>) every.get("results");
		SimpleObject spoiltList = (SimpleObject) results.get(0);
		assertNull(spoiltList.get("detail"));
		assertNull(spoiltList.get("list"));
		assertNull(spoiltList.get("form"));
		assertEquals("downloadcaller", ((SimpleObject) spoiltList.get("user")).get("username"));
		SimpleObject spoiltForm = (SimpleObject) results.get(1);
		assertNull(spoiltForm.get("detail"));
		assertEquals("RHD Echocardiogram", ((SimpleObject) spoiltForm.get("form")).get("name"));
		assertEquals(Boolean.TRUE, ((Map<?, ?>) ((SimpleObject) results.get(2)).get("detail")).get("identifiers"));
		assertEquals(Integer.valueOf(3),
		    controller.getDownloads(null, null, null, form.getUuid(), null, null, null).get("totalCount"));
		List<?> ofOther = (List<?>) controller.getDownloads(null, null, null, other.getUuid(), null, null, null)
		        .get("results");
		assertEquals(1, ofOther.size());
		assertNull(((SimpleObject) ofOther.get(0)).get("form"));
		assertEquals(Integer.valueOf(3),
		    controller.getDownloads(null, null, null, null, "with", null, null).get("totalCount"));
		assertEquals(Integer.valueOf(0),
		    controller.getDownloads(null, null, null, null, "without", null, null).get("totalCount"));
		assertEquals(Integer.valueOf(0),
		    controller.getDownloads(null, null, null, null, "research", null, null).get("totalCount"));
	}
	
	@Test
	public void listsADownloadRecordedWithoutADetailAsWithoutIdentifiersAndWithoutAWarning() {
		authenticateWith(with(ActCorePrivileges.DOWNLOAD_DATA));
		Context.getService(DownloadLogService.class).recordDownload(null, null);
		signInAs("reader", ActCoreConstants.AUDITLOGWEB_VIEW_READ_AUDIT_LOGS);
		List<LogEvent> warned = new ArrayList<LogEvent>();
		
		SimpleObject without = warningsOf(warned,
		    () -> controller.getDownloads(null, null, null, null, "without", null, null));
		
		assertEquals(Integer.valueOf(1), without.get("totalCount"));
		assertNull(((SimpleObject) ((List<?>) without.get("results")).get(0)).get("detail"));
		assertEquals(Integer.valueOf(0),
		    controller.getDownloads(null, null, null, null, "with", null, null).get("totalCount"));
		assertTrue(warned.isEmpty());
	}
	
	@Test
	public void givesEachListedDownloadTheIdentifiersFilterThatListsItAndWhetherItsDetailIsUnreadable() {
		authenticateWith(with(ActCorePrivileges.DOWNLOAD_DATA));
		DownloadLogService service = Context.getService(DownloadLogService.class);
		service.recordDownload(null, recorded("ACT ID", true, false));
		service.recordDownload(null, recorded("ACT ID", false, false));
		service.recordDownload(null, recorded("ACT ID", false, true));
		service.recordDownload(null, null);
		service.recordDownload(null, recorded("ACT ID", false, false));
		String spoiltUuid = spoilLastDetail("{not json");
		service.recordDownload(null, recorded("ACT ID", false, false));
		String nulledUuid = spoilLastDetail("null");
		signInAs("reader", ActCoreConstants.AUDITLOGWEB_VIEW_READ_AUDIT_LOGS);
		List<LogEvent> warned = new ArrayList<LogEvent>();
		
		List<?> every = (List<?>) warningsOf(warned, () -> controller.getDownloads(null, null, null, null, null, null, null))
		        .get("results");
		
		List<String> named = new ArrayList<String>();
		for (Object each : every) {
			named.add(((SimpleObject) each).get("identifiers")
			        + (Boolean.TRUE.equals(((SimpleObject) each).get("unreadable")) ? " unreadable" : ""));
		}
		assertEquals(Arrays.asList("with unreadable", "with unreadable", "without", "research", "without", "with"), named);
		assertNull(((SimpleObject) every.get(2)).get("detail"));
		assertEquals(Boolean.FALSE, ((SimpleObject) every.get(2)).get("unreadable"));
		for (String kind : Arrays.asList("with", "without", "research")) {
			List<?> listed = (List<?>) controller.getDownloads(null, null, null, null, kind, null, null).get("results");
			assertEquals(kind, Collections.frequency(named, kind) + Collections.frequency(named, kind + " unreadable"),
			    listed.size());
			for (Object each : listed) {
				assertEquals(kind, ((SimpleObject) each).get("identifiers"));
			}
		}
		assertEquals(2, warned.size());
		String first = warned.get(0).getMessage().getFormattedMessage();
		assertEquals(warningFor(nulledUuid), first);
		String second = warned.get(1).getMessage().getFormattedMessage();
		assertEquals(warningFor(spoiltUuid), second);
	}
	
	@Test
	public void listsADetailWithoutTheShapeEveryDownloadRecordsAsUnreadable() throws Exception {
		Form other = secondForm();
		authenticateWith(with(ActCorePrivileges.DOWNLOAD_DATA));
		controller.download(body());
		spoilLastDetail("{}");
		List<String> spoilt = new ArrayList<String>();
		for (String detail : SHAPELESS) {
			Map<String, Object> list = new HashMap<String, Object>();
			list.put("list", "registry");
			list.put("columns", Collections.singletonList("ACT ID"));
			controller.record(list);
			spoilt.add(0, spoilLastDetail(detail));
		}
		signInAs("reader", ActCoreConstants.AUDITLOGWEB_VIEW_READ_AUDIT_LOGS);
		List<LogEvent> warned = new ArrayList<LogEvent>();
		
		List<?> every = (List<?>) warningsOf(warned, () -> controller.getDownloads(null, null, null, null, null, null, null))
		        .get("results");
		
		assertEquals(SHAPELESS.size() + 1, every.size());
		for (Object each : every) {
			SimpleObject download = (SimpleObject) each;
			assertEquals(Boolean.TRUE, download.get("unreadable"));
			assertEquals("with", download.get("identifiers"));
			assertNull(download.get("detail"));
			assertNull(download.get("list"));
		}
		assertEquals(SHAPELESS.size() + 1, warned.size());
		for (int i = 0; i < spoilt.size(); i++) {
			assertEquals(warningFor(spoilt.get(i)), warned.get(i).getMessage().getFormattedMessage());
		}
		assertEquals(Integer.valueOf(SHAPELESS.size() + 1),
		    controller.getDownloads(null, null, null, form.getUuid(), null, null, null).get("totalCount"));
		assertEquals(Integer.valueOf(SHAPELESS.size()),
		    controller.getDownloads(null, null, null, other.getUuid(), null, null, null).get("totalCount"));
		assertEquals(Integer.valueOf(0),
		    controller.getDownloads(null, null, null, null, "without", null, null).get("totalCount"));
	}
	
	@Test
	public void refusesToRecordADetailWithoutTheShapeEveryDownloadRecords() {
		authenticateWith(with(ActCorePrivileges.DOWNLOAD_DATA));
		DownloadLogService service = Context.getService(DownloadLogService.class);
		List<String> refused = new ArrayList<String>(SHAPELESS);
		String shaped = recorded("ACT ID", false, false);
		refused.add(shaped.replace("\"withheld\":[]", "\"withheld\":[1]"));
		refused.add(shaped.replace("\"rows\":1", "\"rows\":\"1\""));
		refused.add(shaped.replace("\"identifiers\":false", "\"identifiers\":\"no\""));
		refused.add(shaped.replace("\"researchOnly\":false", "\"researchOnly\":null"));
		refused.add(shaped.replace("\"startDate\":null,", ""));
		refused.add(shaped.replace("\"endDate\":null", "\"endDate\":20260630"));
		refused.add(shaped.replace("}", ",\"list\":[\"registry\"]}"));
		refused.add(shaped.replace("}", ",\"forms\":{}}"));
		refused.add(shaped.replace("}", ",\"forms\":[\"RHD Echocardiogram\"]}"));
		refused.add(shaped.replace("}", ",\"rowPerPatient\":\"yes\"}"));
		refused.add(shaped.replace("}", ",\"filters\":[]}"));
		refused.add(shaped.replace("}", ",\"patient\":\"sex\"}"));
		refused.add(shaped.replace("}", ",\"patient\":[null]}"));
		
		for (String detail : refused) {
			try {
				service.recordDownload(null, detail);
				fail("expected the detail to be refused: " + detail);
			}
			catch (ValidationException e) {
				assertEquals(detail, "A download's detail must be a JSON object of the shape a download records",
				    e.getMessage());
			}
		}
		assertTrue(logged().isEmpty());
	}
	
	@Test
	public void recordsAndListsEveryDetailTheDownloadsRecordAndHaveRecorded() throws Exception {
		Form second = secondForm();
		authenticateWith(with(ActCorePrivileges.DOWNLOAD_DATA, ActCorePrivileges.DOWNLOAD_IDENTIFIERS));
		DownloadLogService service = Context.getService(DownloadLogService.class);
		// As downloads were recorded before AR-76, and then a list the app made itself.
		service.recordDownload(form.getFormId(),
		    "{\"fields\":[\"Mitral regurgitation\"],\"withheld\":[\"Given name\"],\"startDate\":\"2026-01-01\","
		            + "\"endDate\":null,\"rows\":2,\"identifiers\":false,\"researchOnly\":true}");
		service.recordDownload(null,
		    "{\"list\":\"registry\",\"fields\":[\"ACT ID\"],\"withheld\":[],\"startDate\":null,\"endDate\":null,"
		            + "\"rows\":0,\"identifiers\":true,\"researchOnly\":false,\"filters\":{}}");
		service.recordDownload(form.getFormId(),
		    "{\"fields\":[\"ACT ID\"],\"withheld\":[],\"startDate\":null,\"endDate\":null,\"rows\":1,"
		            + "\"identifiers\":true,\"researchOnly\":false,\"forms\":[{\"uuid\":\"" + form.getUuid()
		            + "\",\"name\":\"RHD Echocardiogram\",\"fields\":[\"ACT ID\"]}],\"rowPerPatient\":false,"
		            + "\"filters\":{\"status\":\"active\",\"cardiacClinic\":\"Lira RRH\",\"primaryCareClinic\":null},"
		            + "\"patient\":[]}");
		controller.download(body());
		Map<String, Object> both = new HashMap<String, Object>();
		both.put("forms", Arrays.asList(choice(form), choice(second)));
		both.put("patient", Arrays.asList("sex", "givenName"));
		controller.download(both);
		authenticateWith(with(ActCorePrivileges.DOWNLOAD_RESEARCH_DATA));
		controller.download(body());
		// As the app's Download CSV and dashboard export record their files.
		Map<String, Object> worklist = new HashMap<String, Object>();
		worklist.put("list", "worklist:Due for prophylaxis");
		worklist.put("columns", Arrays.asList("ACT ID", "Name"));
		worklist.put("rows", 12);
		worklist.put("identifiers", true);
		worklist.put("filters", Collections.singletonMap("filter", "overdue"));
		controller.record(worklist);
		Map<String, Object> figure = new HashMap<String, Object>();
		figure.put("list", "dashboard:screenings");
		figure.put("columns", Arrays.asList("Section", "Group", "Label", "Count", "Period", "Clinics"));
		figure.put("rows", 4);
		figure.put("identifiers", false);
		Map<String, Object> filters = new HashMap<String, Object>();
		filters.put("clinics", "Lira RRH");
		filters.put("startDate", "2026-01-01");
		figure.put("filters", filters);
		controller.record(figure);
		Map<String, Object> bare = new HashMap<String, Object>();
		bare.put("list", "logs:downloads");
		bare.put("columns", Collections.emptyList());
		controller.record(bare);
		Map<String, Object> odd = new HashMap<String, Object>();
		odd.put("list", "registry");
		odd.put("columns", Collections.singletonList("ACT ID"));
		odd.put("filters", Collections.singletonMap("status", Collections.singletonMap("value", "active")));
		controller.record(odd);
		signInAs("reader", ActCoreConstants.AUDITLOGWEB_VIEW_READ_AUDIT_LOGS);
		List<LogEvent> warned = new ArrayList<LogEvent>();
		
		List<?> every = (List<?>) warningsOf(warned, () -> controller.getDownloads(null, null, null, null, null, null, null))
		        .get("results");
		
		assertEquals(10, every.size());
		for (Object each : every) {
			assertEquals(Boolean.FALSE, ((SimpleObject) each).get("unreadable"));
			assertTrue(((SimpleObject) each).get("detail") instanceof Map);
		}
		assertTrue(warned.isEmpty());
	}
	
	/**
	 * A detail of the shape every download has recorded since 1.0.0, of one field with this label and
	 * with the identifiers and research flags given.
	 */
	private static String recorded(String label, boolean identifiers, boolean researchOnly) {
		return "{\"fields\":[\"" + label + "\"],\"withheld\":[],\"startDate\":null,\"endDate\":null,\"rows\":1,"
		        + "\"identifiers\":" + identifiers + ",\"researchOnly\":" + researchOnly + "}";
	}
	
	/** The WARN logged for the download with this uuid when its detail cannot be read. */
	private static String warningFor(String uuid) {
		return "Download " + uuid
		        + " has a recorded detail that cannot be read as a JSON object of the shape a download records, so the detail is left out when the download is listed";
	}
	
	/** Committed, as the download's record was. It gives the uuid of the record spoilt. */
	private static String spoilLastDetail(String detail) {
		String table = ActCoreConstants.DOWNLOAD_LOG_TABLE;
		String last = "(select max(download_id) from " + table + ")";
		AtomicReference<String> uuid = new AtomicReference<String>();
		TransactionsInTest.inNewTransaction(() -> {
			DbSession session = Context.getRegisteredComponent("dbSessionFactory", DbSessionFactory.class)
			        .getCurrentSession();
			session.createSQLQuery("update " + table + " set detail = :detail where download_id = " + last)
			        .setParameter("detail", detail).executeUpdate();
			uuid.set((String) session.createSQLQuery("select uuid from " + table + " where download_id = " + last)
			        .uniqueResult());
		});
		return uuid.get();
	}
	
	/**
	 * Runs the listing with what the download log service logs at WARN, as the distribution's
	 * log4j2.xml leaves org.openmrs, added to the warnings.
	 */
	private static <T> T warningsOf(List<LogEvent> warned, Supplier<T> listing) {
		AbstractAppender appender = new AbstractAppender("capture", null, null, true, Property.EMPTY_ARRAY) {
			
			@Override
			public void append(LogEvent event) {
				warned.add(event.toImmutable());
			}
		};
		appender.start();
		Logger logger = (Logger) LogManager.getLogger(DownloadLogServiceImpl.class);
		Level level = logger.getLevel();
		logger.addAppender(appender);
		// Kept off the console: a test captures what it expects to be logged.
		boolean additive = logger.isAdditive();
		logger.setAdditive(false);
		// The test log4j2.xml turns every logger off.
		logger.setLevel(Level.WARN);
		try {
			return listing.get();
		}
		finally {
			logger.removeAppender(appender);
			logger.setLevel(level);
			logger.setAdditive(additive);
		}
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
		assertNull(logged.getFormName());
		assertTrue(logged.getDetail(), logged.getDetail().contains("\"rowPerPatient\":true"));
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
		String detail = logged().get(0).getDetail();
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
		String detail = logged().get(0).getDetail();
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
		String detail = logged().get(0).getDetail();
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
		TransactionsInTest.inNewTransaction(() -> {
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
