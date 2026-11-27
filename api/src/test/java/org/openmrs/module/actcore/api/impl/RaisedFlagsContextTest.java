/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.api.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import org.aopalliance.intercept.MethodInterceptor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openmrs.GlobalProperty;
import org.openmrs.Patient;
import org.openmrs.Person;
import org.openmrs.PersonName;
import org.openmrs.Privilege;
import org.openmrs.Role;
import org.openmrs.User;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.APIException;
import org.openmrs.api.UserService;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.ActCoreConstants;
import org.openmrs.module.actcore.ActCorePrivileges;
import org.openmrs.module.actcore.ModuleTables;
import org.openmrs.module.actcore.aop.HideDismissedFlags;
import org.openmrs.module.actcore.api.AuditLogService;
import org.openmrs.module.actcore.api.RaisedFlagService;
import org.openmrs.module.actcore.api.search.ActivitySearchCriteria;
import org.openmrs.module.actcore.model.ActivityPage;
import org.openmrs.module.actcore.model.DismissalOutcome;
import org.openmrs.module.cohort.CohortType;
import org.openmrs.module.cohort.api.CohortTypeService;
import org.openmrs.module.patientflags.Flag;
import org.openmrs.module.patientflags.PatientFlag;
import org.openmrs.module.patientflags.PatientFlagsConstants;
import org.openmrs.module.patientflags.Tag;
import org.openmrs.module.patientflags.api.FlagService;
import org.openmrs.module.patientflags.evaluator.GroovyFlagEvaluator;
import org.openmrs.module.patientflags.evaluator.SQLFlagEvaluator;
import org.openmrs.test.BaseModuleContextSensitiveTest;

public class RaisedFlagsContextTest extends BaseModuleContextSensitiveTest {
	
	private static final int PATIENT = 7;
	
	private static final String SWITCH = "test.flagMatches";
	
	// Matches the patient while the switch is on; aliased, as patientflags evaluates one patient by
	// appending a condition on the first *.patient_id.
	private static final String MATCHES_WHILE_ON = "select p.patient_id from patient p where p.patient_id = " + PATIENT
	        + " and exists (select 1 from global_property g where g.property = '" + SWITCH
	        + "' and g.property_value = 'on')";
	
	private final HideDismissedFlags hide = new HideDismissedFlags();
	
	private FlagService flagService;
	
	private Flag flag;
	
	private Runnable restoreService;
	
	@Before
	public void setUp() {
		ModuleTables.create();
		Context.getAdministrationService()
		        .saveGlobalProperty(new GlobalProperty(ActCoreConstants.GP_DISMISS_FLAG_TAG, "Critical data"));
		flagService = Context.getService(FlagService.class);
		CohortType type = new CohortType();
		type.setName("System List");
		type.setDescription("System List");
		Context.getService(CohortTypeService.class).saveCohortType(type);
		Tag critical = new Tag("Critical data");
		flagService.saveTag(critical);
		flag = saveFlag("missing data", critical);
		Context.addAdvice(FlagService.class, hide);
		restoreService = RaisedFlagServiceInTest.recordInTestTransaction();
	}
	
	@After
	public void removeAdvice() {
		try {
			if (restoreService != null) {
				restoreService.run();
			}
		}
		finally {
			Context.removeAdvice(FlagService.class, hide);
		}
	}
	
	@Test
	public void raisesAFlagAgainAfterLoweringItInTheSameSession() {
		raisedFlags().raise(flag.getFlagId(), PATIENT);
		raisedFlags().raise(flag.getFlagId(), PATIENT);
		raisedFlags().lower(flag.getFlagId(), PATIENT);
		
		raisedFlags().raise(flag.getFlagId(), PATIENT);
		
		assertTrue(raisedFlags().getRaisedPatients(flag.getFlagId()).contains(PATIENT));
	}
	
