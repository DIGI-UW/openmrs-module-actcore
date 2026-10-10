/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.nextsteps;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.Before;
import org.junit.Test;
import org.openmrs.Concept;
import org.openmrs.Encounter;
import org.openmrs.EncounterType;
import org.openmrs.Form;
import org.openmrs.Obs;
import org.openmrs.Patient;
import org.openmrs.Person;
import org.openmrs.PersonName;
import org.openmrs.Privilege;
import org.openmrs.Role;
import org.openmrs.User;
import org.openmrs.Visit;
import org.openmrs.api.UserService;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.adherence.AdherenceContextTest;
import org.openmrs.module.patientflags.Flag;
import org.openmrs.module.patientflags.PatientFlag;
import org.openmrs.module.patientflags.api.FlagService;
import org.openmrs.module.patientflags.evaluator.SQLFlagEvaluator;

public class NextStepsContextTest extends AdherenceContextTest {
	
	private EncounterType bpg;
	
	private EncounterType oral;
	
	private EncounterType consult;
	
	private EncounterType echo;
	
	private EncounterType procedures;
	
	private EncounterType inr;
	
	private Concept echoDate;
	
	private Concept tolerance;
	
	private Concept anaphylaxis;
	
	private Concept nextInrDate;
	
	private Flag notPrescribed;
	
	private Flag followUp;
	
	private Flag inrDue;
	
	private Visit visit;
	
	private Patient patient;
	
	@Before
	public void setUpNextSteps() {
		patient = Context.getPatientService().getPatient(7);
		bpg = type("BPG Delivery");
		oral = type("Oral Adherence");
		consult = type("Consultation Visit");
		echo = type("Echocardiogram");
		procedures = type("Interventions and Outcomes");
		inr = type("INR Monitoring");
		property(NextSteps.GP_ENCOUNTER_TYPES,
		    "bpg:" + bpg.getUuid() + ",oral:" + oral.getUuid() + ",consult:" + consult.getUuid() + ",echo:" + echo.getUuid()
		            + ",procedures:" + procedures.getUuid() + ",inr:" + inr.getUuid());
		echoDate = concept("Date of echocardiogram", "Datetime");
		tolerance = concept("Injection tolerance", "Coded");
		anaphylaxis = concept("Anaphylaxis", "N/A");
		nextInrDate = concept("Next INR date", "Datetime");
		property(NextSteps.GP_ECHO_DATE, echoDate.getUuid());
		property(NextSteps.GP_ANAPHYLAXIS, anaphylaxis.getUuid());
		property(NextSteps.GP_NEXT_INR_DATE, nextInrDate.getUuid());
		notPrescribed = flag("RHD prophylaxis not prescribed");
		followUp = flag("RHD 30-day follow-up due");
		inrDue = flag("RHD INR review due");
		property(NextSteps.GP_FLAGS,
		    "notPrescribed:" + notPrescribed.getUuid() + ",followUp:" + followUp.getUuid() + ",inr:" + inrDue.getUuid());
	}
	
	@Test
	public void of_shouldGiveBpgAndEchoForAnOverdueDoseAndAThirteenMonthOldEcho() {
		lastGiven(36);
		echoed(at(395));
		
		List<NextStep> steps = steps();
		
		assertEquals("bpg|echo", keys(steps));
		assertEquals("Give BPG injection", steps.get(0).getTitle());
		assertEquals("Overdue: was due 21-Sep-2026", steps.get(0).getReason());
		assertEquals("Last echo was over 12 months ago (30-Aug-2025)", steps.get(1).getReason());
		assertEquals(false, steps.get(0).isNew());
		assertEquals(false, steps.get(0).isDone());
	}
	
	@Test
	public void of_shouldGiveANewConsultationAndBpgDoneOnceAnAnaphylaxisInjectionIsSavedThisVisit() {
		lastGiven(36);
		echoed(at(30));
		startVisit();
		Encounter injection = inVisit(bpg, 0);
		given(injection, 0);
		answered(injection, tolerance, anaphylaxis);
		
		List<NextStep> steps = steps();
		
		assertEquals("consult|bpg", keys(steps));
		assertEquals("Anaphylaxis reported after BPG: review before the next dose", steps.get(0).getReason());
		assertEquals(true, steps.get(0).isNew());
		assertEquals(false, steps.get(0).isDone());
		assertEquals(true, steps.get(1).isDone());
	}
	
