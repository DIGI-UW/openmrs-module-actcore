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

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.openmrs.Encounter;
import org.openmrs.EncounterType;
import org.openmrs.Form;
import org.openmrs.Obs;
import org.openmrs.Patient;
import org.openmrs.Visit;
import org.openmrs.api.AdministrationService;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.adherence.ProphylaxisSummary;
import org.openmrs.module.actcore.consultation.NextConsultation;
import org.openmrs.module.actcore.task.RaisedFlags;
import org.openmrs.module.patientflags.PatientFlag;
import org.openmrs.module.patientflags.PatientFlagsConstants;
import org.openmrs.module.patientflags.api.FlagService;
import org.openmrs.util.PrivilegeConstants;

/**
 * Works out the steps the chart suggests for a patient at this visit. It applies the prototype's
 * rules to the patient's saved forms, to ACT Core's dose status and to the patient's flags, and
 * keeps the steps the signed-in user may take.
 */
public class NextSteps {
	
	static final String GP_ENCOUNTER_TYPES = "actcore.nextSteps.encounterTypes";
	
	static final String GP_FLAGS = "actcore.nextSteps.flags";
	
	static final String GP_DOSE_WITHIN_DAYS = "actcore.nextSteps.doseWithinDays";
	
	static final String GP_ECHO_MONTHS = "actcore.nextSteps.echoMonths";
	
	static final String GP_ADHERENCE_BELOW = "actcore.nextSteps.adherenceBelow";
	
	static final String GP_DOSE_AFTER_EARLIER_STEPS = "actcore.nextSteps.doseAfterEarlierSteps";
	
	static final String GP_ECHO_DATE = "actcore.nextSteps.echoDateConcept";
	
	static final String GP_ANAPHYLAXIS = "actcore.nextSteps.anaphylaxisAnswer";
	
	static final String GP_NEXT_INR_DATE = "actcore.nextSteps.nextInrDateConcept";
	
	static final String GP_INJECTION_DATE = "actcore.adherence.injectionDateConcept";
	
	static final String GP_ESTIMATE = "actcore.adherence.estimateConcept";
	
	static final String GP_PRESCRIPTION = "actcore.adherence.prescriptionConcept";
	
	static final String GP_INJECTION_INTERVALS = "actcore.adherence.injectionIntervals";
	
	private static final StepReason ANAPHYLAXIS_REVIEW = StepReason.of("anaphylaxisReview");
	
	private static final StepReason ANAPHYLAXIS_REFERRAL = StepReason.of("anaphylaxisReferral");
	
	private static final StepReason ENTERED_THIS_VISIT = StepReason.of("enteredThisVisit");
	
	/**
	 * The keys of the steps a form records, each titled by its message
	 * {@code actcore.nextStep.title.<key>}.
	 */
	private static final List<String> FORM_KEYS = Collections.unmodifiableList(
	    Arrays.asList("bpg", "oral", "consult", "echo", "procedures", "inr", "patientInfo", "pregnancy"));
	
	private static final List<String> READS = Arrays.asList(PrivilegeConstants.GET_ENCOUNTERS, PrivilegeConstants.GET_VISITS,
	    PrivilegeConstants.GET_FORMS, PrivilegeConstants.GET_ENCOUNTER_TYPES, PrivilegeConstants.GET_OBS,
	    PrivilegeConstants.GET_CONCEPTS, PrivilegeConstants.GET_GLOBAL_PROPERTIES, PrivilegeConstants.GET_PATIENTS,
	    PatientFlagsConstants.PRIV_VIEW_PATIENT_FLAGS);
	
	private final Patient patient;
	
	private final LocalDate today;
	
	private final Map<String, Step> steps = new LinkedHashMap<String, Step>();
	
	private Map<String, EncounterType> types;
	
	private List<Encounter> encounters;
	
	private Set<Encounter> thisVisit;
	
	private Set<String> savedThisVisit;
	
	private Map<Encounter, List<Obs>> obsByEncounter;
	
	private List<Encounter> referTriggers;
	
	private Date referFlagSince;
	
	private ProphylaxisSummary summary;
	
	private NextSteps(Patient patient, LocalDate today) {
		this.patient = patient;
		this.today = today;
	}
	