	@Test
	public void forgetsThePatientsAFlagWasRaisedForWhenTheFlagIsPurgedButKeepsItsDismissals() {
		execute("insert into " + ActCoreConstants.RAISED_FLAG_TABLE + " (patient_id, flag_id, raised_since) values ("
		        + PATIENT + ", " + flag.getFlagId() + ", '2026-01-05 10:00:00')");
		execute("insert into " + ActCoreConstants.FLAG_DISMISSAL_TABLE
		        + " (uuid, patient_id, flag_id, dismissed_by, date_dismissed)" + " values ('" + UUID.randomUUID() + "', "
		        + PATIENT + ", " + flag.getFlagId() + ", 1, '2026-01-06 10:00:00')");
		
		flagService.purgeFlag(flag.getFlagId());
		
		assertEquals(0L,
		    count("select count(*) from " + ActCoreConstants.RAISED_FLAG_TABLE + " where flag_id = " + flag.getFlagId()));
		assertEquals(1L,
		    count("select count(*) from " + ActCoreConstants.FLAG_DISMISSAL_TABLE + " where flag_id = " + flag.getFlagId()));
	}
	
	@Test
	public void keepsTheDateAFlagWasRaisedWhenPatientflagsRewritesItsRow() {
		matching(true);
		runFullRefresh();
		execute("update " + ActCoreConstants.RAISED_FLAG_TABLE + " set raised_since = '2026-01-05 10:00:00'");
		
		regenerateAsPatientflagsDoes();
		refreshPatient();
		runFullRefresh();
		
		assertEquals("2026-01-05", raisedSince());
	}
	
	@Test
	public void startsAPatientsListMembershipOnTheDayTheFlagWasRaised() {
		matching(true);
		runFullRefresh();
		execute("update " + ActCoreConstants.RAISED_FLAG_TABLE + " set raised_since = '2026-01-05 10:00:00'");
		execute("delete from cohort_member");
		
		runFullRefresh();
		
		Object listStart = single(
		    "select cast(m.start_date as date) from cohort_member m join cohort c on c.cohort_id = m.cohort_id"
		            + " where c.uuid = '" + flag.getUuid() + "' and m.patient_id = " + PATIENT + " and m.end_date is null");
		assertEquals("2026-01-05", listStart.toString());
	}
	
	@Test
	public void forgetsTheDateOnceTheFlagStopsMatchingAndDatesARecurrenceAfresh() {
		matching(true);
		runFullRefresh();
		execute("update " + ActCoreConstants.RAISED_FLAG_TABLE + " set raised_since = '2026-01-05 10:00:00'");
		
		matching(false);
		refreshPatient();
		assertEquals(0, rows());
		
		matching(true);
		refreshPatient();
		assertEquals(1, rows());
		assertTrue(!"2026-01-05".equals(raisedSince()));
	}
	
	@Test
	public void keepsADismissedFlagHiddenAndOffItsListThroughPatientflagsRewritesAndBothRefreshes() {
		matching(true);
		runFullRefresh();
		assertEquals(1, shownFlags());
		assertEquals(1, listMembers());
		
		assertEquals(DismissalOutcome.DISMISSED, dismiss());
		assertEquals(0, shownFlags());
		assertEquals(0, listMembers());
		
		regenerateAsPatientflagsDoes();
		assertEquals(0, shownFlags());
		refreshPatient();
		assertEquals(0, shownFlags());
		assertEquals(0, listMembers());
		runFullRefresh();
		assertEquals(0, shownFlags());
		assertEquals(0, listMembers());
		// The row itself stays, as the flag still matches.
		assertEquals(1L, count("select count(*) from patientflags_patient_flag where voided = false and flag_id = "
		        + flag.getFlagId() + " and patient_id = " + PATIENT));
	}
	
	@Test
	public void showsTheFlagAgainWhenItRecursAfterNoLongerMatching() {
		matching(true);
		runFullRefresh();
		dismiss();
		
		matching(false);
		runFullRefresh();
		matching(true);
		refreshPatient();
		
		assertEquals(1, shownFlags());
		assertEquals(1, listMembers());
		assertEquals(0L,
		    count("select count(*) from " + ActCoreConstants.RAISED_FLAG_TABLE + " where date_dismissed is not null"));
	}
	
