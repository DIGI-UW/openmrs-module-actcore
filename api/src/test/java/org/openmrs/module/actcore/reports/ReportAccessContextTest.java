/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.reports;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openmrs.Person;
import org.openmrs.PersonName;
import org.openmrs.Privilege;
import org.openmrs.Role;
import org.openmrs.User;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.UserService;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.Daemon;
import org.openmrs.module.DaemonToken;
import org.openmrs.module.Module;
import org.openmrs.module.ModuleFactory;
import org.openmrs.module.actcore.ActCoreActivator;
import org.openmrs.module.actcore.clinics.UserClinics;
import org.openmrs.module.reporting.dataset.DataSet;
import org.openmrs.module.reporting.dataset.DataSetRow;
import org.openmrs.module.reporting.dataset.definition.DataSetDefinition;
import org.openmrs.module.reporting.dataset.definition.SqlDataSetDefinition;
import org.openmrs.module.reporting.dataset.definition.service.DataSetDefinitionService;
import org.openmrs.module.reporting.evaluation.EvaluationContext;
import org.openmrs.module.reporting.evaluation.parameter.Mapped;
import org.openmrs.module.reporting.evaluation.parameter.Parameter;
import org.openmrs.module.reporting.report.ReportData;
import org.openmrs.module.reporting.report.definition.ReportDefinition;
import org.openmrs.module.reporting.report.ReportRequest;
import org.openmrs.module.reporting.report.definition.service.ReportDefinitionService;
import org.openmrs.module.reporting.report.renderer.CsvReportRenderer;
import org.openmrs.module.reporting.report.renderer.RenderingMode;
import org.openmrs.module.reporting.report.service.ReportService;
import org.openmrs.test.BaseModuleContextSensitiveTest;
import org.openmrs.util.PrivilegeConstants;

/**
 * Runs the advice as the activator installs it, as users holding only the privileges named.
 * Reporting 2.2.0 guards its services as the distribution runs it, so a denial or a pass is the
 * real one.
 */
public class ReportAccessContextTest extends BaseModuleContextSensitiveTest {
	
	private static final String ACT_REPORT = "aaaaaaaa-1111-4111-8111-000000000001";
	
	private static final String OTHER_REPORT = "aaaaaaaa-1111-4111-8111-000000000002";
	
	private static final String PRIVILEGE = "Task: act.report.test";
	
	private static final String CLINIC_A = "aaaaaaaa-0000-4000-8000-00000000000a";
	
	private static final String CLINIC_B = "bbbbbbbb-0000-4000-8000-00000000000b";
	
	private ReportDefinitionService reports;
	
	private User generic;
	
	private User holder;
	
	private User both;
	
	private User nobody;
	
	private User limitedAtA;
	
	private User limitedWithNoClinics;
	
	private User unlimitedAtA;
	
	private User limitedGenericAtA;
	
	@Before
	public void setUp() throws Exception {
		reports = Context.getService(ReportDefinitionService.class);
		saveReport(ACT_REPORT, "ACT test report", "persisted");
		saveReport(OTHER_REPORT, "Another module's report", "other");
		ReportPrivileges.register(ACT_REPORT, PRIVILEGE);
		generic = user("generic", null, PrivilegeConstants.GET_USERS, "View Reports", "Run Reports");
		holder = user("holder", null, PRIVILEGE);
		both = user("both", null, PRIVILEGE, "View Reports", "Run Reports");
		nobody = user("nobody", null, PrivilegeConstants.GET_USERS);
		limitedAtA = user("limitedA", CLINIC_A, PRIVILEGE, PrivilegeConstants.EDIT_USERS);
		limitedWithNoClinics = user("limitedNone", null, PRIVILEGE, PrivilegeConstants.EDIT_USERS);
		unlimitedAtA = user("unlimitedA", CLINIC_A, PRIVILEGE);
		limitedGenericAtA = user("limitedGenericA", CLINIC_A, PRIVILEGE, "View Reports", "Run Reports",
		    PrivilegeConstants.EDIT_USERS);
		Context.flushSession();
		ReportAccess.install();
	}
	
	@After
	public void tearDown() throws Exception {
		ReportAccess.uninstall();
		ReportPrivileges.clear();
		Context.logout();
		authenticate();
	}
	