	/**
	 * Gets the steps the signed-in user may take for the patient: the referrals, and the forms the user
	 * may record. Steps still to do come before steps done, new steps before the rest, and otherwise
	 * the steps keep the order of the rules. The patient's records are read with proxy privileges.
	 *
	 * @param patient the patient
	 * @param today the day the steps are for
	 * @return the steps; empty when no rule gives the user a step
	 */
	public static List<NextStep> of(Patient patient, LocalDate today) {
		return new NextSteps(patient, today).list();
	}
	
	private List<NextStep> list() {
		// Asked before the reads are proxied, which would grant them.
		boolean addsEncounters = Context.hasPrivilege(PrivilegeConstants.ADD_ENCOUNTERS);
		boolean seesFlags = Context.hasPrivilege(PatientFlagsConstants.PRIV_VIEW_PATIENT_FLAGS);
		Map<String, String> editPrivileges = new HashMap<String, String>();
		proxyReads(true);
		try {
			read();
			for (Map.Entry<String, EncounterType> type : types.entrySet()) {
				editPrivileges.put(type.getKey(),
				    type.getValue().getEditPrivilege() == null ? null : type.getValue().getEditPrivilege().getPrivilege());
			}
		}
		finally {
			proxyReads(false);
		}
		// As MayEnterForm in the ACT app: Add Encounters, and the type's edit privilege if any.
		Set<String> enterable = new HashSet<String>();
		for (Map.Entry<String, String> edit : editPrivileges.entrySet()) {
			if (addsEncounters && (edit.getValue() == null || Context.hasPrivilege(edit.getValue()))) {
				enterable.add(edit.getKey());
			}
		}
		
		proxyReads(true);
		try {
			evaluate(seesFlags, enterable);
			List<NextStep> result = new ArrayList<NextStep>();
			for (Step step : steps.values()) {
				if (shown(step.key, enterable)) {
					result.add(step.toNextStep(form(step.key), referralType(step.key)));
				}
			}
			Collections.sort(result, ORDER);
			return result;
		}
		finally {
			proxyReads(false);
		}
	}
	
	private static void proxyReads(boolean add) {
		for (String privilege : READS) {
			if (add) {
				Context.addProxyPrivilege(privilege);
			} else {
				Context.removeProxyPrivilege(privilege);
			}
		}
	}
	
	private void read() {
		types = new HashMap<String, EncounterType>();
		for (Map.Entry<String, String> entry : pairs(GP_ENCOUNTER_TYPES).entrySet()) {
			EncounterType type = Context.getEncounterService().getEncounterTypeByUuid(entry.getValue());
			if (type != null) {
				types.put(entry.getKey(), type);
			}
		}
		encounters = new ArrayList<Encounter>();
		for (Encounter encounter : Context.getEncounterService().getEncountersByPatient(patient)) {
			if (!encounter.getVoided()) {
				encounters.add(encounter);
			}
		}
		Collections.sort(encounters, BY_DATE);
		// From the database, not each encounter's obs set, which can lag obs saved on their own.
		obsByEncounter = new HashMap<Encounter, List<Obs>>();
		for (Obs obs : Context.getObsService().getObservationsByPerson(patient)) {
			if (!obs.getVoided() && obs.getEncounter() != null) {
				if (!obsByEncounter.containsKey(obs.getEncounter())) {
					obsByEncounter.put(obs.getEncounter(), new ArrayList<Obs>());
				}
				obsByEncounter.get(obs.getEncounter()).add(obs);
			}
		}
		referTriggers = new ArrayList<Encounter>();
		referFlagSince = null;
		thisVisit = new HashSet<Encounter>();
		savedThisVisit = new HashSet<String>();
		Visit visit = activeVisit();
		if (visit != null) {
			for (Encounter encounter : encounters) {
				// Keep the today filter: the ACT app never ends a visit, so one visit can hold weeks of forms.
				if (visit.equals(encounter.getVisit()) && day(encounter.getEncounterDatetime()).equals(today)) {
					thisVisit.add(encounter);
					for (Map.Entry<String, EncounterType> type : types.entrySet()) {
						if (type.getValue().equals(encounter.getEncounterType())) {
							savedThisVisit.add(type.getKey());
						}
					}
				}
			}
		}
	}
	