	@Test
	public void dismissesAFlagRaisedBeforeACoreRefreshDatedIt() {
		matching(true);
		flagService.savePatientFlag(new PatientFlag(patient(), flag, flag.getMessage()));
		
		assertEquals(DismissalOutcome.DISMISSED, dismiss());
		
		assertEquals(0, shownFlags());
		assertEquals(1, rows());
	}
	
	@Test
	public void leavesTheOtherFlagsOfThePatientShowing() {
		matching(true);
		Flag other = saveFlag("other", null);
		runFullRefresh();
		dismiss();
		
		List<PatientFlag> shown = flagService.getPatientFlags(patient());
		
		assertEquals(1, shown.size());
		assertEquals(other.getUuid(), shown.get(0).getFlag().getUuid());
	}
	
	@Test
	public void leavesADismissedFlagOutOfTheFlagsReadForThePatientButNotOfTheirEvaluation() {
		matching(true);
		Flag other = saveFlag("other", null);
		runFullRefresh();
		dismiss();
		
		List<Flag> read = flagService.getFlagsForPatient(patient());
		List<Flag> evaluated = flagService.generateFlagsForPatient(patient(), new HashMap<Object, Object>());
		
		assertEquals(1, read.size());
		assertEquals(other.getUuid(), read.get(0).getUuid());
		assertEquals(2, evaluated.size());
	}
	
	@Test
	public void hidesADismissedFlagWithoutCallingFlagServiceAgain() {
		matching(true);
		runFullRefresh();
		dismiss();
		Patient patient = patient();
		List<String> calls = new ArrayList<String>();
		MethodInterceptor counting = invocation -> {
			calls.add(invocation.getMethod().getName());
			return invocation.proceed();
		};
		Context.addAdvice(FlagService.class, counting);
		try {
			assertEquals(0, flagService.getPatientFlags(patient).size());
		}
		finally {
			Context.removeAdvice(FlagService.class, counting);
		}
		
		assertEquals(Collections.singletonList("getPatientFlags"), calls);
	}
	
