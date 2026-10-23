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
import static org.junit.Assert.assertTrue;

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
	
	private EncounterType patientInfo;
	
	private EncounterType referral;
	
	private Encounter registered;
	
	private Concept echoDate;
	
	private Concept tolerance;
	
	private Concept anaphylaxis;
	
	private Concept nextInrDate;
	
	private Concept reasonStopped;
	
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
		patientInfo = type("Patient Information");
		property(NextSteps.GP_ENCOUNTER_TYPES,
		    "bpg:" + bpg.getUuid() + ",oral:" + oral.getUuid() + ",consult:" + consult.getUuid() + ",echo:" + echo.getUuid()
		            + ",procedures:" + procedures.getUuid() + ",inr:" + inr.getUuid() + ",patientInfo:"
		            + patientInfo.getUuid());
		registered = encounterOf(patientInfo, 400);
		echoDate = concept("Date of echocardiogram", "Datetime");
		tolerance = concept("Injection tolerance", "Coded");
		anaphylaxis = concept("Anaphylaxis", "N/A");
		nextInrDate = concept("Next INR date", "Datetime");
		reasonStopped = concept("Reason stopped", "Coded");
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
		listTheDoseAnyway();
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
	public void of_shouldListAWithheldBpgOnHoldWithNoFormWhileTheConsultationIsToDo() {
		form(bpg);
		lastGiven(36);
		echoed(at(30));
		startVisit();
		answered(inVisit(bpg, 0), tolerance, anaphylaxis);
		
		List<NextStep> steps = steps();
		
		assertEquals("consult|bpg", keys(steps));
		NextStep held = steps.get(1);
		assertEquals("Give BPG injection", held.getTitle());
		assertEquals("On hold: complete the consultation before giving BPG", held.getReason());
		assertEquals(true, held.isOnHold());
		assertNull(held.getForm());
		assertEquals(false, held.isDone());
		assertEquals(false, held.isNew());
	}
	
	@Test
	public void of_shouldListTheHeldBpgOfAnEarlierAnaphylaxisBesideTheReferral() {
		form(bpg);
		lastGiven(36);
		echoed(at(30));
		answered(encounterOf(bpg, 10), tolerance, anaphylaxis);
		consult.setEditPrivilege(privilege("Task: test.consult"));
		Context.getEncounterService().saveEncounterType(consult);
		authenticateWith("Add Encounters");
		
		List<NextStep> steps = steps();
		
		assertEquals("refer|bpg", keys(steps));
		assertEquals("On hold: a clinician must review before the next dose", steps.get(1).getReason());
		assertEquals(true, steps.get(1).isOnHold());
		assertNull(steps.get(1).getForm());
	}
	
	@Test
	public void of_shouldListTheHeldBpgWhilePatientInformationIsToDo() {
		lastGiven(36);
		echoed(at(30));
		answered(encounterOf(bpg, 10), tolerance, anaphylaxis);
		Context.getEncounterService().voidEncounter(registered, "test");
		
		List<NextStep> steps = steps();
		
		assertEquals("consult|bpg|patientInfo", keys(steps));
		assertEquals(true, steps.get(1).isOnHold());
	}
	
	@Test
	public void of_shouldNameTheFormOfABpgNoLongerOnHold() {
		Form form = form(bpg);
		lastGiven(36);
		echoed(at(30));
		startVisit();
		answered(inVisit(bpg, 0), tolerance, anaphylaxis);
		inVisit(consult, 0);
		
		NextStep dose = steps().get(0);
		
		assertEquals("bpg", dose.getKey());
		assertEquals(false, dose.isOnHold());
		assertEquals(form.getUuid(), dose.getForm());
	}
	
	@Test
	public void of_shouldStillLeaveOutABpgNotOnHoldWhileAReviewIsToDo() {
		lastGiven(36);
		echoed(at(400));
		startVisit();
		inVisit(echo, 0);
		
		assertEquals("consult|echo", keys(steps()));
	}
	
	@Test
	public void of_shouldListTheHeldBpgWithNoFormWhenTheRuleIsOff() {
		listTheDoseAnyway();
		form(bpg);
		lastGiven(36);
		echoed(at(30));
		answered(encounterOf(bpg, 10), tolerance, anaphylaxis);
		
		List<NextStep> steps = steps();
		
		assertEquals("consult|bpg", keys(steps));
		assertEquals(true, steps.get(1).isOnHold());
		assertNull(steps.get(1).getForm());
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
		listTheDoseAnyway();
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
	public void of_shouldListTheConsultationDoneWhenThisVisitReviewedAnEarlierAnaphylaxis() {
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
	public void of_shouldKeepAnUnreviewedAnaphylaxisAfterALaterBpgFormWithoutIt() {
		listTheDoseAnyway();
		lastGiven(36);
		echoed(at(30));
		answered(encounterOf(bpg, 10), tolerance, anaphylaxis);
		encounterOf(bpg, 5);
		
		List<NextStep> steps = steps();
		
		assertEquals("consult|bpg", keys(steps));
		assertEquals("Anaphylaxis reported after BPG: review before the next dose", steps.get(0).getReason());
		assertEquals(true, steps.get(0).isNew());
		assertEquals("On hold: complete the consultation before giving BPG", steps.get(1).getReason());
	}
	
	@Test
	public void of_shouldHoldTheNextBpgWhenAnInjectionWasGivenAfterAnUnreviewedAnaphylaxis() {
		listTheDoseAnyway();
		answered(encounterOf(bpg, 40), tolerance, anaphylaxis);
		lastGiven(36);
		echoed(at(30));
		
		List<NextStep> steps = steps();
		
		assertEquals("consult|bpg", keys(steps));
		assertEquals("Anaphylaxis reported after BPG: review before the next dose", steps.get(0).getReason());
		assertEquals("On hold: complete the consultation before giving BPG", steps.get(1).getReason());
	}
	
	@Test
	public void of_shouldKeepTheConsultationAndListBpgDoneWhenThisVisitGaveADoseAfterAnUnreviewedAnaphylaxis() {
		lastGiven(36);
		echoed(at(30));
		answered(encounterOf(bpg, 10), tolerance, anaphylaxis);
		startVisit();
		given(inVisit(bpg, 0), 0);
		
		List<NextStep> steps = steps();
		
		assertEquals("consult|bpg", keys(steps));
		assertEquals(true, steps.get(0).isNew());
		assertEquals("Entered this visit", steps.get(1).getReason());
		assertEquals(true, steps.get(1).isDone());
	}
	
	@Test
	public void of_shouldReferAnUnreviewedAnaphylaxisAfterALaterBpgFormWithoutIt() {
		listTheDoseAnyway();
		lastGiven(36);
		echoed(at(30));
		answered(encounterOf(bpg, 10), tolerance, anaphylaxis);
		encounterOf(bpg, 5);
		consult.setEditPrivilege(privilege("Task: test.consult"));
		Context.getEncounterService().saveEncounterType(consult);
		authenticateWith("Add Encounters");
		
		List<NextStep> steps = steps();
		
		assertEquals("refer|bpg", keys(steps));
		assertEquals("Anaphylaxis reported: a clinician must review before the next dose", steps.get(0).getReason());
		assertEquals("On hold: a clinician must review before the next dose", steps.get(1).getReason());
	}
	
	@Test
	public void of_shouldLeaveOutAnEarlierAnaphylaxisAConsultationBeforeThisVisitReviewed() {
		lastGiven(36);
		echoed(at(30));
		answered(encounterOf(bpg, 20), tolerance, anaphylaxis);
		encounterOf(consult, 15);
		encounterOf(bpg, 10);
		
		assertEquals("bpg", keys(steps()));
	}
	
	@Test
	public void of_shouldRaiseANewerAnaphylaxisAfterAConsultationReviewedAnEarlierOne() {
		listTheDoseAnyway();
		lastGiven(36);
		echoed(at(30));
		answered(encounterOf(bpg, 20), tolerance, anaphylaxis);
		encounterOf(consult, 15);
		answered(encounterOf(bpg, 10), tolerance, anaphylaxis);
		encounterOf(bpg, 5);
		
		assertEquals("consult|bpg", keys(steps()));
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
		listTheDoseAnyway();
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
		assertEquals("On hold: a clinician must review before the next dose", steps.get(1).getReason());
	}
	
	@Test
	public void of_shouldShowTheReferralSentOnceOneIsSavedAfterTheAnaphylaxisAndKeepTheDoseOnHold() {
		mapReferral();
		listTheDoseAnyway();
		lastGiven(36);
		echoed(at(30));
		answered(encounterOf(bpg, 10), tolerance, anaphylaxis);
		encounterOf(referral, 5);
		mayNotConsult("Add Encounters");
		
		List<NextStep> steps = steps();
		
		assertEquals("bpg|refer", keys(steps));
		assertEquals(true, steps.get(0).isOnHold());
		assertEquals("On hold: a clinician must review before the next dose", steps.get(0).getReason());
		assertEquals(true, steps.get(1).isDone());
		assertEquals(false, steps.get(1).isNew());
	}
	
	@Test
	public void of_shouldNameTheReferralTypeOnAReferralStillToDo() {
		mapReferral();
		listTheDoseAnyway();
		lastGiven(36);
		echoed(at(30));
		answered(encounterOf(bpg, 10), tolerance, anaphylaxis);
		mayNotConsult("Add Encounters");
		
		NextStep refer = steps().get(0);
		
		assertEquals("refer", refer.getKey());
		assertEquals(referral.getUuid(), refer.getReferralType());
		assertEquals(false, refer.isDone());
		assertEquals(true, refer.isNew());
	}
	
	@Test
	public void of_shouldNotCountAReferralSavedBeforeTheAnaphylaxis() {
		mapReferral();
		listTheDoseAnyway();
		lastGiven(36);
		echoed(at(30));
		encounterOf(referral, 20);
		answered(encounterOf(bpg, 10), tolerance, anaphylaxis);
		mayNotConsult("Add Encounters");
		
		assertEquals(false, steps().get(0).isDone());
	}
	
	@Test
	public void of_shouldNeedANewReferralForALaterTriggerThanTheLastReferral() {
		mapReferral();
		prescribe(encounter(7, 60), oralPenicillin, 60, null);
		echoed(at(30));
		answered(encounterOf(bpg, 40), tolerance, anaphylaxis);
		encounterOf(referral, 30);
		estimate(encounterOf(oral, 10), 50);
		mayNotConsult("Add Encounters");
		
		NextStep refer = steps().get(0);
		
		assertEquals("refer", refer.getKey());
		assertEquals(false, refer.isDone());
		assertEquals(true, refer.isNew());
	}
	
	@Test
	public void of_shouldShowTheReferralSentOnceItFollowsTheLatestOfTwoTriggers() {
		mapReferral();
		prescribe(encounter(7, 60), oralPenicillin, 60, null);
		echoed(at(30));
		answered(encounterOf(bpg, 40), tolerance, anaphylaxis);
		encounterOf(referral, 30);
		estimate(encounterOf(oral, 10), 50);
		encounterOf(referral, 5);
		mayNotConsult("Add Encounters");
		
		assertEquals(true, steps().get(0).isDone());
	}
	
	@Test
	public void of_shouldShowAReferralSentForNoProphylaxisOnlyIfItIsAfterTheLatestConsultation() {
		mapReferral();
		lastGiven(36);
		echoed(at(30));
		flagged(notPrescribed);
		encounterOf(consult, 10);
		encounterOf(referral, 20);
		mayNotConsult("Add Encounters", "View Patient Flags");
		
		assertEquals(false, steps().get(0).isDone());
	}
	
	@Test
	public void of_shouldShowAReferralSentForNoProphylaxisWhenItIsAfterTheLatestConsultation() {
		mapReferral();
		lastGiven(36);
		echoed(at(30));
		flagged(notPrescribed);
		encounterOf(consult, 10);
		encounterOf(referral, 5);
		mayNotConsult("Add Encounters", "View Patient Flags");
		
		assertEquals(true, steps().get(0).isDone());
	}
	
	@Test
	public void of_shouldCountAnyReferralForNoProphylaxisWhenThereIsNoConsultation() {
		mapReferral();
		lastGiven(36);
		echoed(at(30));
		flagged(notPrescribed);
		encounterOf(referral, 40);
		mayNotConsult("Add Encounters", "View Patient Flags");
		
		assertEquals(true, steps().get(0).isDone());
	}
	
	@Test
	public void of_shouldKeepTheDoseOutAfterTheReferralForNoProphylaxisIsSent() {
		mapReferral();
		lastGiven(36);
		echoed(at(30));
		flagged(notPrescribed);
		encounterOf(referral, 1);
		mayNotConsult("Add Encounters", "View Patient Flags");
		
		List<NextStep> steps = steps();
		
		assertEquals("refer", keys(steps));
		assertEquals(true, steps.get(0).isDone());
	}
	
	@Test
	public void of_shouldNameNoReferralTypeWhenNoneIsMapped() {
		listTheDoseAnyway();
		lastGiven(36);
		echoed(at(30));
		answered(encounterOf(bpg, 10), tolerance, anaphylaxis);
		mayNotConsult("Add Encounters");
		
		assertNull(steps().get(0).getReferralType());
	}
	
	@Test
	public void of_shouldMarkTheReferralDoneWhenAConsultationIsSavedThisVisitAfterAnaphylaxis() {
		lastGiven(36);
		echoed(at(30));
		startVisit();
		answered(inVisit(bpg, 0), tolerance, anaphylaxis);
		inVisit(consult, 0);
		consult.setEditPrivilege(privilege("Task: test.consult"));
		Context.getEncounterService().saveEncounterType(consult);
		authenticateWith("Add Encounters");
		
		List<NextStep> steps = steps();
		
		assertEquals("bpg|refer", keys(steps));
		assertEquals("Overdue: was due 21-Sep-2026", steps.get(0).getReason());
		assertEquals(true, steps.get(1).isDone());
		assertEquals(false, steps.get(1).isNew());
	}
	
	@Test
	public void of_shouldReferTheUnreviewedLowEstimateWhenThisVisitsConsultationReviewedTheAnaphylaxis() {
		prescribe(encounter(7, 60), oralPenicillin, 60, null);
		echoed(at(30));
		startVisit();
		answered(inVisit(bpg, 0), tolerance, anaphylaxis);
		inVisit(consult, 0);
		estimate(inVisit(oral, 0), 50);
		consult.setEditPrivilege(privilege("Task: test.consult"));
		Context.getEncounterService().saveEncounterType(consult);
		authenticateWith("Add Encounters");
		
		NextStep refer = steps().get(0);
		
		assertEquals("refer", refer.getKey());
		assertEquals("Oral adherence below 80%: a clinician to review the plan", refer.getReason());
		assertEquals(false, refer.isDone());
	}
	
	@Test
	public void of_shouldGiveTheUnreviewedEchosReasonWhenThisVisitsConsultationReviewedTheAnaphylaxis() {
		listTheDoseAnyway();
		lastGiven(36);
		echoed(at(30));
		startVisit();
		answered(inVisit(bpg, 0), tolerance, anaphylaxis);
		inVisit(consult, 0);
		inVisit(echo, 0);
		
		List<NextStep> steps = steps();
		
		assertEquals("consult|bpg|echo", keys(steps));
		assertEquals("Review the new echo result and update the care plan", steps.get(0).getReason());
		assertEquals(false, steps.get(0).isDone());
		assertEquals("Overdue: was due 21-Sep-2026", steps.get(1).getReason());
	}
	
	@Test
	public void of_shouldGiveTheUnreviewedAnaphylaxisReasonWhenThisVisitsConsultationReviewedTheEcho() {
		listTheDoseAnyway();
		lastGiven(36);
		echoed(at(30));
		startVisit();
		inVisit(echo, 0);
		inVisit(consult, 0);
		answered(inVisit(bpg, 0), tolerance, anaphylaxis);
		
		List<NextStep> steps = steps();
		
		assertEquals("consult|bpg|echo", keys(steps));
		assertEquals("Anaphylaxis reported after BPG: review before the next dose", steps.get(0).getReason());
		assertEquals(false, steps.get(0).isDone());
		assertEquals("On hold: complete the consultation before giving BPG", steps.get(1).getReason());
	}
	
	@Test
	public void of_shouldGiveTheAnaphylaxisReasonOverAnUnreviewedEchoSavedThisVisit() {
		lastGiven(36);
		echoed(at(30));
		startVisit();
		inVisit(echo, 0);
		Encounter injection = inVisit(bpg, 0);
		given(injection, 0);
		answered(injection, tolerance, anaphylaxis);
		
		List<NextStep> steps = steps();
		
		assertEquals("consult|bpg|echo", keys(steps));
		assertEquals("Anaphylaxis reported after BPG: review before the next dose", steps.get(0).getReason());
		assertEquals(true, steps.get(0).isNew());
		assertEquals(true, steps.get(1).isDone());
	}
	
	@Test
	public void of_shouldGiveTheAnaphylaxisReasonWhenThisVisitsConsultationReviewedItAndTheEcho() {
		lastGiven(36);
		echoed(at(30));
		startVisit();
		inVisit(echo, 0);
		answered(inVisit(bpg, 0), tolerance, anaphylaxis);
		inVisit(consult, 0);
		
		List<NextStep> steps = steps();
		
		assertEquals("bpg|echo|consult", keys(steps));
		assertEquals("Anaphylaxis reported after BPG: review before the next dose", steps.get(2).getReason());
		assertEquals(true, steps.get(2).isDone());
	}
	
	@Test
	public void of_shouldNotReferANewEchoForAUserWhoMayNotRecordTheConsultation() {
		echoed(at(400));
		startVisit();
		inVisit(echo, 0);
		consult.setEditPrivilege(privilege("Task: test.consult"));
		Context.getEncounterService().saveEncounterType(consult);
		authenticateWith("Add Encounters");
		
		assertEquals("echo", keys(steps()));
	}
	
	@Test
	public void of_shouldGiveTheReviewedEchosReasonOverTheNotPrescribedFlags() {
		flagged(notPrescribed);
		echoed(at(400));
		startVisit();
		inVisit(echo, 0);
		inVisit(consult, 0);
		
		List<NextStep> steps = steps();
		
		assertEquals("consult|echo", keys(steps));
		assertEquals("Review the new echo result and update the care plan", steps.get(0).getReason());
		assertEquals(true, steps.get(0).isDone());
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
	public void of_shouldReferAPatientFlaggedAsNotPrescribedForAUserWhoMayNotRecordTheConsultation() {
		echoed(at(30));
		flagged(notPrescribed);
		consult.setEditPrivilege(privilege("Task: test.consult"));
		Context.getEncounterService().saveEncounterType(consult);
		authenticateWith("Add Encounters", "View Patient Flags");
		
		List<NextStep> steps = steps();
		
		assertEquals("refer", keys(steps));
		assertEquals("Refer to clinician", steps.get(0).getTitle());
		assertNull(steps.get(0).getForm());
		assertEquals("No prophylaxis prescribed: a clinician to prescribe", steps.get(0).getReason());
		assertEquals(false, steps.get(0).isNew());
		assertEquals(false, steps.get(0).isDone());
	}
	
	@Test
	public void of_shouldMarkTheNotPrescribedReferralDoneWhenAConsultationIsSavedThisVisit() {
		echoed(at(30));
		flagged(notPrescribed);
		startVisit();
		inVisit(consult, 0);
		consult.setEditPrivilege(privilege("Task: test.consult"));
		Context.getEncounterService().saveEncounterType(consult);
		authenticateWith("Add Encounters", "View Patient Flags");
		
		List<NextStep> steps = steps();
		
		assertEquals("refer", keys(steps));
		assertEquals(true, steps.get(0).isDone());
	}
	
	@Test
	public void of_shouldGiveTheLowEstimatesReasonOverTheNotPrescribedReferral() {
		prescribe(encounter(7, 60), oralPenicillin, 60, null);
		estimate(encounterOf(oral, 10), 50);
		echoed(at(30));
		flagged(notPrescribed);
		consult.setEditPrivilege(privilege("Task: test.consult"));
		Context.getEncounterService().saveEncounterType(consult);
		authenticateWith("Add Encounters", "View Patient Flags");
		
		List<NextStep> steps = steps();
		
		assertEquals("refer", keys(steps).replace("|oral", "").replace("oral|", ""));
		assertEquals("Oral adherence below 80%: a clinician to review the plan", steps.get(0).getReason());
		assertEquals(true, steps.get(0).isNew());
	}
	
	@Test
	public void of_shouldListTheDoseBesidePatientInformationToDo() {
		lastGiven(36);
		echoed(at(30));
		Context.getEncounterService().voidEncounter(registered, "test");
		
		List<NextStep> steps = steps();
		
		assertEquals("bpg|patientInfo", keys(steps));
		assertEquals("Overdue: was due 21-Sep-2026", steps.get(0).getReason());
	}
	
	@Test
	public void of_shouldStillLeaveTheDoseOutForAReviewWhilePatientInformationIsToDo() {
		lastGiven(36);
		echoed(at(30));
		flagged(notPrescribed);
		Context.getEncounterService().voidEncounter(registered, "test");
		
		assertEquals("patientInfo|consult", keys(steps()));
	}
	
	@Test
	public void of_shouldGiveTheDoseWhilePatientInformationTheUserMayNotRecordIsToDo() {
		lastGiven(36);
		echoed(at(30));
		Context.getEncounterService().voidEncounter(registered, "test");
		patientInfo.setEditPrivilege(privilege("Task: test.patientInfo"));
		Context.getEncounterService().saveEncounterType(patientInfo);
		authenticateWith("Add Encounters");
		
		List<NextStep> steps = steps();
		
		assertEquals("bpg", keys(steps));
		assertEquals("Overdue: was due 21-Sep-2026", steps.get(0).getReason());
	}
	
	@Test
	public void of_shouldListTheDoseBesidePatientInformationTheUserMayRecord() {
		lastGiven(36);
		echoed(at(30));
		Context.getEncounterService().voidEncounter(registered, "test");
		patientInfo.setEditPrivilege(privilege("Task: test.patientInfo"));
		Context.getEncounterService().saveEncounterType(patientInfo);
		authenticateWith("Add Encounters", "Task: test.patientInfo");
		
		assertEquals("bpg|patientInfo", keys(steps()));
	}
	
	@Test
	public void of_shouldListTheOralDoseBesidePatientInformationToDo() {
		prescribe(encounter(7, 120), oralPenicillin, 120, null);
		estimate(encounterOf(oral, 100), 90);
		echoed(at(30));
		Context.getEncounterService().voidEncounter(registered, "test");
		
		assertEquals("oral|patientInfo", keys(steps()));
	}
	
	@Test
	public void of_shouldLeaveTheDoseOutWhileTheConsultationIsToDo() {
		lastGiven(36);
		echoed(at(30));
		flagged(notPrescribed);
		
		assertEquals("consult", keys(steps()));
	}
	
	@Test
	public void of_shouldLeaveTheDoseOutWhileTheReferralIsToDo() {
		lastGiven(36);
		echoed(at(30));
		flagged(notPrescribed);
		consult.setEditPrivilege(privilege("Task: test.consult"));
		Context.getEncounterService().saveEncounterType(consult);
		authenticateWith("Add Encounters", "View Patient Flags");
		
		assertEquals("refer", keys(steps()));
	}
	
	@Test
	public void of_shouldGiveTheDoseWhileAConsultationTheUserMayNotRecordIsToDo() {
		lastGiven(36);
		echoed(at(400));
		startVisit();
		inVisit(echo, 0);
		consult.setEditPrivilege(privilege("Task: test.consult"));
		Context.getEncounterService().saveEncounterType(consult);
		authenticateWith("Add Encounters");
		
		assertEquals("bpg|echo", keys(steps()));
	}
	
	@Test
	public void of_shouldListTheDoseWhileOnlyAnEchoProceduresOrInrIsToDo() {
		lastGiven(36);
		echoed(at(400));
		flagged(followUp);
		flagged(inrDue);
		
		assertEquals("bpg|echo|procedures|inr", keys(steps()));
	}
	
	@Test
	public void of_shouldListTheDoseRecordedThisVisitDoneWhileTheConsultationIsToDo() {
		lastGiven(36);
		echoed(at(30));
		flagged(notPrescribed);
		startVisit();
		given(inVisit(bpg, 0), 0);
		
		assertEquals("consult|bpg", keys(steps()));
		assertEquals(true, steps().get(1).isDone());
	}
	
	@Test
	public void of_shouldListTheDoseWhileTheConsultationIsToDoWhenTheRuleIsOff() {
		listTheDoseAnyway();
		lastGiven(36);
		echoed(at(30));
		flagged(notPrescribed);
		
		assertEquals("bpg|consult", keys(steps()));
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
	public void of_shouldGiveAnOverdueOralSupplyBesideTheLowAdherenceConsultation() {
		prescribe(encounter(7, 120), oralPenicillin, 120, null);
		estimate(encounterOf(oral, 100), 70);
		echoed(at(30));
		
		List<NextStep> steps = steps();
		
		assertEquals("consult|oral", keys(steps));
		assertEquals("Oral adherence below 80%: consider switching to BPG", steps.get(0).getReason());
		NextStep supply = steps.get(1);
		assertEquals("Record oral prophylaxis visit", supply.getTitle());
		assertTrue(supply.getReason().startsWith("Overdue: was due "));
		assertEquals(false, supply.isOnHold());
		assertEquals(false, supply.isDone());
	}
	
	@Test
	public void of_shouldGiveAnOverdueOralSupplyBesideTheLowAdherenceReferral() {
		Form form = form(oral);
		prescribe(encounter(7, 120), oralPenicillin, 120, null);
		estimate(encounterOf(oral, 100), 70);
		echoed(at(30));
		consult.setEditPrivilege(privilege("Task: test.consult"));
		Context.getEncounterService().saveEncounterType(consult);
		authenticateWith("Add Encounters");
		
		List<NextStep> steps = steps();
		
		assertEquals("refer|oral", keys(steps));
		assertEquals("Oral adherence below 80%: a clinician to review the plan", steps.get(0).getReason());
		assertEquals(form.getUuid(), steps.get(1).getForm());
	}
	
	@Test
	public void of_shouldNotGiveTheOralSupplyAUserWhoMayNotRecordIt() {
		prescribe(encounter(7, 120), oralPenicillin, 120, null);
		estimate(encounterOf(oral, 100), 70);
		echoed(at(30));
		consult.setEditPrivilege(privilege("Task: test.consult"));
		Context.getEncounterService().saveEncounterType(consult);
		oral.setEditPrivilege(privilege("Task: test.oral"));
		Context.getEncounterService().saveEncounterType(oral);
		authenticateWith("Add Encounters");
		
		assertEquals("refer", keys(steps()));
	}
	
	@Test
	public void of_shouldGiveTheOralSupplyBesideTheReviewOfAnEchoSavedThisVisit() {
		prescribe(encounter(7, 120), oralPenicillin, 120, null);
		estimate(encounterOf(oral, 100), 90);
		echoed(at(400));
		startVisit();
		inVisit(echo, 0);
		
		assertEquals("consult|oral|echo", keys(steps()));
	}
	
	@Test
	public void of_shouldListTheOralSupplyBesideTheConsultationWhenTheRuleIsOff() {
		listTheDoseAnyway();
		prescribe(encounter(7, 120), oralPenicillin, 120, null);
		estimate(encounterOf(oral, 100), 70);
		echoed(at(30));
		
		assertEquals("consult|oral", keys(steps()));
	}
	
	@Test
	public void of_shouldReviewTheEstimatesOfAboutHalfAndFewButNotAllOrMost() {
		prescribe(encounter(7, 60), oralPenicillin, 60, null);
		echoed(at(30));
		
		estimate(encounterOf(oral, 40), 100);
		assertEquals("", keys(steps()));
		estimate(encounterOf(oral, 30), 90);
		assertEquals("", keys(steps()));
		estimate(encounterOf(oral, 20), 50);
		assertEquals("consult", keys(steps()));
		estimate(encounterOf(oral, 10), 20);
		assertEquals("consult", keys(steps()));
		assertEquals("Oral adherence below 80%: consider switching to BPG", steps().get(0).getReason());
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
		listTheDoseAnyway();
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
	
	@Test
	public void of_shouldNotCountADoseGivenOnAnEarlierDayOfAVisitLeftOpen() {
		echoed(at(30));
		startVisit(28);
		Encounter dose = inVisit(bpg, 28);
		prescribe(dose, q28, 28, null);
		given(dose, 28);
		
		List<NextStep> steps = steps();
		
		assertEquals("bpg", keys(steps));
		assertEquals("Due today", steps.get(0).getReason());
		assertEquals(false, steps.get(0).isDone());
	}
	
	@Test
	public void of_shouldNotCountAnEarlierInjectionRecordedToday() {
		lastGiven(36);
		echoed(at(30));
		startVisit();
		given(inVisit(bpg, 0), 36);
		
		List<NextStep> steps = steps();
		
		assertEquals("bpg", keys(steps));
		assertEquals("Overdue: was due 21-Sep-2026", steps.get(0).getReason());
		assertEquals(false, steps.get(0).isDone());
	}
	
	@Test
	public void of_shouldLeaveOutAnaphylaxisAConsultationOnAnEarlierDayOfAVisitLeftOpenReviewed() {
		lastGiven(36);
		echoed(at(30));
		startVisit(25);
		answered(inVisit(bpg, 25), tolerance, anaphylaxis);
		inVisit(consult, 20);
		
		List<NextStep> steps = steps();
		
		assertEquals("bpg", keys(steps));
		assertEquals("Overdue: was due 21-Sep-2026", steps.get(0).getReason());
	}
	
	@Test
	public void of_shouldNotAskForAReviewOfAnEchoSavedOnAnEarlierDayOfAVisitLeftOpen() {
		startVisit(3);
		inVisit(echo, 3);
		
		assertEquals("", keys(steps()));
	}
	
	@Test
	public void of_shouldAskForAnEchoWhenTheLastWasSavedOnAnEarlierDayOfAVisitLeftOpen() {
		startVisit(400);
		Encounter scan = inVisit(echo, 400);
		Obs date = obs(scan, echoDate);
		date.setValueDatetime(at(400));
		Context.getObsService().saveObs(date, null);
		
		List<NextStep> steps = steps();
		
		assertEquals("echo", keys(steps));
		assertEquals("Last echo was over 12 months ago (25-Aug-2025)", steps.get(0).getReason());
		assertEquals(false, steps.get(0).isDone());
	}
	
	@Test
	public void of_shouldNotSuggestBpgForALowEstimateAfterAnAnaphylaxisOnBpg() {
		answered(encounterOf(bpg, 90), tolerance, anaphylaxis);
		encounterOf(consult, 80);
		prescribe(encounter(7, 60), oralPenicillin, 60, null);
		estimate(encounterOf(oral, 10), 50);
		echoed(at(30));
		
		List<NextStep> steps = steps();
		
		assertEquals("consult", keys(steps).replace("oral|", ""));
		assertEquals("Oral adherence below 80%: a clinician to review the plan", steps.get(steps.size() - 1).getReason());
	}
	
	@Test
	public void of_shouldNotSuggestBpgForALowEstimateAfterAConsultationStoppedBpgForAnaphylaxis() {
		Encounter consultation = encounterOf(consult, 60);
		stoppedFor(consultation, q28, 120, 60, anaphylaxis);
		prescribe(consultation, oralPenicillin, 60, null);
		estimate(encounterOf(oral, 10), 50);
		echoed(at(30));
		
		List<NextStep> steps = steps();
		
		assertEquals("consult", keys(steps).replace("oral|", ""));
		assertEquals("Oral adherence below 80%: a clinician to review the plan", steps.get(steps.size() - 1).getReason());
	}
	
	@Test
	public void of_shouldStillSuggestBpgForALowEstimateWhenTheRegimenStoppedForAnaphylaxisWasOral() {
		Encounter consultation = encounterOf(consult, 60);
		stoppedFor(consultation, concept("Oral erythromycin", "N/A"), 120, 60, anaphylaxis);
		prescribe(consultation, oralPenicillin, 60, null);
		estimate(encounterOf(oral, 10), 50);
		echoed(at(30));
		
		List<NextStep> steps = steps();
		
		assertEquals("consult", keys(steps).replace("oral|", ""));
		assertEquals("Oral adherence below 80%: consider switching to BPG", steps.get(steps.size() - 1).getReason());
	}
	
	@Test
	public void of_shouldStillSuggestBpgForALowEstimateWhenAnaphylaxisIsAnsweredOutsideAPrescription() {
		Encounter consultation = encounterOf(consult, 60);
		answered(consultation, reasonStopped, anaphylaxis);
		prescribe(consultation, oralPenicillin, 60, null);
		estimate(encounterOf(oral, 10), 50);
		echoed(at(30));
		
		List<NextStep> steps = steps();
		
		assertEquals("consult", keys(steps).replace("oral|", ""));
		assertEquals("Oral adherence below 80%: consider switching to BPG", steps.get(steps.size() - 1).getReason());
	}
	
	@Test
	public void of_shouldStillSuggestBpgForALowEstimateWhenOnlyAVoidedConsultationStoppedBpgForAnaphylaxis() {
		Encounter stopping = encounterOf(consult, 70);
		stoppedFor(stopping, q28, 120, 70, anaphylaxis);
		prescribe(encounterOf(consult, 60), oralPenicillin, 60, null);
		estimate(encounterOf(oral, 10), 50);
		echoed(at(30));
		// Raw SQL rather than voidEncounter, so the obs stay unvoided as in migrated data.
		Context.flushSession();
		Context.getAdministrationService()
		        .executeSQL("update encounter set voided = true where encounter_id = " + stopping.getEncounterId(), false);
		Context.clearSession();
		
		List<NextStep> steps = steps();
		
		assertEquals("consult", keys(steps).replace("oral|", ""));
		assertEquals("Oral adherence below 80%: consider switching to BPG", steps.get(steps.size() - 1).getReason());
	}
	
	@Test
	public void of_shouldStillSuggestBpgForALowEstimateWhenAnInjectionRegimenIsAnotherMemberOfAGroupStoppedForAnaphylaxis() {
		Encounter consultation = encounterOf(consult, 60);
		Obs group = prescriptionGroup(consultation, concept("Oral erythromycin", "N/A"), 120, 60);
		Obs why = obs(consultation, reasonStopped);
		why.setValueCoded(anaphylaxis);
		group.addGroupMember(why);
		Obs previous = obs(consultation, concept("Previous regimen", "Coded"));
		previous.setValueCoded(q28);
		group.addGroupMember(previous);
		Context.getObsService().saveObs(group, null);
		prescribe(consultation, oralPenicillin, 60, null);
		estimate(encounterOf(oral, 10), 50);
		echoed(at(30));
		
		List<NextStep> steps = steps();
		
		assertEquals("consult", keys(steps).replace("oral|", ""));
		assertEquals("Oral adherence below 80%: consider switching to BPG", steps.get(steps.size() - 1).getReason());
	}
	
	@Test
	public void of_shouldStillSuggestBpgForALowEstimateWhenAMigratedRegimenStoppedForAnaphylaxisHasNoAnswer() {
		Encounter consultation = encounterOf(consult, 60);
		stoppedFor(consultation, q28, 120, 60, anaphylaxis);
		prescribe(consultation, oralPenicillin, 60, null);
		estimate(encounterOf(oral, 10), 50);
		echoed(at(30));
		Context.flushSession();
		Context.getAdministrationService().executeSQL("update obs set value_coded = null where value_coded = "
		        + q28.getConceptId() + " and encounter_id = " + consultation.getEncounterId(),
		    false);
		Context.clearSession();
		
		List<NextStep> steps = steps();
		
		assertEquals("consult", keys(steps).replace("oral|", ""));
		assertEquals("Oral adherence below 80%: consider switching to BPG", steps.get(steps.size() - 1).getReason());
	}
	
	@Test
	public void of_shouldAskForPatientInformationWhenNoneIsRecorded() {
		echoed(at(30));
		Context.getEncounterService().voidEncounter(registered, "test");
		
		List<NextStep> steps = steps();
		
		assertEquals("patientInfo", keys(steps));
		assertEquals("Patient Information", steps.get(0).getTitle());
		assertEquals("No diagnosis recorded", steps.get(0).getReason());
		assertEquals(false, steps.get(0).isNew());
		assertEquals(false, steps.get(0).isDone());
	}
	
	@Test
	public void of_shouldAskForPatientInformationAfterTheDose() {
		listTheDoseAnyway();
		lastGiven(28);
		echoed(at(30));
		flagged(notPrescribed);
		Context.getEncounterService().voidEncounter(registered, "test");
		
		assertEquals("bpg|patientInfo|consult", keys(steps()));
	}
	
	@Test
	public void of_shouldAskForPatientInformationOnlyAUserWhoMayRecordIt() {
		echoed(at(30));
		Context.getEncounterService().voidEncounter(registered, "test");
		patientInfo.setEditPrivilege(privilege("Task: test.patientInfo"));
		Context.getEncounterService().saveEncounterType(patientInfo);
		authenticateWith("Add Encounters", "Task: test.patientInfo");
		
		assertEquals("patientInfo", keys(steps()));
	}
	
	@Test
	public void of_shouldNotAskForPatientInformationAUserWhoMayNotRecordIt() {
		echoed(at(30));
		Context.getEncounterService().voidEncounter(registered, "test");
		patientInfo.setEditPrivilege(privilege("Task: test.patientInfo"));
		Context.getEncounterService().saveEncounterType(patientInfo);
		authenticateWith("Add Encounters");
		
		assertEquals("", keys(steps()));
	}
	
	@Test
	public void of_shouldListPatientInformationSavedThisVisitAsEntered() {
		echoed(at(30));
		startVisit();
		inVisit(patientInfo, 0);
		
		List<NextStep> steps = steps();
		
		assertEquals("patientInfo", keys(steps));
		assertEquals("Entered this visit", steps.get(0).getReason());
		assertEquals(true, steps.get(0).isDone());
	}
	
	/** Maps the referral step to an encounter type, as ``refer:`` in the setting does. */
	private void mapReferral() {
		referral = type("Referral");
		property(NextSteps.GP_ENCOUNTER_TYPES,
		    Context.getAdministrationService().getGlobalProperty(NextSteps.GP_ENCOUNTER_TYPES) + ",refer:"
		            + referral.getUuid());
	}
	
	/**
	 * A user who may add encounters but not record the consultation, so is listed Refer to clinician.
	 */
	private void mayNotConsult(String... privileges) {
		consult.setEditPrivilege(privilege("Task: test.consult"));
		Context.getEncounterService().saveEncounterType(consult);
		authenticateWith(privileges);
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
		startVisit(0);
	}
	
	private void startVisit(int daysAgo) {
		visit = new Visit(patient, Context.getVisitService().getVisitType(1), at(daysAgo));
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
	
	private void stoppedFor(Encounter encounter, Concept regimen, int startedDaysAgo, int stoppedDaysAgo, Concept reason) {
		Obs group = prescriptionGroup(encounter, regimen, startedDaysAgo, stoppedDaysAgo);
		Obs why = obs(encounter, reasonStopped);
		why.setValueCoded(reason);
		group.addGroupMember(why);
		Context.getObsService().saveObs(group, null);
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
	
	private void listTheDoseAnyway() {
		property(NextSteps.GP_DOSE_AFTER_EARLIER_STEPS, "false");
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