	@Test
	public void lookup_shouldRefuseAGenericHolderWithoutThePerReportPrivilege() throws Exception {
		become(generic);
		
		refused(() -> reports.getDefinitionByUuid(ACT_REPORT));
	}
	
	@Test
	public void lookup_shouldAnswerAHolderOfOnlyThePerReportPrivilege() throws Exception {
		become(holder);
		
		assertEquals(ACT_REPORT, reports.getDefinitionByUuid(ACT_REPORT).getUuid());
	}
	
	@Test
	public void lookup_shouldLeaveAReportThatIsNotAnActReportToOpenMRS() throws Exception {
		become(generic);
		assertEquals(OTHER_REPORT, reports.getDefinitionByUuid(OTHER_REPORT).getUuid());
		
		become(holder);
		refused(() -> reports.getDefinitionByUuid(OTHER_REPORT));
	}
	
	@Test
	public void listing_shouldShowAHolderOnlyTheActReportsTheyMayRun() throws Exception {
		become(holder);
		
		assertThat(uuids(reports.getAllDefinitions(false)), contains(ACT_REPORT));
		
		become(generic);
		assertThat(uuids(reports.getAllDefinitions(false)), contains(OTHER_REPORT));
		
		become(both);
		assertThat(uuids(reports.getAllDefinitions(false)), hasItem(ACT_REPORT));
		assertThat(uuids(reports.getAllDefinitions(false)), hasItem(OTHER_REPORT));
	}
	
	@Test
	public void evaluate_shouldRunForAHolderWhoHoldsNeitherViewNorRunReports() throws Exception {
		become(holder);
		
		assertEquals("persisted", answer(evaluate(reports.getDefinitionByUuid(ACT_REPORT), null)));
	}
	
	@Test
	public void evaluate_shouldRefuseAUserWhoHoldsNeitherThePerReportPrivilegeNorTheGenericOnes() throws Exception {
		become(nobody);
		
		refused(() -> evaluate(definition(ACT_REPORT, "persisted"), null));
	}
	
	@Test
	public void evaluate_shouldRefuseAGenericHolderWithoutThePerReportPrivilege() throws Exception {
		become(generic);
		
		refused(() -> evaluate(definition(ACT_REPORT, "persisted"), null));
	}
	
	@Test
	public void evaluate_shouldRunThePersistedDefinitionNotAPostedOneWithTheSameUuid() throws Exception {
		become(holder);
		
		assertEquals("persisted", answer(evaluate(definition(ACT_REPORT, "posted"), null)));
	}
	
	@Test
	public void evaluate_shouldRunThePersistedDefinitionWhenAPostedOneIsMapped() throws Exception {
		become(holder);
		Mapped<ReportDefinition> posted = Mapped.mapStraightThrough(definition(ACT_REPORT, "posted"));
		
		ReportData data = reports.evaluate(posted, new EvaluationContext());
		
		assertEquals("persisted", answer(data));
	}
	
	@Test
	public void evaluate_shouldLeaveAnotherModulesReportToOpenMRS() throws Exception {
		become(holder);
		refused(() -> evaluate(definition(OTHER_REPORT, "other"), null));
		
		become(generic);
		assertEquals("other", answer(evaluate(definition(OTHER_REPORT, "other"), null)));
	}
	
	@Test
	public void evaluate_shouldHoldALimitedUserToTheirClinicsWhateverTheyAsk() throws Exception {
		become(limitedAtA);
		
		assertEquals(CLINIC_A, seen(evaluate(reports.getDefinitionByUuid(ACT_REPORT), null)));
		assertEquals(CLINIC_A, seen(evaluate(reports.getDefinitionByUuid(ACT_REPORT), CLINIC_B)));
		assertEquals(CLINIC_A, seen(evaluate(reports.getDefinitionByUuid(ACT_REPORT), "")));
	}
	
