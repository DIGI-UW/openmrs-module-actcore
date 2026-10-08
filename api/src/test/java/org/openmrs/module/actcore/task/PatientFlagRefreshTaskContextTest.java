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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import org.junit.Before;
import org.junit.Test;
import org.openmrs.Cohort;
import org.openmrs.GlobalProperty;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.cohort.CohortM;
import org.openmrs.module.cohort.CohortMember;
import org.openmrs.module.cohort.CohortType;
import org.openmrs.module.cohort.api.CohortMemberService;
import org.openmrs.module.cohort.api.CohortService;
import org.openmrs.module.cohort.api.CohortTypeService;
import org.openmrs.module.patientflags.Flag;
import org.openmrs.module.patientflags.FlagValidationResult;
import org.openmrs.module.patientflags.PatientFlag;
import org.openmrs.module.patientflags.Tag;
import org.openmrs.module.patientflags.api.FlagService;
import org.openmrs.module.patientflags.evaluator.FlagEvaluator;
import org.openmrs.module.patientflags.evaluator.SQLFlagEvaluator;
import org.openmrs.test.BaseModuleContextSensitiveTest;

public class PatientFlagRefreshTaskContextTest extends BaseModuleContextSensitiveTest {
	
	private static final int MATCHING_PATIENT = 7;
	
	private static final int OTHER_PATIENT = 2;
	
	private static final String MATCHES_ONE = "select patient_id from patient where patient_id = " + MATCHING_PATIENT;
	
	private static final String MATCHES_NOBODY = "select patient_id from patient where patient_id = -1";
	
	private FlagService flagService;
	
	@Before
	public void setUp() {
		flagService = Context.getService(FlagService.class);
		saveCohortType("System List");
	}
	
	@Test
	public void recordsWhenItFinished() {
		Instant before = Instant.now();
		
		boolean ran = new PatientFlagRefreshTask().runIfIdle();
		
		assertTrue(ran);
		Instant finished = Instant
		        .parse(Context.getAdministrationService().getGlobalProperty(PatientFlagRefreshTask.LAST_FINISHED_PROPERTY));
		assertTrue(!finished.isBefore(before) && !finished.isAfter(Instant.now()));
		assertTrue(!PatientFlagRefreshTask.isRunning());
	}
	
	@Test
	public void keepsAPatientWhoseFlagWasVoidedOffTheList() {
		Flag flag = saveFlag("dismissed", MATCHES_NOBODY);
		voidedFlagFor(flag);
		
		runScheduledWork();
		
		assertEquals(1, count(
		    "select count(*) from patientflags_patient_flag where flag_id = " + flag.getFlagId() + " and voided = true"));
		assertEquals(1, listsNamed("dismissed"));
		assertEquals(0, activeMembers("dismissed", MATCHING_PATIENT));
	}
	
	@Test
	public void raisesAFlagAgainWhenItsOnlyRowWasVoidedAndThePatientStillMatches() {
		Flag flag = saveFlag("still matching", MATCHES_ONE);
		voidedFlagFor(flag);
		
		runScheduledWork();
		
		assertEquals(1, count("select count(*) from patientflags_patient_flag where flag_id = " + flag.getFlagId()
		        + " and patient_id = " + MATCHING_PATIENT + " and voided = false"));
	}
	
	@Test
	public void listsAFlagsPatientsAfterARun() {
		saveFlag("overdue", MATCHES_ONE);
		
		runScheduledWork();
		
		assertEquals(1, activeMembers("overdue", MATCHING_PATIENT));
	}
	
	@Test
	public void endsTheListOfAFlagThatHasBeenDisabled() {
		Flag flag = saveFlag("overdue", MATCHES_ONE);
		runScheduledWork();
		assertEquals(1, activeMembers("overdue", MATCHING_PATIENT));
		
		flag = flagService.getFlag(flag.getFlagId());
		flag.setEnabled(Boolean.FALSE);
		flagService.saveFlag(flag);
		runScheduledWork();
		
		assertEquals(0, activeMembers("overdue", MATCHING_PATIENT));
		assertEquals(1, listsNamed("overdue"));
	}
	