	@Test
	public void of_shouldHoldAWithheldBpgUntilTheConsultation() {
		lastGiven(36);
		echoed(at(30));
		startVisit();
		answered(inVisit(bpg, 0), tolerance, anaphylaxis);
		
		List<NextStep> steps = steps();
		
		assertEquals("consult|bpg", keys(steps));
		assertEquals("On hold: complete the consultation before giving BPG", steps.get(1).getReason());
		assertEquals(false, steps.get(1).isDone());
	}
	
	@Test
	public void of_shouldMarkTheConsultationDoneWhenItIsSavedThisVisitAfterAnaphylaxis() {
		lastGiven(36);
		echoed(at(30));
		startVisit();
		answered(inVisit(bpg, 0), tolerance, anaphylaxis);
		inVisit(consult, 0);
		
		List<NextStep> steps = steps();
		
		assertEquals("bpg|consult", keys(steps));
		assertEquals("Overdue: was due 21-Sep-2026", steps.get(0).getReason());
		assertEquals(true, steps.get(1).isDone());
		assertEquals("Anaphylaxis reported after BPG: review before the next dose", steps.get(1).getReason());
		assertEquals(false, steps.get(1).isNew());
	}
	
	@Test
	public void of_shouldCountAConsultationSavedAfterTheBpgAtTheSameTimeAsTheReview() {
		lastGiven(36);
		echoed(at(30));
		answered(encounterOf(bpg, 10), tolerance, anaphylaxis);
		encounterOf(consult, 10);
		
		assertEquals("bpg", keys(steps()));
	}
	
	@Test
	public void of_shouldHoldBpgWhenThisVisitsConsultationCameBeforeTheAnaphylaxis() {
		lastGiven(36);
		echoed(at(30));
		startVisit();
		inVisit(consult, 0);
		answered(inVisit(bpg, 0), tolerance, anaphylaxis);
		
		List<NextStep> steps = steps();
		
		assertEquals("consult|bpg", keys(steps));
		assertEquals(false, steps.get(0).isDone());
		assertEquals(true, steps.get(0).isNew());
		assertEquals("On hold: complete the consultation before giving BPG", steps.get(1).getReason());
	}
	
	@Test
	public void of_shouldNotCountThisVisitsConsultationAsTheReviewOfAnEarlierAnaphylaxis() {
		lastGiven(36);
		echoed(at(30));
		answered(encounterOf(bpg, 10), tolerance, anaphylaxis);
		startVisit();
		inVisit(consult, 0);
		
		List<NextStep> steps = steps();
		
		assertEquals("bpg|consult", keys(steps));
		assertEquals("Anaphylaxis reported after BPG: review before the next dose", steps.get(1).getReason());
		assertEquals(true, steps.get(1).isDone());
	}
	
	@Test
	public void of_shouldLeaveOutAnaphylaxisAConsultationBeforeThisVisitReviewed() {
		lastGiven(36);
		echoed(at(30));
		answered(encounterOf(bpg, 10), tolerance, anaphylaxis);
		encounterOf(consult, 5);
		
		assertEquals("bpg", keys(steps()));
	}
	
	@Test
	public void of_shouldReferAUserWhoMayNotRecordTheConsultation() {
		lastGiven(36);
		echoed(at(30));
		answered(encounterOf(bpg, 10), tolerance, anaphylaxis);
		consult.setEditPrivilege(privilege("Task: test.consult"));
		Context.getEncounterService().saveEncounterType(consult);
		authenticateWith("Add Encounters");
		
		List<NextStep> steps = steps();
		
		assertEquals("refer|bpg", keys(steps));
		NextStep refer = steps.get(0);
		assertEquals("Refer to clinician", refer.getTitle());
		assertNull(refer.getForm());
		assertEquals("Anaphylaxis reported: a clinician must review before the next dose", refer.getReason());
		assertEquals(true, refer.isNew());
	}
	
