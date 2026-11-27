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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Test;
import org.openmrs.Location;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.api.AuditLogService;
import org.openmrs.module.actcore.api.DownloadLogService;
import org.openmrs.module.actcore.api.RaisedFlagService;
import org.openmrs.module.actcore.api.RunChartService;
import org.openmrs.module.actcore.api.ScreeningCountService;
import org.openmrs.module.actcore.api.search.ActivitySearchCriteria;
import org.openmrs.module.actcore.api.search.DownloadSearchCriteria;
import org.openmrs.module.actcore.api.search.ReadSearchCriteria;
import org.openmrs.module.actcore.model.ActivityDetails;
import org.openmrs.module.actcore.model.ActivityEncounter;
import org.openmrs.module.actcore.model.ActivityEntry;
import org.openmrs.module.actcore.model.ActivityField;
import org.openmrs.module.actcore.model.ActivityPage;
import org.openmrs.module.actcore.model.ActivityTarget;
import org.openmrs.module.actcore.model.ChartFlag;
import org.openmrs.module.actcore.model.DownloadLogItem;
import org.openmrs.module.actcore.model.DownloadPage;
import org.openmrs.module.actcore.model.LogUser;
import org.openmrs.module.actcore.model.ReadEvent;
import org.openmrs.module.actcore.model.ReadPage;
import org.openmrs.module.actcore.model.ReadPatient;
import org.openmrs.module.actcore.model.ReadRow;
import org.openmrs.module.actcore.model.RunChartPoint;
import org.openmrs.module.actcore.model.ScreeningCount;
import org.openmrs.module.actcore.model.ScreeningCountMonth;
import org.openmrs.module.actcore.model.ScreeningCountSummary;
import org.openmrs.module.patientflags.Flag;
import org.openmrs.module.webservices.rest.web.ConversionUtil;
import org.openmrs.web.test.BaseModuleWebContextSensitiveTest;
import org.springframework.aop.TargetSource;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.target.SingletonTargetSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Pins the JSON bodies that ACT Core's read endpoints build from the model types its services
 * return, byte for byte, with each service answering a fixed value, and the search criteria each
 * endpoint hands its service.
 */
public class JsonBodiesTest extends BaseModuleWebContextSensitiveTest {
	
	private static final Date TIME = Timestamp.valueOf("2026-03-14 10:15:30");
	
	private static final Date LATER = Timestamp.valueOf("2026-03-14 10:45:00");
	
	/** Unknown Location in the standard test dataset. */
	private static final String CLINIC = "8d6c993e-c2cc-11de-8d13-0010c6dffd0f";
	
	@Autowired
	private List<RequestMappingHandlerMapping> mappings;
	
	@Autowired
	private RequestMappingHandlerAdapter adapter;
	
	private final List<Runnable> restores = new ArrayList<Runnable>();
	
	private final Map<String, Object[]> calls = new HashMap<String, Object[]>();
	
	@After
	public void restoreTheServices() {
		for (Runnable restore : restores) {
			restore.run();
		}
	}
	