	@Test
	public void endsTheListOfAFlagThatHasBeenRetired() {
		Flag flag = saveFlag("overdue", MATCHES_ONE);
		runScheduledWork();
		assertEquals(1, activeMembers("overdue", MATCHING_PATIENT));
		
		flag = flagService.getFlag(flag.getFlagId());
		flag.setRetired(Boolean.TRUE);
		flag.setRetireReason("replaced");
		flagService.saveFlag(flag);
		runScheduledWork();
		
		assertEquals(0, activeMembers("overdue", MATCHING_PATIENT));
		assertEquals(1, listsNamed("overdue"));
	}
	
	@Test
	public void endsTheListOfAFlagThatNoLongerCarriesTheListTag() {
		Tag tag = new Tag();
		tag.setName("worklist");
		flagService.saveTag(tag);
		Context.getAdministrationService().saveGlobalProperty(new GlobalProperty("actcore.listFlagTag", "worklist"));
		Flag flag = saveFlag("overdue", MATCHES_ONE);
		flag.setTags(new HashSet<Tag>(Collections.singleton(tag)));
		flagService.saveFlag(flag);
		runScheduledWork();
		assertEquals(1, activeMembers("overdue", MATCHING_PATIENT));
		
		flag = flagService.getFlag(flag.getFlagId());
		flag.getTags().clear();
		flagService.saveFlag(flag);
		runScheduledWork();
		
		assertEquals(0, activeMembers("overdue", MATCHING_PATIENT));
		assertEquals(1, listsNamed("overdue"));
	}
	
	@Test
	public void neverListsAFlagWithoutTheListTag() {
		Context.getAdministrationService().saveGlobalProperty(new GlobalProperty("actcore.listFlagTag", "worklist"));
		saveFlag("overdue", MATCHES_ONE);
		
		runScheduledWork();
		
		assertEquals(0, listsNamed("overdue"));
	}
	
	@Test
	public void neverRaisesOrListsADisabledFlag() {
		Flag flag = saveFlag("overdue", MATCHES_ONE);
		flag.setEnabled(Boolean.FALSE);
		flagService.saveFlag(flag);
		
		runScheduledWork();
		
		assertEquals(0, liveRows(flag));
		assertEquals(0, listsNamed("overdue"));
	}
	
	@Test
	public void neverRaisesOrListsARetiredFlag() {
		Flag flag = saveFlag("overdue", MATCHES_ONE);
		flag.setRetired(Boolean.TRUE);
		flag.setRetireReason("replaced");
		flagService.saveFlag(flag);
		
		runScheduledWork();
		
		assertEquals(0, liveRows(flag));
		assertEquals(0, listsNamed("overdue"));
	}
	
	@Test
	public void listsAPatientAgainWhoFallsBackUnderTheFlag() {
		Flag flag = saveFlag("overdue", MATCHES_ONE);
		runScheduledWork();
		setCriteria(flag, MATCHES_NOBODY);
		runScheduledWork();
		assertEquals(0, activeMembers("overdue", MATCHING_PATIENT));
		
		setCriteria(flag, MATCHES_ONE);
		runScheduledWork();
		
		assertEquals(1, activeMembers("overdue", MATCHING_PATIENT));
	}
	
	@Test
	public void followsAFlagThroughARenameWithoutASecondList() {
		Flag flag = saveFlag("overdue", MATCHES_ONE);
		runScheduledWork();
		
		flag = flagService.getFlag(flag.getFlagId());
		flag.setName("prophylaxis overdue");
		flagService.saveFlag(flag);
		runScheduledWork();
		
		assertEquals(0, listsNamed("overdue"));
		assertEquals(1, listsNamed("prophylaxis overdue"));
		assertEquals(1, activeMembers("prophylaxis overdue", MATCHING_PATIENT));
	}
	
	@Test
	public void listsFollowTwoFlagsThatSwapNames() {
		Flag first = saveFlag("overdue", MATCHES_ONE);
		Flag second = saveFlag("lost to follow-up", "select patient_id from patient where patient_id = " + OTHER_PATIENT);
		runScheduledWork();
		
		rename(first, "swapping");
		rename(second, "overdue");
		rename(first, "lost to follow-up");
		runScheduledWork();
		runScheduledWork();
		
		assertEquals(1, listsNamed("overdue"));
		assertEquals(1, activeMembers("overdue", OTHER_PATIENT));
		assertEquals(0, activeMembers("overdue", MATCHING_PATIENT));
		assertEquals(1, listsNamed("lost to follow-up"));
		assertEquals(1, activeMembers("lost to follow-up", MATCHING_PATIENT));
		assertEquals(0, activeMembers("lost to follow-up", OTHER_PATIENT));
	}
	