	/**
	 * Tells whether the user is shown the step, which is so for a referral and for a form the user may
	 * record.
	 */
	private static boolean shown(String key, Set<String> enterable) {
		return "refer".equals(key) || enterable.contains(key);
	}
	
	private void evaluate(boolean seesFlags, Set<String> enterable) {
		boolean mayConsult = enterable.contains("consult");
		Set<String> flags = seesFlags ? activeFlags() : Collections.<String> emptySet();
		Map<String, String> flagKeys = pairs(GP_FLAGS);
		
		dose();
		if (latest("patientInfo") == null) {
			put("patientInfo", StepReason.of("patientInfoNotRecorded"), false);
		}
		if (flags.contains(flagKeys.get("notPrescribed"))) {
			if (mayConsult) {
				put("consult", StepReason.of("notPrescribed"), false);
			} else if (types.containsKey("consult")) {
				steps.put("refer",
				    new Step("refer", StepReason.of("notPrescribedReferral"), false, savedThisVisit.contains("consult")));
				if (latest("consult") != null) {
					referTriggers.add(latest("consult"));
				}
				referFlagSince = raisedSince(flagKeys.get("notPrescribed"));
			}
		}
		if (mayConsult && !steps.containsKey("consult")) {
			reviewOverdue();
		}
		if ("F".equals(patient.getGender()) && flags.contains(flagKeys.get("deliveryOverdue"))) {
			put("pregnancy", StepReason.of("deliveryOverdue"), false);
		}
		echo();
		if (flags.contains(flagKeys.get("followUp"))) {
			put("procedures", StepReason.of("followUpDue"), false);
		}
		if (flags.contains(flagKeys.get("inr"))) {
			put("inr", inrReason(), false);
		}
		review(mayConsult, savedThisVisit.contains("echo") ? latest("echo") : null, StepReason.of("reviewEcho"), null);
		Encounter anaphylaxisTrigger = anaphylaxis();
		Review anaphylaxis = review(mayConsult, anaphylaxisTrigger, ANAPHYLAXIS_REVIEW, ANAPHYLAXIS_REFERRAL);
		addReferTrigger(mayConsult, anaphylaxisTrigger, anaphylaxis);
		int below = threshold(GP_ADHERENCE_BELOW, 80);
		Encounter lowEstimate = lowAdherence();
		StepReason lowReview = StepReason.of("lowAdherenceReview", below);
		addReferTrigger(mayConsult, lowEstimate, review(mayConsult, lowEstimate,
		    lowEstimate != null && bpgAnaphylaxis() ? lowReview : StepReason.of("lowAdherenceSwitch", below), lowReview));
		// What was recorded at this visit is shown done, even when no rule asked for it.
		for (String key : FORM_KEYS) {
			if (savedThisVisit.contains(key) && !steps.containsKey(key) && !"bpg".equals(key)) {
				put(key, ENTERED_THIS_VISIT, false);
			}
		}
		Step bpg = steps.get("bpg");
		if (anaphylaxis == Review.NONE && bpg != null && !bpg.done) {
			bpg.reason = StepReason.of(mayConsult ? "onHoldConsult" : "onHoldReview");
			bpg.onHold = true;
			bpg.alert = true;
		}
		// After every rule, as a later rule's reason can replace the anaphylaxis one on the same step.
		for (String key : Arrays.asList("consult", "refer")) {
			Step review = steps.get(key);
			if (anaphylaxis == Review.NONE && review != null && !review.done
			        && (ANAPHYLAXIS_REVIEW.equals(review.reason) || ANAPHYLAXIS_REFERRAL.equals(review.reason))) {
				review.alert = true;
			}
		}
		// Only a review this user is listed holds the BPG dose back; Patient Information never does.
		if (!"false".equalsIgnoreCase(StringUtils.trim(property(GP_DOSE_AFTER_EARLIER_STEPS)))) {
			boolean reviewToDo = holdsDose("consult", enterable) || holdsDose("refer", enterable);
			if (reviewToDo && toDo("bpg") && !steps.get("bpg").onHold) {
				steps.remove("bpg");
			}
		}
		Step refer = steps.get("refer");
		if (refer != null && !refer.done) {
			refer.referred = referralSentAfter(referTriggers, referFlagSince);
		}
	}
	