	@Test
	public void of_shouldGiveNoFormStepsToAUserWithoutAddEncounters() {
		authenticateWith("View Patient Flags");
		
		assertEquals("", keys(steps()));
	}
	
	@Test
	public void of_shouldGiveTheDoseDueToday() {
		lastGiven(28);
		echoed(at(30));
		
		assertEquals("Due today", steps().get(0).getReason());
	}
	
	@Test
	public void of_shouldGiveTheDoseDueTomorrow() {
		lastGiven(27);
		echoed(at(30));
		
		assertEquals("Due tomorrow", steps().get(0).getReason());
	}
	
	@Test
	public void of_shouldGiveTheDoseWhenItIsDueWithinTwoDays() {
		lastGiven(26);
		echoed(at(30));
		
		List<NextStep> steps = steps();
		
		assertEquals("bpg", keys(steps));
		assertEquals("Due in 2 days", steps.get(0).getReason());
	}
	
	@Test
	public void of_shouldNotGiveADoseDueInThreeDays() {
		lastGiven(25);
		echoed(at(30));
		
		assertEquals("", keys(steps()));
	}
	
	@Test
	public void of_shouldGiveTheConsultationForAPatientFlaggedAsNotPrescribed() {
		echoed(at(30));
		flagged(notPrescribed);
		
		List<NextStep> steps = steps();
		
		assertEquals("consult", keys(steps));
		assertEquals("No prophylaxis prescribed", steps.get(0).getReason());
	}
	
	@Test
	public void of_shouldGiveProceduresForAPatientWithTheThirtyDayFollowUpDue() {
		echoed(at(30));
		flagged(followUp);
		
		List<NextStep> steps = steps();
		
		assertEquals("procedures", keys(steps));
		assertEquals("30-day follow-up due", steps.get(0).getReason());
	}
	
	@Test
	public void of_shouldGiveTheInrReviewWithItsDueDate() {
		echoed(at(30));
		flagged(inrDue);
		Obs next = obs(encounterOf(inr, 40), nextInrDate);
		next.setValueDatetime(at(5));
		Context.getObsService().saveObs(next, null);
		
		List<NextStep> steps = steps();
		
		assertEquals("inr", keys(steps));
		assertEquals("INR review overdue: was due 24-Sep-2026", steps.get(0).getReason());
	}
	
	@Test
	public void of_shouldSkipTheFlagRulesForAUserWhoMayNotSeeFlags() {
		echoed(at(30));
		flagged(notPrescribed);
		flagged(followUp);
		authenticateWith("Add Encounters");
		
		assertEquals("", keys(steps()));
	}
	
	@Test
	public void of_shouldAskForAnEchoWhenNoneIsRecorded() {
		List<NextStep> steps = steps();
		
		assertEquals("echo", keys(steps));
		assertEquals("No echocardiogram recorded", steps.get(0).getReason());
	}
	
	@Test
	public void of_shouldGiveANewConsultationAndEchoDoneWhenAnEchoIsSavedThisVisit() {
		echoed(at(400));
		startVisit();
		Encounter today = inVisit(echo, 0);
		Obs date = obs(today, echoDate);
		date.setValueDatetime(at(0));
		Context.getObsService().saveObs(date, null);
		
		List<NextStep> steps = steps();
		
		assertEquals("consult|echo", keys(steps));
		assertEquals("Review the new echo result and update the care plan", steps.get(0).getReason());
		assertEquals(true, steps.get(0).isNew());
		assertEquals(true, steps.get(1).isDone());
		assertEquals("Entered this visit", steps.get(1).getReason());
	}
	
	@Test
	public void of_shouldNotCountAConsultationBeforeThisVisitsEchoAsItsReview() {
		echoed(at(400));
		startVisit();
		inVisit(consult, 0);
		inVisit(echo, 0);
		
		List<NextStep> steps = steps();
		
		assertEquals("consult|echo", keys(steps));
		assertEquals("Review the new echo result and update the care plan", steps.get(0).getReason());
		assertEquals(false, steps.get(0).isDone());
	}
	