	@Test
	public void waitsForTheListInItsWayRatherThanTakingAStandInName() {
		Flag first = saveFlag("alpha", MATCHES_ONE);
		Flag second = saveFlag("beta", "select patient_id from patient where patient_id = " + OTHER_PATIENT);
		runScheduledWork();
		
		rename(second, "gamma");
		rename(first, "beta");
		assertEquals(0, errorsLoggedDuringScheduledWork());
		assertEquals(0, count("select count(*) from cohort where name like '%(%'"));
		runScheduledWork();
		
		assertEquals(1, activeMembers("beta", MATCHING_PATIENT));
		assertEquals(1, activeMembers("gamma", OTHER_PATIENT));
	}
	
	@Test
	public void keepsItsNameWhenTheListInItsWayIsStuckBehindAHandMadeOne() {
		saveHandMadeList("lost to follow-up", OTHER_PATIENT);
		Flag first = saveFlag("overdue", MATCHES_ONE);
		Flag second = saveFlag("follow-up due", MATCHES_ONE);
		runScheduledWork();
		
		rename(second, "lost to follow-up");
		rename(first, "follow-up due");
		first = flagService.getFlag(first.getFlagId());
		first.setCriteria(
		    "select patient_id from patient where patient_id in (" + MATCHING_PATIENT + ", " + OTHER_PATIENT + ")");
		flagService.saveFlag(first);
		runScheduledWork();
		runScheduledWork();
		
		assertEquals(1, listsNamed("overdue"));
		assertEquals(1, activeMembers("overdue", MATCHING_PATIENT));
		assertEquals(1, activeMembers("overdue", OTHER_PATIENT));
	}
	
	@Test
	public void leavesAHandMadeListWithTheFlagsNameAlone() {
		saveHandMadeList("overdue", OTHER_PATIENT);
		saveFlag("overdue", MATCHES_ONE);
		
		runScheduledWork();
		
		assertEquals(1, listsNamed("overdue"));
		assertEquals(1, activeMembers("overdue", OTHER_PATIENT));
		assertEquals(0, activeMembers("overdue", MATCHING_PATIENT));
	}
	
	@Test
	public void keepsTheOldNameWhenARenameWouldTakeAHandMadeListsName() {
		saveHandMadeList("prophylaxis overdue", OTHER_PATIENT);
		Flag flag = saveFlag("overdue", MATCHES_ONE);
		runScheduledWork();
		
		flag = flagService.getFlag(flag.getFlagId());
		flag.setName("prophylaxis overdue");
		flagService.saveFlag(flag);
		runScheduledWork();
		
		assertEquals(1, listsNamed("prophylaxis overdue"));
		assertEquals(1, activeMembers("prophylaxis overdue", OTHER_PATIENT));
		assertEquals(0, activeMembers("prophylaxis overdue", MATCHING_PATIENT));
		assertEquals(1, activeMembers("overdue", MATCHING_PATIENT));
	}
	
	@Test
	public void createsTheConfiguredCohortTypeWhenItIsMissing() {
		Context.getAdministrationService().saveGlobalProperty(new GlobalProperty("actcore.listCohortType", "Flag Lists"));
		saveFlag("overdue", MATCHES_ONE);
		
		runScheduledWork();
		
		assertNotNull(Context.getService(CohortTypeService.class).getCohortTypeByName("Flag Lists"));
		assertEquals(1, activeMembers("overdue", MATCHING_PATIENT));
	}
	
	@Test
	public void doesNotRecreateACohortTypeThatWasVoided() {
		CohortTypeService cohortTypeService = Context.getService(CohortTypeService.class);
		cohortTypeService.voidCohortType(cohortTypeService.getCohortTypeByName("System List"), "not wanted");
		saveFlag("overdue", MATCHES_ONE);
		
		runScheduledWork();
		
		assertEquals(1, count("select count(*) from cohort_type where name = 'System List'"));
		assertEquals(0, listsNamed("overdue"));
	}
	