	/**
	 * Notes a trigger that no consultation has reviewed, for a user who may not consult, who can only
	 * refer the patient.
	 */
	private void addReferTrigger(boolean mayConsult, Encounter trigger, Review review) {
		if (!mayConsult && trigger != null && review == Review.NONE) {
			referTriggers.add(trigger);
		}
	}
	
	/**
	 * Gets when the flag was raised for the patient, or null when it is not raised or ACT Core keeps no
	 * date for it.
	 */
	private Date raisedSince(String flagUuid) {
		for (PatientFlag flag : Context.getService(FlagService.class).getPatientFlags(patient)) {
			if (!flag.getVoided() && flag.getFlag() != null && flag.getFlag().getUuid().equals(flagUuid)) {
				return RaisedFlags.raisedSinceByFlag(patient.getPatientId()).get(flag.getFlag().getFlagId());
			}
		}
		return null;
	}
	
	/**
	 * Tells whether a referral was saved after the latest trigger and after the flag was raised. It
	 * compares when each encounter was saved rather than its own date, which can be set to any day.
	 */
	private boolean referralSentAfter(List<Encounter> triggers, Date flagSince) {
		EncounterType type = types.get("refer");
		if (type == null) {
			return false;
		}
		Encounter latestTrigger = null;
		for (Encounter trigger : triggers) {
			if (latestTrigger == null || BY_SAVED.compare(trigger, latestTrigger) > 0) {
				latestTrigger = trigger;
			}
		}
		for (Encounter encounter : encounters) {
			if (type.equals(encounter.getEncounterType())
			        && (latestTrigger == null || BY_SAVED.compare(encounter, latestTrigger) > 0)
			        && (flagSince == null || encounter.getDateCreated().after(flagSince))) {
				return true;
			}
		}
		return false;
	}
	
	/**
	 * Gets the uuid of the encounter type that records the referral, for the referral step. It is null
	 * for any other step, and when no encounter type is mapped to the referral.
	 */
	private String referralType(String key) {
		return "refer".equals(key) && types.containsKey("refer") ? types.get("refer").getUuid() : null;
	}
	
	private boolean toDo(String key) {
		Step step = steps.get(key);
		return step != null && !step.done;
	}
	
	private boolean holdsDose(String key, Set<String> enterable) {
		return toDo(key) && !steps.get(key).routine && shown(key, enterable);
	}
	
	/**
	 * Adds a consultation step when the routine cardiology review is past its date. Unlike a review of
	 * a trigger, an overdue routine review does not hold the dose back.
	 */
	private void reviewOverdue() {
		LocalDate due = NextConsultation.of(patient);
		if (due != null && due.isBefore(today)) {
			put("consult", StepReason.of("overdue", due), false);
			if (steps.containsKey("consult")) {
				steps.get("consult").routine = true;
			}
		}
	}
	
	/**
	 * Adds the new consultation or referral that a trigger calls for, and returns how a consultation
	 * reviewed the trigger, or null when there is no trigger. The step counts as done once this visit
	 * has reviewed every trigger behind it. Its reason is the latest unreviewed trigger's reason, in
	 * rule order, or else the latest trigger's.
	 */
	private Review review(boolean mayConsult, Encounter trigger, StepReason reason, StepReason referral) {
		if (trigger == null) {
			return null;
		}
		Review review = reviewOf(trigger);
		if (review == Review.BEFORE_THIS_VISIT || !types.containsKey("consult")) {
			return review;
		}
		String key = mayConsult || referral == null ? "consult" : "refer";
		Step step = steps.get(key);
		// A new step still done has only reviewed triggers behind it.
		if (step == null || !step.isNew || step.done || review == Review.NONE) {
			if ("consult".equals(key)) {
				put(key, reason, true);
			} else if (step == null) {
				steps.put(key, new Step(key, referral, true, true));
			} else {
				step.reason = referral;
				step.isNew = true;
			}
			step = steps.get(key);
		}
		step.done = step.done && review == Review.THIS_VISIT;
		return review;
	}
	