	@Test
	public void of_shouldCountAnEchoSavedWithoutItsDateByTheEncounter() {
		encounterOf(echo, 30);
		
		assertEquals("", keys(steps()));
	}
	
	@Test
	public void of_shouldFallBackToTheDefaultForAThresholdTooLargeForAnInt() {
		property(NextSteps.GP_ECHO_MONTHS, "99999999999");
		echoed(at(395));
		
		assertEquals("Last echo was over 12 months ago (30-Aug-2025)", steps().get(0).getReason());
	}
	
	@Test
	public void of_shouldListAFormSavedThisVisitAsEnteredWhenNoRuleAskedForIt() {
		echoed(at(30));
		startVisit();
		inVisit(procedures, 0);
		
		List<NextStep> steps = steps();
		
		assertEquals("procedures", keys(steps));
		assertEquals("Entered this visit", steps.get(0).getReason());
		assertEquals(true, steps.get(0).isDone());
	}
	
	@Test
	public void of_shouldGiveANewConsultationForOralAdherenceBelowEightyPercent() {
		prescribe(encounter(7, 60), oralPenicillin, 60, null);
		estimate(encounterOf(oral, 10), 50);
		echoed(at(30));
		
		List<NextStep> steps = steps();
		
		assertEquals("consult", keys(steps).replace("oral|", ""));
		assertEquals("Oral adherence below 80%: consider switching to BPG", steps.get(steps.size() - 1).getReason());
	}
	
	@Test
	public void of_shouldLeaveOutALowEstimateAConsultationAfterItReviewed() {
		prescribe(encounter(7, 60), oralPenicillin, 60, null);
		estimate(encounterOf(oral, 10), 50);
		encounterOf(consult, 5);
		echoed(at(30));
		
		assertEquals("", keys(steps()).replace("oral", ""));
	}
	
	@Test
	public void of_shouldNotCountAConsultationBeforeThisVisitsLowEstimateAsItsReview() {
		prescribe(encounter(7, 60), oralPenicillin, 60, null);
		echoed(at(30));
		startVisit();
		inVisit(consult, 0);
		estimate(inVisit(oral, 0), 50);
		
		List<NextStep> steps = steps();
		
		assertEquals("consult", steps.get(0).getKey());
		assertEquals("Oral adherence below 80%: consider switching to BPG", steps.get(0).getReason());
		assertEquals(false, steps.get(0).isDone());
	}
	
	@Test
	public void of_shouldReferALowEstimateForAUserWhoMayNotRecordTheConsultation() {
		prescribe(encounter(7, 60), oralPenicillin, 60, null);
		estimate(encounterOf(oral, 10), 50);
		echoed(at(30));
		consult.setEditPrivilege(privilege("Task: test.consult"));
		Context.getEncounterService().saveEncounterType(consult);
		authenticateWith("Add Encounters");
		
		List<NextStep> steps = steps();
		
		assertEquals("refer", keys(steps).replace("|oral", "").replace("oral|", ""));
		assertEquals("Oral adherence below 80%: a clinician to review the plan", steps.get(0).getReason());
	}
	
	@Test
	public void of_shouldNotAskForAConsultationOverALowEstimateOnABpgRegimen() {
		lastGiven(10);
		estimate(encounterOf(oral, 10), 50);
		echoed(at(30));
		
		assertEquals("", keys(steps()));
	}
	
	@Test
	public void of_shouldPutNewStepsFirstAndDoneStepsLast() {
		lastGiven(36);
		echoed(at(400));
		answered(encounterOf(bpg, 10), tolerance, anaphylaxis);
		startVisit();
		inVisit(procedures, 0);
		
		assertEquals("consult|bpg|echo|procedures", keys(steps()));
	}
	
	@Test
	public void of_shouldNameTheNewestUnretiredFormOfTheStepsEncounterType() {
		Form old = form(echo);
		old.setRetired(true);
		old.setRetireReason("replaced");
		Context.getFormService().saveForm(old);
		Form current = form(echo);
		Form newer = form(echo);
		newer.setRetired(true);
		newer.setRetireReason("withdrawn");
		Context.getFormService().saveForm(newer);
		
		assertEquals(current.getUuid(), steps().get(0).getForm());
	}
	