	@Test
	public void evaluate_shouldHoldALimitedUserToTheirClinicsWhenTheyMapAnotherAsALiteralOrAnExpression() throws Exception {
		become(limitedAtA);
		ReportDefinition report = reports.getDefinitionByUuid(ACT_REPORT);
		
		Map<String, Object> literal = new HashMap<String, Object>();
		literal.put("clinics", CLINIC_B);
		Map<String, Object> expression = new HashMap<String, Object>();
		expression.put("clinics", "${clinics}");
		EvaluationContext asksForB = new EvaluationContext();
		asksForB.addParameterValue("clinics", CLINIC_B);
		
		assertEquals(CLINIC_A,
		    seen(reports.evaluate(new Mapped<ReportDefinition>(report, literal), new EvaluationContext())));
		assertEquals(CLINIC_A, seen(reports.evaluate(new Mapped<ReportDefinition>(report, expression), asksForB)));
	}
	
	@Test
	public void evaluate_shouldGiveALimitedUserWithNoClinicsNothingRatherThanEveryClinic() throws Exception {
		become(limitedWithNoClinics);
		
		String seen = seen(evaluate(reports.getDefinitionByUuid(ACT_REPORT), null));
		
		assertEquals(ReportAccess.NO_CLINIC, seen);
		assertTrue("an empty value would mean every clinic", !seen.isEmpty());
	}
	
	@Test
	public void evaluate_shouldLeaveTheClinicsOfAnUnlimitedUserAsTheyAsk() throws Exception {
		become(unlimitedAtA);
		
		assertNull(seen(evaluate(reports.getDefinitionByUuid(ACT_REPORT), null)));
		assertEquals(CLINIC_B, seen(evaluate(reports.getDefinitionByUuid(ACT_REPORT), CLINIC_B)));
	}
	
	@Test
	public void evaluate_shouldHoldALimitedUsersDatasetToTheirClinicsToo() throws Exception {
		become(limitedGenericAtA);
		EvaluationContext asksForB = new EvaluationContext();
		asksForB.addParameterValue("clinics", CLINIC_B);
		
		DataSet data = Context.getService(DataSetDefinitionService.class).evaluate(dataset(), asksForB);
		
		assertEquals(CLINIC_A, String.valueOf(data.iterator().next().getColumnValue("seen")));
	}
	
	@Test
	public void evaluate_shouldNotForceClinicsOnADatasetThatDeclaresNoSuchParameter() throws Exception {
		become(limitedGenericAtA);
		SqlDataSetDefinition plain = new SqlDataSetDefinition();
		plain.setSqlQuery("select 'x' as seen");
		
		DataSet data = Context.getService(DataSetDefinitionService.class).evaluate(plain, new EvaluationContext());
		
		assertEquals("x", String.valueOf(data.iterator().next().getColumnValue("seen")));
	}
	
	@Test
	public void evaluate_shouldNotChangeASuperUsersOrADaemonThreadsReports() throws Exception {
		assertEquals(CLINIC_B, seen(evaluate(reports.getDefinitionByUuid(ACT_REPORT), CLINIC_B)));
		
		ReportDefinition stored = reports.getDefinitionByUuid(ACT_REPORT);
		AtomicReference<String> inDaemon = new AtomicReference<String>();
		AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
		become(limitedAtA);
		Daemon.runInDaemonThreadAndWait(() -> {
			try {
				inDaemon.set(seen(evaluate(stored, CLINIC_B)));
			}
			catch (Throwable t) {
				failure.set(t);
			}
		}, daemonToken());
		
		if (failure.get() != null) {
			failure.get().printStackTrace();
		}
		assertNull(String.valueOf(failure.get()), failure.get());
		assertEquals(CLINIC_B, inDaemon.get());
	}
	
	@Test
	public void install_shouldPutTheAdviceInFrontOnceHoweverOftenItIsAsked() throws Exception {
		ReportAccess.uninstall();
		
		int first = ReportAccess.install();
		int again = ReportAccess.install();
		
		assertTrue(first > 0);
		assertEquals(0, again);
		assertTrue(ReportAccess.isInstalledInFront());
	}
	
	@Test
	public void descriptors_shouldNameThePrivilegeUnderConfigAndGuardOneThatNamesNone() throws Exception {
		org.openmrs.module.reporting.config.ReportDescriptor named = new org.openmrs.module.reporting.config.ReportDescriptor();
		Map<String, Object> config = new HashMap<String, Object>();
		config.put(ReportPrivileges.CONFIG_KEY, " Task: act.report.waitingList ");
		named.setConfig(config);
		org.openmrs.module.reporting.config.ReportDescriptor unnamed = new org.openmrs.module.reporting.config.ReportDescriptor();
		
		assertEquals("Task: act.report.waitingList", ReportPrivileges.privilegeOf(named));
		assertEquals(ReportPrivileges.UNASSIGNED, ReportPrivileges.privilegeOf(unnamed));
	}
	