	private void dose() {
		summary = ProphylaxisSummary.of(patient, today);
		String key = "BPG".equals(summary.getType()) ? "bpg" : "Oral".equals(summary.getType()) ? "oral" : null;
		if (key == null) {
			return;
		}
		StepReason reason = null;
		LocalDate due = summary.getNextDue();
		if (due != null) {
			long days = ChronoUnit.DAYS.between(today, due);
			if (days < 0) {
				reason = StepReason.of("overdue", due);
			} else if (days == 0) {
				reason = StepReason.of("dueToday");
			} else if (days <= threshold(GP_DOSE_WITHIN_DAYS, 2)) {
				reason = days == 1 ? StepReason.of("dueTomorrow") : StepReason.of("dueInDays", days);
			}
		}
		boolean done = "bpg".equals(key) ? injectionThisVisit() : savedThisVisit.contains("oral");
		if (reason != null || done) {
			put(key, reason == null ? ENTERED_THIS_VISIT : reason, false);
		}
	}
	
	private boolean injectionThisVisit() {
		String injectionDate = property(GP_INJECTION_DATE);
		for (Encounter encounter : thisVisit) {
			if (types.containsKey("bpg") && types.get("bpg").equals(encounter.getEncounterType())) {
				// An earlier dose recorded today, such as from a paper record, is not today's dose.
				for (Obs obs : obsOf(encounter)) {
					if (obs.getConcept().getUuid().equals(injectionDate) && obs.getValueDatetime() != null
					        && day(obs.getValueDatetime()).equals(today)) {
						return true;
					}
				}
			}
		}
		return false;
	}
	
	private void echo() {
		String echoDate = property(GP_ECHO_DATE);
		LocalDate last = null;
		for (Encounter encounter : encounters) {
			if (thisVisit.contains(encounter)) {
				continue;
			}
			LocalDate dated = null;
			for (Obs obs : obsOf(encounter)) {
				if (obs.getConcept().getUuid().equals(echoDate) && obs.getValueDatetime() != null) {
					LocalDate day = day(obs.getValueDatetime());
					dated = dated == null || day.isAfter(dated) ? day : dated;
				}
			}
			if (dated == null && types.containsKey("echo") && types.get("echo").equals(encounter.getEncounterType())) {
				dated = day(encounter.getEncounterDatetime());
			}
			if (dated != null && (last == null || dated.isAfter(last))) {
				last = dated;
			}
		}
		if (savedThisVisit.contains("echo")) {
			put("echo", ENTERED_THIS_VISIT, false);
		} else if (last == null) {
			put("echo", StepReason.of("noEcho"), false);
		} else if (last.isBefore(today.minusMonths(threshold(GP_ECHO_MONTHS, 12)))) {
			put("echo", StepReason.of("echoOverdue", threshold(GP_ECHO_MONTHS, 12), last), false);
		}
	}
	
	private StepReason inrReason() {
		String nextInr = property(GP_NEXT_INR_DATE);
		Obs latest = null;
		for (Encounter encounter : encounters) {
			for (Obs obs : obsOf(encounter)) {
				if (obs.getConcept().getUuid().equals(nextInr) && obs.getValueDatetime() != null
				        && (latest == null || !obs.getObsDatetime().before(latest.getObsDatetime()))) {
					latest = obs;
				}
			}
		}
		if (latest == null) {
			return StepReason.of("inrDue");
		}
		LocalDate due = day(latest.getValueDatetime());
		return StepReason.of(due.isBefore(today) ? "inrOverdue" : "inrDueOn", due);
	}
	
	/**
	 * Finds the latest BPG form that reported anaphylaxis, even when a later BPG form follows it, since
	 * only a consultation reviews an anaphylaxis. It is null when no BPG form reported one.
	 */
	private Encounter anaphylaxis() {
		String anaphylaxis = property(GP_ANAPHYLAXIS);
		Encounter latest = null;
		for (Encounter encounter : encounters) {
			if (types.containsKey("bpg") && types.get("bpg").equals(encounter.getEncounterType())) {
				for (Obs obs : obsOf(encounter)) {
					if (obs.getValueCoded() != null && obs.getValueCoded().getUuid().equals(anaphylaxis)) {
						latest = encounter;
					}
				}
			}
		}
		return latest;
	}
	