	private List<NextStep> steps() {
		return NextSteps.of(patient, TODAY);
	}
	
	/**
	 * Q28 prescribed and last given ``daysAgo`` days ago, so next due ``28 - daysAgo`` days from today.
	 */
	private void lastGiven(int daysAgo) {
		Encounter dose = encounterOf(bpg, daysAgo);
		prescribe(dose, q28, daysAgo, null);
		given(dose, daysAgo);
	}
	
	private static String keys(List<NextStep> steps) {
		List<String> keys = new ArrayList<String>();
		for (NextStep step : steps) {
			keys.add(step.getKey());
		}
		return String.join("|", keys);
	}
	
	private EncounterType type(String name) {
		EncounterType type = new EncounterType(name + " " + System.nanoTime(), name);
		return Context.getEncounterService().saveEncounterType(type);
	}
	
	private Form form(EncounterType type) {
		Form form = new Form();
		form.setName(type.getName() + " " + System.nanoTime());
		form.setVersion("1");
		form.setEncounterType(type);
		return Context.getFormService().saveForm(form);
	}
	
	private Encounter encounterOf(EncounterType type, int daysAgo) {
		Encounter encounter = encounter(7, daysAgo);
		encounter.setEncounterType(type);
		return Context.getEncounterService().saveEncounter(encounter);
	}
	
	private Encounter inVisit(EncounterType type, int daysAgo) {
		Encounter encounter = encounterOf(type, daysAgo);
		encounter.setVisit(visit);
		return Context.getEncounterService().saveEncounter(encounter);
	}
	
	private void startVisit() {
		visit = new Visit(patient, Context.getVisitService().getVisitType(1), at(0));
		Context.getVisitService().saveVisit(visit);
	}
	
	private void echoed(java.util.Date when) {
		Encounter encounter = encounter(7, 400);
		encounter.setEncounterType(echo);
		encounter.setEncounterDatetime(when);
		Context.getEncounterService().saveEncounter(encounter);
		Obs date = obs(encounter, echoDate);
		date.setValueDatetime(when);
		Context.getObsService().saveObs(date, null);
	}
	
	private void answered(Encounter encounter, Concept question, Concept answer) {
		Obs obs = obs(encounter, question);
		obs.setValueCoded(answer);
		Context.getObsService().saveObs(obs, null);
	}
	
	private Flag flag(String name) {
		Flag flag = new Flag();
		flag.setUuid(UUID.randomUUID().toString());
		flag.setName(name + " " + System.nanoTime());
		flag.setCriteria("select p.patient_id from patient p where 1 = 0");
		flag.setEvaluator(SQLFlagEvaluator.class.getName());
		flag.setMessage(name);
		flag.setEnabled(Boolean.TRUE);
		Context.getService(FlagService.class).saveFlag(flag);
		return flag;
	}
	
	private void flagged(Flag flag) {
		Context.getService(FlagService.class).savePatientFlag(new PatientFlag(patient, flag, flag.getMessage()));
	}
	
	private Privilege privilege(String name) {
		UserService users = Context.getUserService();
		Privilege privilege = users.getPrivilege(name);
		return privilege != null ? privilege : users.savePrivilege(new Privilege(name));
	}
	
	private void authenticateWith(String... privileges) {
		UserService users = Context.getUserService();
		Role role = new Role("next steps " + UUID.randomUUID());
		for (String name : privileges) {
			role.addPrivilege(privilege(name));
		}
		users.saveRole(role);
		Person person = new Person();
		person.addName(new PersonName("Next", null, "Steps"));
		person.setGender("F");
		User user = new User(person);
		user.setUsername("nextsteps" + System.nanoTime() % 100000);
		user.addRole(role);
		users.createUser(user, "NextSteps123");
		Context.logout();
		Context.authenticate(user.getUsername(), "NextSteps123");
	}
}