	@Test
	public void activity_shouldGiveTheEntriesAsBefore() throws Exception {
		ActivityPage page = new ActivityPage();
		ActivityEntry entry = new ActivityEntry();
		entry.setRevisionId(42);
		entry.setTime(TIME);
		entry.setUser(user());
		entry.setKind("edited");
		entry.setAction("Edited");
		entry.setSummary("Echo saved");
		ActivityTarget target = new ActivityTarget();
		target.setKind("patient");
		target.setUuid("target-uuid");
		target.setDisplay("Jane Doe");
		target.setActId("6TS-4");
		entry.setTarget(target);
		ActivityEncounter encounter = new ActivityEncounter();
		encounter.setUuid("encounter-uuid");
		encounter.setFormUuid("form-uuid");
		encounter.setFormName("RHD Echocardiogram");
		encounter.setTypeName("Echo");
		encounter.setVisitDate(TIME);
		entry.setEncounter(encounter);
		page.getResults().add(entry);
		page.getResults().add(new ActivityEntry());
		page.setTotalCount(7);
		stub(AuditLogService.class, "getActivity", page);
		
		String body = get("/rest/v1/actcore/activity", "username", "nurse", "startDate", "2026-03-01", "endDate",
		    "2026-03-31", "kind", "edited", "form", "form-uuid", "patient", "patient-uuid", "startIndex", "5", "limit",
		    "20");
		
		assertEquals("{\"results\":[{\"revisionId\":42,\"time\":" + time(TIME) + ",\"user\":" + USER
		        + ",\"kind\":\"edited\",\"action\":\"Edited\",\"summary\":\"Echo saved\",\"target\":{\"kind\":\"patient\","
		        + "\"uuid\":\"target-uuid\",\"display\":\"Jane Doe\",\"actId\":\"6TS-4\"},\"encounter\":{\"uuid\":"
		        + "\"encounter-uuid\",\"form\":{\"uuid\":\"form-uuid\",\"name\":\"RHD Echocardiogram\"},\"visitDate\":"
		        + "\"2026-03-14\"},\"ip\":null,\"userAgent\":null},{\"revisionId\":null,\"time\":null,\"user\":null,"
		        + "\"kind\":null,\"action\":null,\"summary\":null,\"target\":null,\"encounter\":null,\"ip\":null,"
		        + "\"userAgent\":null}],\"totalCount\":7,\"from\":\"2026-03-01\",\"to\":\"2026-03-31\"}",
		    body);
		ActivitySearchCriteria filter = (ActivitySearchCriteria) calls.get("getActivity")[0];
		assertEquals(Arrays.asList("nurse", "2026-03-01", "2026-03-31", "edited", "form-uuid", "patient-uuid", 5, 20),
		    Arrays.asList(filter.getUsername(), filter.getFrom().toString(), filter.getTo().toString(), filter.getKind(),
		        filter.getFormUuid(), filter.getPatientUuid(), filter.getStartIndex(), filter.getLimit()));
	}
	
	@Test
	public void activityDetails_shouldGiveTheFieldsAsBefore() throws Exception {
		ActivityDetails details = new ActivityDetails();
		details.setRevisionId(42);
		details.getFields().add(new ActivityField("EF", "50", "55"));
		details.getFields().add(new ActivityField("Notes", null, "Seen"));
		details.setVoidReason("Entered twice");
		stub(AuditLogService.class, "getActivityDetails", details);
		
		assertEquals(
		    "{\"revisionId\":42,\"fields\":[{\"name\":\"EF\",\"previous\":\"50\",\"current\":\"55\"},"
		            + "{\"name\":\"Notes\",\"previous\":null,\"current\":\"Seen\"}],\"voidReason\":\"Entered twice\"}",
		    get("/rest/v1/actcore/activity/42"));
		assertEquals(42, calls.get("getActivityDetails")[0]);
	}
	