	/**
	 * Tells whether the patient has had anaphylaxis with BPG: a BPG form reported it, or an anaphylaxis
	 * answer sits in a prescription group whose regimen is an injection. Switching to BPG is then not a
	 * step to suggest.
	 */
	private boolean bpgAnaphylaxis() {
		if (anaphylaxis() != null) {
			return true;
		}
		String answer = property(GP_ANAPHYLAXIS);
		String prescription = property(GP_PRESCRIPTION);
		Set<String> injections = pairs(GP_INJECTION_INTERVALS).keySet();
		for (Encounter encounter : encounters) {
			for (Obs obs : obsOf(encounter)) {
				if (obs.getValueCoded() == null || !obs.getValueCoded().getUuid().equals(answer)
				        || obs.getObsGroup() == null) {
					continue;
				}
				for (Obs member : obs.getObsGroup().getGroupMembers()) {
					if (member.getConcept().getUuid().equals(prescription) && member.getValueCoded() != null
					        && injections.contains(member.getValueCoded().getUuid())) {
						return true;
					}
				}
			}
		}
		return false;
	}
	
	/**
	 * Finds the form of the latest oral adherence estimate when the patient is on an oral regimen and
	 * that estimate is below the threshold. It is null otherwise.
	 */
	private Encounter lowAdherence() {
		String estimate = property(GP_ESTIMATE);
		Obs latest = null;
		for (Encounter encounter : encounters) {
			for (Obs obs : obsOf(encounter)) {
				if (obs.getConcept().getUuid().equals(estimate) && obs.getValueNumeric() != null && (latest == null
				        || !encounter.getEncounterDatetime().before(latest.getEncounter().getEncounterDatetime()))) {
					latest = obs;
				}
			}
		}
		return "Oral".equals(summary.getType()) && latest != null
		        && latest.getValueNumeric() < threshold(GP_ADHERENCE_BELOW, 80) ? latest.getEncounter() : null;
	}
	
	/**
	 * Tells how a consultation after the trigger, ordered by BY_DATE, reviewed it: in an encounter
	 * outside this visit, in this visit, or not at all.
	 */
	private Review reviewOf(Encounter trigger) {
		EncounterType consult = types.get("consult");
		Review review = Review.NONE;
		for (Encounter encounter : encounters) {
			if (consult != null && consult.equals(encounter.getEncounterType()) && BY_DATE.compare(encounter, trigger) > 0) {
				if (!thisVisit.contains(encounter)) {
					return Review.BEFORE_THIS_VISIT;
				}
				review = Review.THIS_VISIT;
			}
		}
		return review;
	}
	
	private Encounter latest(String key) {
		Encounter latest = null;
		for (Encounter encounter : encounters) {
			if (types.containsKey(key) && types.get(key).equals(encounter.getEncounterType())) {
				latest = encounter;
			}
		}
		return latest;
	}
	
	/**
	 * Adds the step for the form with its reason, or updates the step when an earlier rule added it, so
	 * that a later rule's reason replaces the earlier one's. A form whose encounter type is not mapped
	 * gets no step.
	 */
	private void put(String key, StepReason reason, boolean isNew) {
		if (!types.containsKey(key)) {
			return;
		}
		boolean done = "bpg".equals(key) ? injectionThisVisit() : savedThisVisit.contains(key);
		Step step = steps.get(key);
		if (step == null) {
			steps.put(key, new Step(key, reason, isNew, done));
		} else {
			step.reason = reason;
			step.isNew = isNew;
			step.routine = false;
		}
	}
	
	private Set<String> activeFlags() {
		Set<String> active = new HashSet<String>();
		for (PatientFlag flag : Context.getService(FlagService.class).getPatientFlags(patient)) {
			if (!flag.getVoided() && flag.getFlag() != null) {
				active.add(flag.getFlag().getUuid());
			}
		}
		return active;
	}
	