	@Test
	public void queueReport_shouldRefuseAGenericHolderWithoutThePerReportPrivilege() throws Exception {
		become(generic);
		
		refused(() -> Context.getService(ReportService.class).queueReport(request(definition(ACT_REPORT, "persisted"))));
	}
	
	@Test
	public void queueReport_shouldStoreALimitedUsersClinicsOnTheRequestSoTheLaterRunKeepsThem() throws Exception {
		become(limitedGenericAtA);
		ReportDefinition report = reports.getDefinitionByUuid(ACT_REPORT);
		Map<String, Object> asksForB = new HashMap<String, Object>();
		asksForB.put("clinics", CLINIC_B);
		ReportRequest asked = request(new Mapped<ReportDefinition>(report, asksForB));
		
		ReportRequest queued = Context.getService(ReportService.class).queueReport(asked);
		
		ReportRequest stored = Context.getService(ReportService.class).getReportRequestByUuid(queued.getUuid());
		assertEquals(CLINIC_A, stored.getReportDefinition().getParameterMappings().get("clinics"));
	}
	
	@Test
	public void queueReport_shouldLeaveAnUnlimitedUsersMappingAsAsked() throws Exception {
		become(both);
		ReportDefinition report = reports.getDefinitionByUuid(ACT_REPORT);
		Map<String, Object> asksForB = new HashMap<String, Object>();
		asksForB.put("clinics", CLINIC_B);
		
		ReportRequest queued = Context.getService(ReportService.class)
		        .queueReport(request(new Mapped<ReportDefinition>(report, asksForB)));
		
		assertEquals(CLINIC_B, queued.getReportDefinition().getParameterMappings().get("clinics"));
	}
	
	@Test
	public void download_shouldRefuseAGenericHolderTheOutputOfAnActReportRun() throws Exception {
		become(both);
		ReportRequest queued = Context.getService(ReportService.class)
		        .queueReport(request(Mapped.mapStraightThrough(reports.getDefinitionByUuid(ACT_REPORT))));
		
		become(generic);
		
		refused(() -> Context.getService(ReportService.class).getReportRequestByUuid(queued.getUuid()));
	}
	
	@Test
	public void contextRefreshed_shouldPutTheAdviceBackInFrontOfAServiceRebuiltWithoutIt() throws Exception {
		ReportAccess.uninstall();
		assertTrue(!ReportAccess.isInstalledInFront());
		
		new ActCoreActivator().contextRefreshed();
		
		assertTrue(ReportAccess.isInstalledInFront());
		become(generic);
		refused(() -> reports.getDefinitionByUuid(ACT_REPORT));
	}
	
	@Test
	public void load_shouldReadThePrivilegeFromAnActualDescriptorThroughTheReportingLoader() throws Exception {
		File dir = new File(org.openmrs.module.reporting.config.ReportLoader.getReportingDescriptorsConfigurationDir());
		dir.mkdirs();
		File named = new File(dir, "act_test_named.yml");
		File unnamed = new File(dir, "act_test_unnamed.yml");
		try {
			Files.write(named.toPath(),
			    ("key: actTestNamed\nuuid: \"aaaaaaaa-2222-4222-8222-000000000001\"\nname: \"Named\"\n"
			            + "description: \"d\"\nconfig:\n  privilege: \"Task: act.report.named\"\n")
			                    .getBytes(StandardCharsets.UTF_8));
			Files.write(unnamed.toPath(), ("key: actTestUnnamed\nuuid: \"aaaaaaaa-2222-4222-8222-000000000002\"\n"
			        + "name: \"Unnamed\"\ndescription: \"d\"\n").getBytes(StandardCharsets.UTF_8));
			
			ReportPrivileges.load();
			
			assertEquals("Task: act.report.named", ReportPrivileges.of("aaaaaaaa-2222-4222-8222-000000000001"));
			assertEquals(ReportPrivileges.UNASSIGNED, ReportPrivileges.of("aaaaaaaa-2222-4222-8222-000000000002"));
		}
		finally {
			named.delete();
			unnamed.delete();
		}
	}
	