	@Test
	public void reads_shouldGiveTheRowsAsBefore() throws Exception {
		ReadPage page = new ReadPage();
		ReadRow row = new ReadRow();
		row.setId("row-1");
		row.setFrom(TIME);
		row.setTo(LATER);
		row.setUser(user());
		ReadPatient patient = new ReadPatient();
		patient.setUuid("patient-uuid");
		patient.setDisplay("Jane Doe");
		patient.setActId("6TS-4");
		patient.setPrimaryCareClinic("Kira HC");
		patient.setCardiacClinic("Lira RRH");
		row.setPatient(patient);
		ReadEvent read = new ReadEvent();
		read.setTime(TIME);
		read.setEntity("Patient");
		read.setDescription("Opened the chart");
		row.getReads().add(read);
		row.getReads().add(new ReadEvent());
		row.setSessionId("session");
		row.setIp("10.0.0.1");
		row.setUserAgent("Firefox");
		row.setSameClinicAsUser(Boolean.TRUE);
		page.getResults().add(row);
		page.getResults().add(new ReadRow());
		page.setTotalCount(3);
		stub(AuditLogService.class, "getReads", page);
		
		String body = get("/rest/v1/actcore/reads", "username", "nurse", "patient", "6TS-4", "startDate", "2026-03-01",
		    "endDate", "2026-03-31", "startIndex", "2", "limit", "10");
		
		assertEquals("{\"results\":[{\"id\":\"row-1\",\"from\":" + time(TIME) + ",\"to\":" + time(LATER) + ",\"user\":"
		        + USER + ",\"patient\":{\"uuid\":\"patient-uuid\",\"display\":\"Jane Doe\",\"actId\":\"6TS-4\","
		        + "\"primaryCareClinic\":\"Kira HC\",\"cardiacClinic\":\"Lira RRH\"},\"count\":2,\"reads\":[{\"time\":"
		        + time(TIME)
		        + ",\"entity\":\"Patient\",\"description\":\"Opened the chart\"},{\"time\":null,\"entity\":null,"
		        + "\"description\":null}],\"sessionId\":\"session\",\"ip\":\"10.0.0.1\",\"userAgent\":\"Firefox\","
		        + "\"sameClinicAsUser\":true},{\"id\":null,\"from\":null,\"to\":null,\"user\":null,\"patient\":null,"
		        + "\"count\":0,\"reads\":[],\"sessionId\":null,\"ip\":null,\"userAgent\":null,\"sameClinicAsUser\":null}],"
		        + "\"totalCount\":3,\"from\":\"2026-03-01\",\"to\":\"2026-03-31\"}",
		    body);
		ReadSearchCriteria filter = (ReadSearchCriteria) calls.get("getReads")[0];
		assertEquals(Arrays.asList("nurse", "6TS-4", "2026-03-01", "2026-03-31", 2, 10),
		    Arrays.asList(filter.getUsername(), filter.getPatient(), filter.getFrom().toString(), filter.getTo().toString(),
		        filter.getStartIndex(), filter.getLimit()));
	}
	
	@Test
	public void downloads_shouldGiveTheDownloadsAsBefore() throws Exception {
		DownloadPage page = new DownloadPage();
		DownloadLogItem download = new DownloadLogItem();
		download.setDate(TIME);
		download.setUser(user());
		download.setFormUuid("form-uuid");
		download.setFormName("RHD Echocardiogram");
		Map<String, Object> detail = new LinkedHashMap<String, Object>();
		detail.put("fields", Collections.singletonList("EF"));
		detail.put("rows", 3);
		download.setDetail(detail);
		download.setIdentifiers("without");
		DownloadLogItem list = new DownloadLogItem();
		list.setDetail(Collections.singletonMap("list", "worklist:Overdue"));
		list.setIdentifiers("with");
		DownloadLogItem unreadable = new DownloadLogItem();
		unreadable.setUnreadable(true);
		unreadable.setIdentifiers("with");
		page.getResults().addAll(Arrays.asList(download, list, unreadable));
		page.setTotalCount(9);
		stub(DownloadLogService.class, "getDownloads", page);
		
		String body = get("/rest/v1/actcore/download", "username", "nurse", "startDate", "2026-03-01", "endDate",
		    "2026-03-31", "form", "form-uuid", "identifiers", "without", "startIndex", "1", "limit", "50");
		
		assertEquals("{\"results\":[{\"date\":" + time(TIME) + ",\"user\":" + USER + ",\"form\":{\"uuid\":\"form-uuid\","
		        + "\"name\":\"RHD Echocardiogram\"},\"list\":null,\"detail\":{\"fields\":[\"EF\"],\"rows\":3},"
		        + "\"identifiers\":\"without\",\"unreadable\":false},{\"date\":null,\"user\":null,\"form\":null,\"list\":"
		        + "{\"name\":\"Overdue worklist\",\"kind\":\"worklist\"},\"detail\":{\"list\":\"worklist:Overdue\"},"
		        + "\"identifiers\":\"with\",\"unreadable\":false},{\"date\":null,\"user\":null,\"form\":null,\"list\":null,"
		        + "\"detail\":null,\"identifiers\":\"with\",\"unreadable\":true}],\"totalCount\":9,\"from\":\"2026-03-01\","
		        + "\"to\":\"2026-03-31\"}",
		    body);
		DownloadSearchCriteria filter = (DownloadSearchCriteria) calls.get("getDownloads")[0];
		assertEquals(Arrays.asList("nurse", "2026-03-01", "2026-03-31", "form-uuid", "without", 1, 50),
		    Arrays.asList(filter.getUsername(), filter.getFrom().toString(), filter.getTo().toString(), filter.getFormUuid(),
		        filter.getIdentifiers(), filter.getStartIndex(), filter.getLimit()));
	}
	