	@Test
	public void refusesADismissalWhileTheFullRefreshHoldsTheRows() throws InterruptedException {
		matching(true);
		runFullRefresh();
		final CountDownLatch held = new CountDownLatch(1);
		final CountDownLatch done = new CountDownLatch(1);
		Thread refresh = new Thread(new Runnable() {
			
			@Override
			public void run() {
				PatientFlagRefresh.ROWS.writeLock().lock();
				held.countDown();
				try {
					done.await();
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				finally {
					PatientFlagRefresh.ROWS.writeLock().unlock();
				}
			}
		});
		refresh.start();
		held.await();
		DismissalOutcome refused;
		try {
			refused = dismiss();
		}
		finally {
			done.countDown();
			refresh.join();
		}
		
		assertEquals(DismissalOutcome.REFRESH_RUNNING, refused);
		assertEquals(0L,
		    count("select count(*) from " + ActCoreConstants.RAISED_FLAG_TABLE + " where date_dismissed is not null"));
		assertEquals(1, shownFlags());
		assertEquals(1, listMembers());
		assertEquals(DismissalOutcome.DISMISSED, dismiss());
		assertEquals(0, PatientFlagRefresh.ROWS.getReadLockCount());
	}
	
	@Test(timeout = 60000)
	public void givesUpADismissalWhoseGroovyFlagDoesNotEndAndLetsGoOfTheRows() {
		Flag hung = flagService.getFlag(flag.getFlagId());
		hung.setEvaluator(GroovyFlagEvaluator.class.getName());
		hung.setCriteria("Thread.sleep(5000); return testCohort");
		flagService.saveFlag(hung);
		long bound = BoundedFlagEvaluation.waitMillis;
		BoundedFlagEvaluation.waitMillis = 300;
		long startedAt = System.currentTimeMillis();
		try {
			dismiss();
			fail("The dismissal did not give up the evaluation");
		}
		catch (APIException e) {
			assertTrue(e.getMessage(), e.getMessage().startsWith("Gave up evaluating flag 'missing data'"));
		}
		finally {
			BoundedFlagEvaluation.waitMillis = bound;
		}
		
		assertTrue(System.currentTimeMillis() - startedAt < 5000);
		assertEquals(0, PatientFlagRefresh.ROWS.getReadLockCount());
		assertEquals(0, rows());
	}
	
	@Test
	public void refusesAFlagWithoutTheDismissTag() {
		matching(true);
		Flag untagged = saveFlag("risk", null);
		runFullRefresh();
		
		assertEquals(DismissalOutcome.NOT_DISMISSIBLE, raisedFlags().dismiss(patient(), untagged));
		assertTrue(!raisedFlags().mayDismiss(untagged));
	}
	
	@Test
	public void letsAnyFlagBeDismissedWhenTheTagPropertyIsEmpty() {
		Context.getAdministrationService().saveGlobalProperty(new GlobalProperty(ActCoreConstants.GP_DISMISS_FLAG_TAG, ""));
		matching(true);
		Flag untagged = saveFlag("risk", null);
		runFullRefresh();
		
		assertEquals(DismissalOutcome.DISMISSED, raisedFlags().dismiss(patient(), untagged));
	}
	
	@Test
	public void keepsEachDismissalInTheActivityLogAfterTheFlagLowers() {
		matching(true);
		runFullRefresh();
		dismiss();
		matching(false);
		runFullRefresh();
		matching(true);
		refreshPatient();
		dismiss();
		Flag disabled = flagService.getFlag(flag.getFlagId());
		disabled.setEnabled(false);
		flagService.saveFlag(disabled);
		runFullRefresh();
		
		ActivitySearchCriteria filter = new ActivitySearchCriteria();
		filter.setKind("flag");
		ActivityPage page = Context.getService(AuditLogService.class).getActivity(filter);
		
		assertEquals(0, rows());
		assertEquals(2, page.getTotalCount());
		assertEquals("missing data", page.getResults().get(1).getSummary());
		assertEquals("Dismissed flag", page.getResults().get(1).getAction());
	}
	
	@Test
	public void refusesAFlagThePatientDoesNotMatch() {
		matching(false);
		
		assertEquals(DismissalOutcome.NOT_RAISED, dismiss());
		assertEquals(0, rows());
	}
	
	@Test
	public void recordsWhoDismissedTheFlagForAUserWithThePrivilege() {
		matching(true);
		runFullRefresh();
		Patient patient = patient();
		User user = authenticateWith(ActCorePrivileges.DISMISS_FLAGS, PatientFlagsConstants.PRIV_VIEW_PATIENT_FLAGS);
		
		assertTrue(raisedFlags().mayDismiss(flag));
		assertEquals(DismissalOutcome.DISMISSED, raisedFlags().dismiss(patient, flag));
		
		Context.logout();
		authenticate();
		assertEquals(user.getUserId().intValue(),
		    ((Number) single("select dismissed_by from " + ActCoreConstants.RAISED_FLAG_TABLE)).intValue());
	}
	
	@Test
	public void refusesAUserWithoutThePrivilege() {
		matching(true);
		runFullRefresh();
		Patient patient = patient();
		authenticateWith(PatientFlagsConstants.PRIV_VIEW_PATIENT_FLAGS);
		
		assertTrue(!raisedFlags().mayDismiss(flag));
		try {
			raisedFlags().dismiss(patient, flag);
			fail("expected the dismissal to be refused");
		}
		catch (APIAuthenticationException e) {
			assertTrue(e.getMessage().contains(ActCorePrivileges.DISMISS_FLAGS));
		}
	}
	
	private static RaisedFlagService raisedFlags() {
		return Context.getService(RaisedFlagService.class);
	}
	
	private DismissalOutcome dismiss() {
		DismissalOutcome dismissal = raisedFlags().dismiss(patient(), flagService.getFlag(flag.getFlagId()));
		Context.flushSession();
		return dismissal;
	}
	
	/**
	 * What patientflags' own thread does on an encounter save: every live row deleted, then rewritten.
	 */
	private void regenerateAsPatientflagsDoes() {
		flagService.deletePatientFlagsForPatient(patient());
		flagService.savePatientFlag(new PatientFlag(patient(), flag, flag.getMessage()));
		Context.flushSession();
	}
	
	private void runFullRefresh() {
		Context.flushSession();
		PatientFlagRefreshInTest.runInTestTransaction();
	}
	
	private void refreshPatient() {
		Context.flushSession();
		PatientFlagRefreshInTest.refreshPatient(PATIENT);
	}
	
	private void matching(boolean on) {
		Context.getAdministrationService().saveGlobalProperty(new GlobalProperty(SWITCH, on ? "on" : "off"));
		Context.flushSession();
	}
	
	private Flag saveFlag(String name, Tag tag) {
		Flag saved = new Flag();
		saved.setUuid(UUID.randomUUID().toString());
		saved.setName(name);
		saved.setCriteria(MATCHES_WHILE_ON);
		saved.setEvaluator(SQLFlagEvaluator.class.getName());
		saved.setMessage(name);
		saved.setEnabled(Boolean.TRUE);
		if (tag != null) {
			saved.setTags(new HashSet<Tag>(Collections.singleton(tag)));
		}
		flagService.saveFlag(saved);
		return saved;
	}
	
	private Patient patient() {
		return Context.getPatientService().getPatient(PATIENT);
	}
	
	private int shownFlags() {
		int shown = 0;
		for (PatientFlag row : flagService.getPatientFlags(patient())) {
			if (!row.getVoided() && flag.getUuid().equals(row.getFlag().getUuid())) {
				shown++;
			}
		}
		return shown;
	}
	
	private long listMembers() {
		Number members = (Number) single(
		    "select count(*) from cohort_member m join cohort c on c.cohort_id = m.cohort_id" + " where c.uuid = '"
		            + flag.getUuid() + "' and m.patient_id = " + PATIENT + " and m.end_date is null and m.voided = false");
		return members.longValue();
	}
	
	private long rows() {
		Number count = (Number) single(
		    "select count(*) from " + ActCoreConstants.RAISED_FLAG_TABLE + " where flag_id = " + flag.getFlagId());
		return count.longValue();
	}
	
	private String raisedSince() {
		return single("select cast(raised_since as date) from " + ActCoreConstants.RAISED_FLAG_TABLE + " where flag_id = "
		        + flag.getFlagId()).toString();
	}
	
	private User authenticateWith(String... privileges) {
		UserService users = Context.getUserService();
		Role role = new Role("dismisser " + UUID.randomUUID());
		for (String name : privileges) {
			Privilege privilege = users.getPrivilege(name);
			if (privilege == null) {
				privilege = users.savePrivilege(new Privilege(name, name));
			}
			role.addPrivilege(privilege);
		}
		users.saveRole(role);
		Person person = new Person();
		person.addName(new PersonName("Flag", null, "Dismisser"));
		person.setGender("F");
		User user = new User(person);
		user.setUsername("dismisser");
		user.addRole(role);
		users.createUser(user, "Dismisser123");
		Context.flushSession();
		Context.logout();
		Context.authenticate("dismisser", "Dismisser123");
		return user;
	}
	
	private void execute(String sql) {
		Context.flushSession();
		Context.getAdministrationService().executeSQL(sql, false);
	}
	
	private long count(String sql) {
		return ((Number) single(sql)).longValue();
	}
	
	private Object single(String sql) {
		Context.flushSession();
		List<List<Object>> rows = new ArrayList<List<Object>>(Context.getAdministrationService().executeSQL(sql, true));
		assertEquals(sql, 1, rows.size());
		return rows.get(0).get(0);
	}
}