	private static ReportRequest request(ReportDefinition report) {
		return request(Mapped.mapStraightThrough(report));
	}
	
	private static ReportRequest request(Mapped<ReportDefinition> report) {
		return new ReportRequest(report, null, new RenderingMode(new CsvReportRenderer(), "CSV", "csv", 100),
		        ReportRequest.Priority.NORMAL, null);
	}
	
	/** Signs the superuser back in first, as core lets only a superuser become another user. */
	private void become(User user) {
		Context.logout();
		authenticate();
		Context.becomeUser(user.getSystemId());
	}
	
	private static DaemonToken daemonToken() throws Exception {
		Module module = new Module("actcore");
		module.setModuleId("actcore");
		module.setModuleActivator(new ActCoreActivator());
		Method pass = ModuleFactory.class.getDeclaredMethod("passDaemonToken", Module.class);
		pass.setAccessible(true);
		pass.invoke(null, module);
		return ActCoreActivator.getDaemonToken();
	}
	
	private ReportData evaluate(ReportDefinition report, String clinics) throws Exception {
		EvaluationContext context = new EvaluationContext();
		if (clinics != null) {
			context.addParameterValue("clinics", clinics);
		}
		return reports.evaluate(report, context);
	}
	
	private static String column(ReportData data, String name) {
		DataSetRow row = data.getDataSets().get("data").iterator().next();
		Object value = row.getColumnValue(name);
		return value == null ? null : String.valueOf(value);
	}
	
	/** The clinics the report's query was given. */
	private static String seen(ReportData data) {
		return column(data, "seen");
	}
	
	/** Which definition's query ran. */
	private static String answer(ReportData data) {
		return column(data, "answer");
	}
	
	private static ReportDefinition definition(String uuid, String answer) {
		ReportDefinition report = new ReportDefinition();
		report.setUuid(uuid);
		report.setName("Report " + uuid);
		report.addParameter(new Parameter("clinics", "Clinics", String.class));
		SqlDataSetDefinition sql = new SqlDataSetDefinition();
		sql.addParameter(new Parameter("clinics", "Clinics", String.class));
		sql.setSqlQuery("select '" + answer + "' as answer, :clinics as seen");
		report.addDataSetDefinition("data", Mapped.mapStraightThrough(sql));
		return report;
	}
	
	private void saveReport(String uuid, String name, String answer) {
		ReportDefinition report = definition(uuid, answer);
		report.setName(name);
		reports.saveDefinition(report);
	}
	
	private static DataSetDefinition dataset() {
		SqlDataSetDefinition sql = new SqlDataSetDefinition();
		sql.addParameter(new Parameter("clinics", "Clinics", String.class));
		sql.setSqlQuery("select :clinics as seen");
		return sql;
	}
	
	private static List<String> uuids(List<ReportDefinition> definitions) {
		List<String> uuids = new ArrayList<String>();
		for (ReportDefinition definition : definitions) {
			if (ACT_REPORT.equals(definition.getUuid()) || OTHER_REPORT.equals(definition.getUuid())) {
				uuids.add(definition.getUuid());
			}
		}
		return uuids;
	}
	
	private interface Call {
		
		void run() throws Exception;
	}
	
	private static void refused(Call call) throws Exception {
		try {
			call.run();
			fail("expected a refusal");
		}
		catch (APIAuthenticationException expected) {
			assertNotNull(expected.getMessage());
		}
	}
	
	private User user(String username, String clinics, String... privileges) {
		UserService users = Context.getUserService();
		Role role = new Role("Role " + username);
		role.setDescription(username);
		for (String name : privileges) {
			Privilege privilege = users.getPrivilege(name);
			if (privilege == null) {
				privilege = users.savePrivilege(new Privilege(name, name));
			}
			role.addPrivilege(privilege);
		}
		users.saveRole(role);
		Person person = new Person();
		person.addName(new PersonName(username, null, "Tester"));
		person.setGender("F");
		User user = new User(person);
		user.setUsername(username);
		user.addRole(role);
		if (clinics != null) {
			user.setUserProperty(UserClinics.PROPERTY, clinics);
		}
		return users.createUser(user, "Password123");
	}
}