	@Test
	public void doesNotRecreateAListThatWasVoided() {
		saveFlag("overdue", MATCHES_ONE);
		runScheduledWork();
		CohortService cohortService = Context.getService(CohortService.class);
		cohortService.voidCohortM(cohortService.getCohortM("overdue"), "not wanted");
		
		assertEquals(0, errorsLoggedDuringScheduledWork());
		assertEquals(1, count("select count(*) from cohort where name = 'overdue'"));
		assertEquals(0, listsNamed("overdue"));
	}
	
	@Test
	public void restoresThePurgedListOfAFlagRecreatedUnderItsUuid() {
		Flag flag = saveFlag("overdue", MATCHES_ONE);
		runScheduledWork();
		purge(flag);
		runScheduledWork();
		
		saveFlag("overdue", MATCHES_ONE, flag.getUuid());
		runScheduledWork();
		runScheduledWork();
		
		assertEquals(1, listsNamed("overdue"));
		assertEquals(1, activeMembersOfList(flag.getUuid(), MATCHING_PATIENT));
		assertEquals(1, count("select count(*) from cohort_attribute"));
	}
	
	@Test
	public void restoresThePurgedListOfAFlagRecreatedUnderItsUuidWithANewName() {
		Flag flag = saveFlag("overdue", MATCHES_ONE);
		runScheduledWork();
		purge(flag);
		runScheduledWork();
		
		saveFlag("prophylaxis overdue", MATCHES_ONE, flag.getUuid());
		runScheduledWork();
		
		assertEquals(0, count("select count(*) from cohort where voided = false and name = 'overdue'"));
		assertEquals(1, activeMembers("prophylaxis overdue", MATCHING_PATIENT));
	}
	
	@Test
	public void keepsAPurgedListVoidedWhileAHandMadeListHoldsTheRecreatedFlagsName() {
		Flag flag = saveFlag("overdue", MATCHES_ONE);
		runScheduledWork();
		purge(flag);
		runScheduledWork();
		saveHandMadeList("prophylaxis overdue", OTHER_PATIENT);
		
		saveFlag("prophylaxis overdue", MATCHES_ONE, flag.getUuid());
		runScheduledWork();
		
		assertEquals(0, listsNamed("overdue"));
		assertEquals(1, listsNamed("prophylaxis overdue"));
		assertEquals(1, activeMembers("prophylaxis overdue", OTHER_PATIENT));
		assertEquals(0, activeMembers("prophylaxis overdue", MATCHING_PATIENT));
	}
	
	@Test
	public void voidsTheListOfAFlagThatHasBeenPurged() {
		Flag flag = saveFlag("overdue", MATCHES_ONE);
		runScheduledWork();
		assertEquals(1, activeMembersOfList(flag.getUuid(), MATCHING_PATIENT));
		
		purge(flag);
		runScheduledWork();
		
		assertEquals(0, activeMembersOfList(flag.getUuid(), MATCHING_PATIENT));
		assertEquals(0, listsNamed("overdue"));
		assertEquals(0, count("select count(*) from cohort_member where voided = false"));
	}
	
	@Test
	public void givesANewFlagTheNameOfAPurgedFlagsListInOneRun() {
		Flag flag = saveFlag("overdue", MATCHES_ONE);
		runScheduledWork();
		
		purge(flag);
		Flag replacement = saveFlag("overdue", MATCHES_ONE);
		runScheduledWork();
		
		assertEquals(1, listsNamed("overdue"));
		assertEquals(1, activeMembersOfList(replacement.getUuid(), MATCHING_PATIENT));
	}
	
	@Test
	public void voidsTheListOfAPurgedFlagWhenTheListPredatesItsMarker() {
		Flag flag = saveFlag("overdue", MATCHES_ONE);
		runScheduledWork();
		execute("delete from cohort_attribute");
		runScheduledWork();
		
		purge(flag);
		runScheduledWork();
		
		assertEquals(0, activeMembersOfList(flag.getUuid(), MATCHING_PATIENT));
	}
	
	@Test
	public void marksEachListOnce() {
		saveFlag("overdue", MATCHES_ONE);
		runScheduledWork();
		runScheduledWork();
		
		assertEquals(1, count("select count(*) from cohort_attribute"));
	}
	