	private Visit activeVisit() {
		Visit latest = null;
		for (Visit visit : Context.getVisitService().getActiveVisitsByPatient(patient)) {
			if (latest == null || visit.getStartDatetime().after(latest.getStartDatetime())) {
				latest = visit;
			}
		}
		return latest;
	}
	
	/**
	 * Gets the uuid of the newest unretired form of the step's encounter type, or null when there is
	 * none.
	 */
	private String form(String key) {
		EncounterType type = types.get(key);
		Form newest = null;
		if (type != null) {
			for (Form form : Context.getFormService().getAllForms(false)) {
				if (type.equals(form.getEncounterType()) && (newest == null || form.getFormId() > newest.getFormId())) {
					newest = form;
				}
			}
		}
		return newest == null ? null : newest.getUuid();
	}
	
	private List<Obs> obsOf(Encounter encounter) {
		List<Obs> obs = obsByEncounter.get(encounter);
		return obs == null ? Collections.<Obs> emptyList() : obs;
	}
	
	private static LocalDate day(Date date) {
		return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
	}
	
	private static String property(String name) {
		return Context.getAdministrationService().getGlobalProperty(name);
	}
	
	private static int threshold(String name, int fallback) {
		String value = property(name);
		return NumberUtils.toInt(StringUtils.trimToEmpty(value), fallback);
	}
	
	/**
	 * Reads a global property that lists key:value pairs separated by commas, as a map. A pair that is
	 * not of that shape is skipped.
	 */
	private static Map<String, String> pairs(String name) {
		AdministrationService admin = Context.getAdministrationService();
		Map<String, String> pairs = new HashMap<String, String>();
		for (String pair : StringUtils.split(StringUtils.defaultString(admin.getGlobalProperty(name)), ',')) {
			String[] parts = StringUtils.split(pair, ':');
			if (parts.length == 2) {
				pairs.put(parts[0].trim(), parts[1].trim());
			}
		}
		return pairs;
	}
	
	private static final Comparator<Encounter> BY_DATE = new Comparator<Encounter>() {
		
		@Override
		public int compare(Encounter a, Encounter b) {
			int byDate = a.getEncounterDatetime().compareTo(b.getEncounterDatetime());
			return byDate != 0 ? byDate : a.getEncounterId().compareTo(b.getEncounterId());
		}
	};
	
	private static final Comparator<Encounter> BY_SAVED = new Comparator<Encounter>() {
		
		@Override
		public int compare(Encounter a, Encounter b) {
			int bySaved = a.getDateCreated().compareTo(b.getDateCreated());
			return bySaved != 0 ? bySaved : a.getEncounterId().compareTo(b.getEncounterId());
		}
	};
	
	private static final Comparator<NextStep> ORDER = new Comparator<NextStep>() {
		
		@Override
		public int compare(NextStep a, NextStep b) {
			if (a.isDone() != b.isDone()) {
				return a.isDone() ? 1 : -1;
			}
			if (!a.isDone() && a.isNew() != b.isNew()) {
				return a.isNew() ? -1 : 1;
			}
			return 0;
		}
	};
	
	/**
	 * How a consultation reviewed a finding: outside this visit, in this visit, or not at all.
	 */
	private enum Review {
		BEFORE_THIS_VISIT,
		THIS_VISIT,
		NONE
	}
	
	/** A step as the rules build it up, before it is turned into a {@link NextStep}. */
	private static final class Step {
		
		private final String key;
		
		private StepReason reason;
		
		private boolean isNew;
		
		private boolean done;
		
		private boolean onHold;
		
		private boolean alert;
		
		private boolean referred;
		
		private boolean routine;
		
		private Step(String key, StepReason reason, boolean isNew, boolean done) {
			this.key = key;
			this.reason = reason;
			this.isNew = isNew;
			this.done = done;
		}
		
		private NextStep toNextStep(String form, String referralType) {
			// A sent referral shows done, but the dose still waits for the consultation.
			boolean shownDone = done || referred;
			// A step on hold names no form, so it offers nothing to record.
			return new NextStep(key, "refer".equals(key) || onHold ? null : form,
			        referred && !done ? StepReason.of("referralSent") : reason, isNew && !shownDone, shownDone, onHold,
			        referralType, alert && !shownDone);
		}
	}
}