	@Test
	public void screeningCounts_shouldGiveTheMonthAsBefore() throws Exception {
		ScreeningCountMonth month = new ScreeningCountMonth();
		month.setSaved(true);
		month.getCounts().put("school_screened", 40);
		month.getCounts().put("school_positive", 1);
		month.setNotes("Two schools");
		stub(ScreeningCountService.class, "getMonth", month);
		
		assertEquals(
		    "{\"clinic\":\"" + CLINIC + "\",\"month\":\"2026-08\",\"saved\":true,\"counts\":{\"school_screened\":40,"
		            + "\"school_positive\":1},\"notes\":\"Two schools\"}",
		    get("/rest/v1/actcore/screeningcounts", "clinic", CLINIC, "month", "2026-08"));
		assertEquals(CLINIC, ((Location) calls.get("getMonth")[0]).getUuid());
		assertEquals(YearMonth.of(2026, 8), calls.get("getMonth")[1]);
	}
	
	@Test
	public void screeningCountSummary_shouldGiveTheSumsAsBefore() throws Exception {
		ScreeningCountSummary summary = new ScreeningCountSummary();
		summary.setMonths(4);
		long value = 1;
		for (String column : ScreeningCount.COLUMNS) {
			summary.getSums().put(column, value++);
		}
		stub(ScreeningCountService.class, "getSummary", summary);
		
		assertEquals(
		    "{\"startMonth\":\"2026-01\",\"endMonth\":\"2026-06\",\"months\":4,\"screened\":21,\"females\":33,"
		            + "\"positive\":36,\"negative\":-15,\"children\":24,\"adolescents\":27,\"adults\":30,\"settings\":"
		            + "{\"school\":1,\"community\":7,\"facility\":13}}",
		    get("/rest/v1/actcore/screeningcounts/summary", "startMonth", "2026-01", "endMonth", "2026-06"));
	}
	
	@Test
	public void raisedFlags_shouldGiveTheFlagsAsBefore() throws Exception {
		Flag flag = new Flag();
		flag.setUuid("flag-uuid");
		flag.setName("Overdue for prophylaxis");
		Flag other = new Flag();
		other.setUuid("other-uuid");
		other.setName("Missing echo");
		stub(RaisedFlagService.class, "getRaisedFlags",
		    Arrays.asList(new ChartFlag(flag, TIME, true), new ChartFlag(other, null, false)));
		String patient = Context.getPatientService().getPatient(7).getUuid();
		
		assertEquals(
		    "{\"results\":[{\"flag\":\"flag-uuid\",\"display\":\"Overdue for prophylaxis\",\"raisedSince\":" + time(TIME)
		            + ",\"dismissible\":true},{\"flag\":\"other-uuid\",\"display\":\"Missing echo\","
		            + "\"raisedSince\":null,\"dismissible\":false}]}",
		    get("/rest/v1/actcore/raisedflags", "patient", patient));
	}
	