	@Test
	public void rewritesAMessageWhosePlaceholderValueHasChanged() {
		execute("update person set gender = 'M' where person_id = " + MATCHING_PATIENT);
		Flag flag = saveFlag("gender", "select p.patient_id, pe.gender from patient p join person pe"
		        + " on pe.person_id = p.patient_id where p.patient_id = " + MATCHING_PATIENT);
		flag.setMessage("gender ${1}");
		flagService.saveFlag(flag);
		runScheduledWork();
		assertEquals("gender M", liveMessage(flag));
		
		execute("update person set gender = 'F' where person_id = " + MATCHING_PATIENT);
		runScheduledWork();
		
		assertEquals("gender F", liveMessage(flag));
	}
	
	@Test
	public void reportsWhatTheListSyncDidAndNotOnlyTheFlags() {
		saveFlag("overdue", MATCHES_ONE);
		
		LogEvent summary = theSummaryIn(logged(PatientFlagRefreshTask.class, Level.INFO, new PatientFlagRefreshTask()));
		
		assertEquals(Level.INFO, summary.getLevel());
		String message = summary.getMessage().getFormattedMessage();
		assertTrue(message, message.contains("lists 1 created"));
		assertTrue(message, message.contains("1 members added"));
	}
	
	/**
	 * The packaged log4j2.xml leaves this module at warn, so a run reporting itself only at info would
	 * be silent on a default install whether it worked or not.
	 */
	@Test
	public void reportsARunThatFailedAtWarnRatherThanInfo() {
		saveFlag("overdue", MATCHES_ONE);
		PatientFlagRefreshTask failing = new PatientFlagRefreshTask() {
			
			@Override
			void reconcile(FlagService flagService, Flag flag) {
				throw new IllegalStateException("criteria is broken");
			}
		};
		
		LogEvent summary = theSummaryIn(logged(PatientFlagRefreshTask.class, Level.INFO, failing));
		
		assertEquals(Level.WARN, summary.getLevel());
		String message = summary.getMessage().getFormattedMessage();
		assertTrue(message, message.contains("1 flags and 0 lists failed"));
	}
	
	/**
	 * Fails on the second patient's message, after the first's row is raised. Ascending: the refresh
	 * takes the matches from a HashSet, which holds these two ids in that order.
	 */
	public static class FailsForOnePatient implements FlagEvaluator {
		
		@Override
		public Boolean eval(Flag flag, Patient patient, Map<Object, Object> context) {
			return true;
		}
		
		@Override
		public Cohort evalCohort(Flag flag, Cohort cohort, Map<Object, Object> context) {
			return new Cohort(Arrays.asList(OTHER_PATIENT, MATCHING_PATIENT));
		}
		
		@Override
		public FlagValidationResult validate(Flag flag) {
			return new FlagValidationResult(true);
		}
		
		@Override
		public String evalMessage(Flag flag, int patientId) {
			if (patientId == MATCHING_PATIENT) {
				throw new IllegalStateException("no message for this patient");
			}
			return flag.getMessage();
		}
	}
	
	@Test
	public void countsTheRowsAFlagRaisedBeforeItFailed() {
		Flag flag = saveFlag("partly broken", "unused");
		flag.setEvaluator(FailsForOnePatient.class.getName());
		flagService.saveFlag(flag);
		
		String summary = summaryOfARun();
		
		assertTrue(summary, summary.contains("1 rows raised"));
		assertEquals(1, count("select count(*) from patientflags_patient_flag where flag_id = " + flag.getFlagId()
		        + " and patient_id = " + OTHER_PATIENT + " and voided = false"));
	}
	
	@Test
	public void countsAPurgedFlagsListAsRetiredOnlyOnce() {
		Flag flag = saveFlag("overdue", MATCHES_ONE);
		runScheduledWork();
		purge(flag);
		
		String first = summaryOfARun();
		String second = summaryOfARun();
		
		assertTrue(first, first.contains("1 retired"));
		assertTrue(second, second.contains("0 retired"));
	}
	
