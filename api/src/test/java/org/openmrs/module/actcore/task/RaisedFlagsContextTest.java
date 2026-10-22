/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.task;

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
import org.openmrs.api.UserService;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.logs.ActivityLog;
import org.openmrs.module.cohort.CohortType;
import org.openmrs.module.cohort.api.CohortTypeService;
import org.openmrs.module.patientflags.Flag;
import org.openmrs.module.patientflags.PatientFlag;
import org.openmrs.module.patientflags.PatientFlagsConstants;
import org.openmrs.module.patientflags.Tag;
import org.openmrs.module.patientflags.api.FlagService;
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
	
	@Before
	public void setUp() {
		RaisedFlagsTable.create();
		Context.getAdministrationService()
		        .saveGlobalProperty(new GlobalProperty(RaisedFlags.DISMISS_TAG_PROPERTY, "Critical data"));
		flagService = Context.getService(FlagService.class);
		CohortType type = new CohortType();
		type.setName("System List");
		type.setDescription("System List");
		Context.getService(CohortTypeService.class).saveCohortType(type);
		Tag critical = new Tag("Critical data");
		flagService.saveTag(critical);
		flag = saveFlag("missing data", critical);
		Context.addAdvice(FlagService.class, hide);
	}
	
	@After
	public void removeAdvice() {
		Context.removeAdvice(FlagService.class, hide);
	}
	
	@Test
	public void keepsTheDateAFlagWasRaisedWhenPatientflagsRewritesItsRow() {
		matching(true);
		runFullRefresh();
		execute("update " + RaisedFlags.TABLE + " set raised_since = '2026-01-05 10:00:00'");
		
		regenerateAsPatientflagsDoes();
		refreshPatient();
		runFullRefresh();
		
		assertEquals("2026-01-05", raisedSince());
	}
	
	@Test
	public void startsAPatientsListMembershipOnTheDayTheFlagWasRaised() {
		matching(true);
		runFullRefresh();
		execute("update " + RaisedFlags.TABLE + " set raised_since = '2026-01-05 10:00:00'");
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
		execute("update " + RaisedFlags.TABLE + " set raised_since = '2026-01-05 10:00:00'");
		
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
		
		assertEquals(RaisedFlags.Dismissal.DISMISSED, dismiss());
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
		assertEquals(0L, count("select count(*) from " + RaisedFlags.TABLE + " where date_dismissed is not null"));
	}
	
	@Test
	public void dismissesAFlagRaisedBeforeACoreRefreshDatedIt() {
		matching(true);
		flagService.savePatientFlag(new PatientFlag(patient(), flag, flag.getMessage()));
		
		assertEquals(RaisedFlags.Dismissal.DISMISSED, dismiss());
		
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
	public void refusesADismissalWhileTheFullRefreshHoldsTheRows() throws InterruptedException {
		matching(true);
		runFullRefresh();
		final CountDownLatch held = new CountDownLatch(1);
		final CountDownLatch done = new CountDownLatch(1);
		Thread refresh = new Thread(new Runnable() {
			
			@Override
			public void run() {
				PatientFlagRefreshTask.ROWS.writeLock().lock();
				held.countDown();
				try {
					done.await();
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				finally {
					PatientFlagRefreshTask.ROWS.writeLock().unlock();
				}
			}
		});
		refresh.start();
		held.await();
		RaisedFlags.Dismissal refused;
		try {
			refused = dismiss();
		}
		finally {
			done.countDown();
			refresh.join();
		}
		
		assertEquals(RaisedFlags.Dismissal.REFRESH_RUNNING, refused);
		assertEquals(0L, count("select count(*) from " + RaisedFlags.TABLE + " where date_dismissed is not null"));
		assertEquals(1, shownFlags());
		assertEquals(1, listMembers());
		assertEquals(RaisedFlags.Dismissal.DISMISSED, dismiss());
		assertEquals(0, PatientFlagRefreshTask.ROWS.getReadLockCount());
	}
	
	@Test
	public void refusesAFlagWithoutTheDismissTag() {
		matching(true);
		Flag untagged = saveFlag("risk", null);
		runFullRefresh();
		
		assertEquals(RaisedFlags.Dismissal.NOT_DISMISSIBLE, RaisedFlags.dismiss(patient(), untagged));
		assertTrue(!RaisedFlags.mayDismiss(untagged));
	}
	
	@Test
	public void letsAnyFlagBeDismissedWhenTheTagPropertyIsEmpty() {
		Context.getAdministrationService().saveGlobalProperty(new GlobalProperty(RaisedFlags.DISMISS_TAG_PROPERTY, ""));
		matching(true);
		Flag untagged = saveFlag("risk", null);
		runFullRefresh();
		
		assertEquals(RaisedFlags.Dismissal.DISMISSED, RaisedFlags.dismiss(patient(), untagged));
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
		
		ActivityLog.Filter filter = new ActivityLog.Filter();
		filter.kind = "flag";
		ActivityLog.Page page = new ActivityLog().list(filter);
		
		assertEquals(0, rows());
		assertEquals(2, page.totalCount);
		assertEquals("missing data", page.results.get(1).summary);
		assertEquals("Dismissed flag", page.results.get(1).action);
	}
	
	@Test
	public void refusesAFlagThePatientDoesNotMatch() {
		matching(false);
		
		assertEquals(RaisedFlags.Dismissal.NOT_RAISED, dismiss());
		assertEquals(0, rows());
	}
	
	@Test
	public void recordsWhoDismissedTheFlagForAUserWithThePrivilege() {
		matching(true);
		runFullRefresh();
		Patient patient = patient();
		User user = authenticateWith(RaisedFlags.DISMISS_PRIVILEGE, PatientFlagsConstants.PRIV_VIEW_PATIENT_FLAGS);
		
		assertTrue(RaisedFlags.mayDismiss(flag));
		assertEquals(RaisedFlags.Dismissal.DISMISSED, RaisedFlags.dismiss(patient, flag));
		
		Context.logout();
		authenticate();
		assertEquals(user.getUserId().intValue(),
		    ((Number) single("select dismissed_by from " + RaisedFlags.TABLE)).intValue());
	}
	
	@Test
	public void refusesAUserWithoutThePrivilege() {
		matching(true);
		runFullRefresh();
		Patient patient = patient();
		authenticateWith(PatientFlagsConstants.PRIV_VIEW_PATIENT_FLAGS);
		
		assertTrue(!RaisedFlags.mayDismiss(flag));
		try {
			RaisedFlags.dismiss(patient, flag);
			fail("expected the dismissal to be refused");
		}
		catch (APIAuthenticationException e) {
			assertTrue(e.getMessage().contains(RaisedFlags.DISMISS_PRIVILEGE));
		}
	}
	
	private RaisedFlags.Dismissal dismiss() {
		RaisedFlags.Dismissal dismissal = RaisedFlags.dismiss(patient(), flagService.getFlag(flag.getFlagId()));
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
		new PatientFlagRefreshTask() {
			
			@Override
			void refreshAdherence() {
			}
		}.runIfIdle();
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
		return ((Number) single("select count(*) from " + RaisedFlags.TABLE + " where flag_id = " + flag.getFlagId()))
		        .longValue();
	}
	
	private String raisedSince() {
		return single("select cast(raised_since as date) from " + RaisedFlags.TABLE + " where flag_id = " + flag.getFlagId())
		        .toString();
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