	@Test
	public void runCharts_shouldGiveThePointsAsBefore() throws Exception {
		Map<String, List<RunChartPoint>> series = new LinkedHashMap<String, List<RunChartPoint>>();
		series.put("total_registrants", Arrays.asList(new RunChartPoint(LocalDate.of(2026, 3, 2), 4.0),
		    new RunChartPoint(LocalDate.of(2026, 3, 9), 6.0)));
		series.put("prophylaxis_adherence", Arrays.asList(new RunChartPoint(LocalDate.of(2026, 3, 2), 0.5, 1L, 2L),
		    new RunChartPoint(LocalDate.of(2026, 3, 9), null, 0L, 0L)));
		stub(RunChartService.class, "getRunCharts", series);
		
		assertEquals("{\"startDate\":\"2026-03-01\",\"endDate\":\"2026-03-14\",\"period\":\"week\",\"metric\":"
		        + "\"total_registrants\",\"points\":[{\"date\":\"2026-03-02\",\"value\":4.0,\"count\":null,\"total\":null},"
		        + "{\"date\":\"2026-03-09\",\"value\":6.0,\"count\":null,\"total\":null}],\"median\":5.0,\"metrics\":"
		        + "{\"total_registrants\":{\"points\":[{\"date\":\"2026-03-02\",\"value\":4.0,\"count\":null,\"total\":null},"
		        + "{\"date\":\"2026-03-09\",\"value\":6.0,\"count\":null,\"total\":null}],\"median\":5.0},"
		        + "\"prophylaxis_adherence\":{\"points\":[{\"date\":\"2026-03-02\",\"value\":0.5,\"count\":1,\"total\":2},"
		        + "{\"date\":\"2026-03-09\",\"value\":null,\"count\":0,\"total\":0}],\"median\":0.5}}}",
		    get("/rest/v1/actcore/runchart", "metric", "total_registrants", "startDate", "2026-03-01", "endDate",
		        "2026-03-14"));
	}
	
	private static final String USER = "{\"uuid\":\"user-uuid\",\"username\":\"nurse\",\"display\":\"Nurse Jane\","
	        + "\"role\":\"Clinician\"}";
	
	private static LogUser user() {
		LogUser user = new LogUser();
		user.setUuid("user-uuid");
		user.setUsername("nurse");
		user.setDisplay("Nurse Jane");
		user.setRole("Clinician");
		return user;
	}
	
	/** A time as the endpoints write it, in the server's time zone. */
	private static String time(Date date) {
		return "\"" + ConversionUtil.convertToRepresentation(date, null) + "\"";
	}
	
	/**
	 * Makes the service answer the method with the result, and records the arguments it was called
	 * with, until the test ends. Its other methods keep their own answers.
	 */
	private <T> void stub(Class<T> type, String method, Object result) {
		Advised service = (Advised) Context.getService(type);
		TargetSource own = service.getTargetSource();
		Object fake = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (proxy, called, args) -> {
			if (called.getName().equals(method)) {
				calls.put(method, args);
				return result;
			}
			try {
				return called.invoke(own.getTarget(), args);
			}
			catch (InvocationTargetException e) {
				throw e.getCause();
			}
		});
		service.setTargetSource(new SingletonTargetSource(fake));
		restores.add(() -> service.setTargetSource(own));
	}
	
	/** The body of a GET of the URI with the parameters, as Spring MVC writes it for a JSON client. */
	private String get(String uri, String... parameters) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
		request.addHeader("Accept", "application/json");
		for (int i = 0; i < parameters.length; i += 2) {
			request.setParameter(parameters[i], parameters[i + 1]);
		}
		MockHttpServletResponse response = new MockHttpServletResponse();
		HandlerExecutionChain chain = null;
		for (RequestMappingHandlerMapping mapping : mappings) {
			chain = chain == null ? mapping.getHandler(request) : chain;
		}
		adapter.handle(request, response, chain.getHandler());
		assertEquals(response.getContentAsString(), 200, response.getStatus());
		return response.getContentAsString();
	}
}