	@Test
	public void aRunStartedWhileAnotherIsGoingIsSkipped() throws Exception {
		saveFlag("overdue", MATCHES_ONE);
		final Thread first = Thread.currentThread();
		final List<LogEvent> secondRunLogged = new CopyOnWriteArrayList<LogEvent>();
		final AtomicReference<Throwable> secondRunThrew = new AtomicReference<Throwable>();
		AbstractAppender appender = new AbstractAppender("overlap", null, null, true, Property.EMPTY_ARRAY) {
			
			@Override
			public void append(LogEvent event) {
				if (Thread.currentThread() != first) {
					secondRunLogged.add(event.toImmutable());
					return;
				}
				if (!event.getMessage().getFormattedMessage().contains("Patient flag refresh starting")) {
					return;
				}
				Thread second = new Thread(new Runnable() {
					
					@Override
					public void run() {
						try {
							new PatientFlagRefreshTask().execute();
						}
						catch (Throwable t) {
							secondRunThrew.set(t);
						}
					}
				});
				second.start();
				try {
					second.join(10000);
				}
				catch (InterruptedException e) {
					throw new IllegalStateException(e);
				}
				if (second.isAlive()) {
					secondRunThrew.set(new IllegalStateException("the second run did not return"));
				}
			}
		};
		
		runScheduledWorkWith(appender, PatientFlagRefreshTask.class, Level.INFO, new PatientFlagRefreshTask());
		
		assertNull(String.valueOf(secondRunThrew.get()), secondRunThrew.get());
		assertEquals(1, secondRunLogged.size());
		assertEquals(Level.WARN, secondRunLogged.get(0).getLevel());
		assertTrue(secondRunLogged.get(0).getMessage().getFormattedMessage().contains("another run is in progress"));
		assertEquals(1, activeMembers("overdue", MATCHING_PATIENT));
	}
	
	/**
	 * patientflags evaluates a placeholder message by appending the patient to the criteria, which
	 * fails for criteria ending in LIMIT.
	 */
	@Test
	public void clearsAPatientWhoStoppedMatchingWhenAnotherPatientsMessageCannotBeEvaluated() {
		Flag flag = saveFlag("overdue",
		    "select p.patient_id, p.patient_id from patient p where p.patient_id = " + MATCHING_PATIENT + " limit 10");
		flag.setMessage("overdue ${1}");
		flagService.saveFlag(flag);
		flagService.savePatientFlag(new PatientFlag(Context.getPatientService().getPatient(OTHER_PATIENT), flag, "overdue"));
		
		String summary = theSummaryIn(logged(PatientFlagRefreshTask.class, Level.INFO, new PatientFlagRefreshTask()))
		        .getMessage().getFormattedMessage();
		
		assertTrue(summary, summary.contains("1 cleared"));
		assertEquals(0, count("select count(*) from patientflags_patient_flag where flag_id = " + flag.getFlagId()
		        + " and patient_id = " + OTHER_PATIENT + " and voided = false"));
	}
	
	private String summaryOfARun() {
		return theSummaryIn(logged(PatientFlagRefreshTask.class, Level.INFO, new PatientFlagRefreshTask())).getMessage()
		        .getFormattedMessage();
	}
	
	private void runScheduledWork() {
		runScheduledWork(new PatientFlagRefreshTask());
	}
	
	private void runScheduledWork(PatientFlagRefreshTask task) {
		Context.flushSession();
		task.execute();
	}
	
	private int errorsLoggedDuringScheduledWork() {
		return logged(FlagListSync.class, Level.ERROR, new PatientFlagRefreshTask()).size();
	}
	
	private List<LogEvent> logged(Class<?> source, final Level threshold, PatientFlagRefreshTask task) {
		final List<LogEvent> events = new ArrayList<LogEvent>();
		AbstractAppender appender = new AbstractAppender("capture", null, null, true, Property.EMPTY_ARRAY) {
			
			@Override
			public void append(LogEvent event) {
				if (event.getLevel().isMoreSpecificThan(threshold)) {
					events.add(event.toImmutable());
				}
			}
		};
		runScheduledWorkWith(appender, source, threshold, task);
		return events;
	}
	
	private void runScheduledWorkWith(AbstractAppender appender, Class<?> source, Level threshold,
	        PatientFlagRefreshTask task) {
		appender.start();
		Logger logger = (Logger) LogManager.getLogger(source);
		Level level = logger.getLevel();
		logger.addAppender(appender);
		// The test log4j2.xml turns every logger off.
		logger.setLevel(threshold);
		try {
			runScheduledWork(task);
		}
		finally {
			logger.removeAppender(appender);
			logger.setLevel(level);
		}
	}
	
	private LogEvent theSummaryIn(List<LogEvent> events) {
		LogEvent summary = null;
		for (LogEvent event : events) {
			if (event.getMessage().getFormattedMessage().contains("Patient flag refresh finished")) {
				assertNull("a run reported itself more than once", summary);
				summary = event;
			}
		}
		assertNotNull("the run reported nothing", summary);
		return summary;
	}
	
	private Flag saveFlag(String name, String criteria) {
		return saveFlag(name, criteria, UUID.randomUUID().toString());
	}
	
	private Flag saveFlag(String name, String criteria, String uuid) {
		Flag flag = new Flag();
		flag.setUuid(uuid);
		flag.setName(name);
		flag.setCriteria(criteria);
		flag.setEvaluator(SQLFlagEvaluator.class.getName());
		flag.setMessage(name);
		flag.setEnabled(Boolean.TRUE);
		flagService.saveFlag(flag);
		return flag;
	}
	
	private void rename(Flag flag, String name) {
		Flag current = flagService.getFlag(flag.getFlagId());
		current.setName(name);
		flagService.saveFlag(current);
		Context.flushSession();
	}
	
	private void setCriteria(Flag flag, String criteria) {
		Flag current = flagService.getFlag(flag.getFlagId());
		current.setCriteria(criteria);
		flagService.saveFlag(current);
	}
	
	private long liveRows(Flag flag) {
		return count(
		    "select count(*) from patientflags_patient_flag where voided = false and flag_id = " + flag.getFlagId());
	}
	
	private void purge(Flag flag) {
		Flag current = flagService.getFlag(flag.getFlagId());
		flagService.deletePatientFlagsForFlag(current);
		flagService.purgeFlag(current.getFlagId());
		Context.flushSession();
		assertEquals(0, count("select count(*) from patientflags_flag where flag_id = " + flag.getFlagId()));
	}
	
	private void execute(String sql) {
		Context.flushSession();
		Context.getAdministrationService().executeSQL(sql, false);
	}
	
	private String liveMessage(Flag flag) {
		Context.flushSession();
		List<List<Object>> rows = Context.getAdministrationService().executeSQL(
		    "select message from patientflags_patient_flag where voided = false and flag_id = " + flag.getFlagId(), true);
		assertEquals(1, rows.size());
		return (String) rows.get(0).get(0);
	}
	
	private void saveHandMadeList(String name, int patientId) {
		CohortM handMade = new CohortM();
		handMade.setName(name);
		handMade.setDescription("kept by hand");
		handMade.setCohortType(Context.getService(CohortTypeService.class).getCohortTypeByName("System List"));
		Context.getService(CohortService.class).saveCohortM(handMade);
		CohortMember member = new CohortMember();
		member.setCohort(handMade);
		member.setPatient(Context.getPatientService().getPatient(patientId));
		member.setStartDate(new Date());
		Context.getService(CohortMemberService.class).saveCohortMember(member);
	}
	
	private void voidedFlagFor(Flag flag) {
		PatientFlag patientFlag = new PatientFlag(Context.getPatientService().getPatient(MATCHING_PATIENT), flag,
		        flag.getMessage());
		flagService.savePatientFlag(patientFlag);
		flagService.voidPatientFlag(patientFlag, "dismissed");
	}
	
	private void saveCohortType(String name) {
		CohortType type = new CohortType();
		type.setName(name);
		type.setDescription(name);
		Context.getService(CohortTypeService.class).saveCohortType(type);
	}
	
	private long activeMembers(String listName, int patientId) {
		return count("select count(*) from cohort_member m join cohort c on c.cohort_id = m.cohort_id where c.name = '"
		        + listName + "' and c.voided = false and m.patient_id = " + patientId
		        + " and m.end_date is null and m.voided = false");
	}
	
	private long activeMembersOfList(String uuid, int patientId) {
		return count("select count(*) from cohort_member m join cohort c on c.cohort_id = m.cohort_id where c.uuid = '"
		        + uuid + "' and m.patient_id = " + patientId + " and m.end_date is null and m.voided = false");
	}
	
	private long listsNamed(String name) {
		return count("select count(*) from cohort where voided = false and name = '" + name + "'");
	}
	
	private long count(String sql) {
		Context.flushSession();
		List<List<Object>> rows = Context.getAdministrationService().executeSQL(sql, true);
		return ((Number) rows.get(0).get(0)).longValue();
	}
}
