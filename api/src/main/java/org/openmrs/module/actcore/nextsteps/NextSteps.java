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
import java.time.format.DateTimeFormatter;
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
import java.util.Locale;
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
import org.openmrs.module.patientflags.PatientFlag;
import org.openmrs.module.patientflags.PatientFlagsConstants;
import org.openmrs.module.patientflags.api.FlagService;
import org.openmrs.util.PrivilegeConstants;

/**
 * A patient's steps for this visit, from the prototype's rules over their saved forms, ACT Core's
 * dose status and their flags, as the signed-in user may record them.
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
	
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);
	
	private static final Map<String, String> TITLES = new HashMap<String, String>();
	static {
		TITLES.put("bpg", "Give BPG injection");
		TITLES.put("oral", "Record oral prophylaxis visit");
		TITLES.put("consult", "Consultation visit");
		TITLES.put("echo", "Echocardiogram");
		TITLES.put("procedures", "Procedures and outcomes");
		TITLES.put("inr", "INR review");
		TITLES.put("patientInfo", "Patient Information");
		TITLES.put("pregnancy", "Pregnancy");
	}
	
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
	
	private ProphylaxisSummary summary;
	
	private NextSteps(Patient patient, LocalDate today) {
		this.patient = patient;
		this.today = today;
	}
	
	/**
	 * The steps the signed-in user may take: to do before done, new before the rest, else in rule
	 * order.
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
					result.add(step.toNextStep(form(step.key)));
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
	
	/** Whether the user is listed the step: a referral, or a form they may record. */
	private static boolean shown(String key, Set<String> enterable) {
		return "refer".equals(key) || enterable.contains(key);
	}
	
	private void evaluate(boolean seesFlags, Set<String> enterable) {
		boolean mayConsult = enterable.contains("consult");
		Set<String> flags = seesFlags ? activeFlags() : Collections.<String> emptySet();
		Map<String, String> flagKeys = pairs(GP_FLAGS);
		
		dose();
		if (latest("patientInfo") == null) {
			put("patientInfo", "No diagnosis recorded", false);
		}
		if (flags.contains(flagKeys.get("notPrescribed"))) {
			if (mayConsult) {
				put("consult", "No prophylaxis prescribed", false);
			} else if (types.containsKey("consult")) {
				steps.put("refer", new Step("refer", "No prophylaxis prescribed: a clinician to prescribe", false,
				        savedThisVisit.contains("consult")));
			}
		}
		if (mayConsult && !steps.containsKey("consult")) {
			reviewOverdue();
		}
		if ("F".equals(patient.getGender()) && flags.contains(flagKeys.get("deliveryOverdue"))) {
			put("pregnancy", "Delivery outcome overdue", false);
		}
		echo();
		if (flags.contains(flagKeys.get("followUp"))) {
			put("procedures", "30-day follow-up due", false);
		}
		if (flags.contains(flagKeys.get("inr"))) {
			put("inr", inrReason(), false);
		}
		review(mayConsult, savedThisVisit.contains("echo") ? latest("echo") : null,
		    "Review the new echo result and update the care plan", null);
		Review anaphylaxis = review(mayConsult, anaphylaxis(), "Anaphylaxis reported after BPG: review before the next dose",
		    "Anaphylaxis reported: a clinician must review before the next dose");
		String below = "Oral adherence below " + threshold(GP_ADHERENCE_BELOW, 80) + "%: ";
		Encounter lowEstimate = lowAdherence();
		review(mayConsult, lowEstimate, below
		        + (lowEstimate != null && bpgAnaphylaxis() ? "a clinician to review the plan" : "consider switching to BPG"),
		    below + "a clinician to review the plan");
		// What was recorded at this visit is shown done, even when no rule asked for it.
		for (String key : TITLES.keySet()) {
			if (savedThisVisit.contains(key) && !steps.containsKey(key) && !"bpg".equals(key)) {
				put(key, "Entered this visit", false);
			}
		}
		Step bpg = steps.get("bpg");
		if (anaphylaxis == Review.NONE && bpg != null && !bpg.done) {
			bpg.reason = mayConsult ? "On hold: complete the consultation before giving BPG"
			        : "On hold: a clinician must review before the next dose";
			bpg.onHold = true;
		}
		// Only a review this user is listed holds the BPG dose back; Patient Information never does.
		if (!"false".equalsIgnoreCase(StringUtils.trim(property(GP_DOSE_AFTER_EARLIER_STEPS)))) {
			boolean reviewToDo = holdsDose("consult", enterable) || holdsDose("refer", enterable);
			if (reviewToDo && toDo("bpg") && !steps.get("bpg").onHold) {
				steps.remove("bpg");
			}
		}
	}
	
	private boolean toDo(String key) {
		Step step = steps.get(key);
		return step != null && !step.done;
	}
	
	private boolean holdsDose(String key, Set<String> enterable) {
		return toDo(key) && !steps.get(key).routine && shown(key, enterable);
	}
	
	/**
	 * A routine review past its date, which does not hold the dose back as a review of a trigger does.
	 */
	private void reviewOverdue() {
		LocalDate due = NextConsultation.of(patient);
		if (due != null && due.isBefore(today)) {
			put("consult", "Overdue: was due " + DAY.format(due), false);
			if (steps.containsKey("consult")) {
				steps.get("consult").routine = true;
			}
		}
	}
	
	/**
	 * Adds the trigger's new consultation or referral, done once this visit reviewed every trigger,
	 * with the latest unreviewed trigger's reason in rule order, else the latest trigger's.
	 */
	private Review review(boolean mayConsult, Encounter trigger, String reason, String referral) {
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
		String reason = null;
		LocalDate due = summary.getNextDue();
		if (due != null) {
			long days = ChronoUnit.DAYS.between(today, due);
			if (days < 0) {
				reason = "Overdue: was due " + DAY.format(due);
			} else if (days == 0) {
				reason = "Due today";
			} else if (days <= threshold(GP_DOSE_WITHIN_DAYS, 2)) {
				reason = days == 1 ? "Due tomorrow" : "Due in " + days + " days";
			}
		}
		boolean done = "bpg".equals(key) ? injectionThisVisit() : savedThisVisit.contains("oral");
		if (reason != null || done) {
			put(key, reason == null ? "Entered this visit" : reason, false);
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
			put("echo", "Entered this visit", false);
		} else if (last == null) {
			put("echo", "No echocardiogram recorded", false);
		} else if (last.isBefore(today.minusMonths(threshold(GP_ECHO_MONTHS, 12)))) {
			put("echo", "Last echo was over " + threshold(GP_ECHO_MONTHS, 12) + " months ago (" + DAY.format(last) + ")",
			    false);
		}
	}
	
	private String inrReason() {
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
			return "INR review due";
		}
		LocalDate due = day(latest.getValueDatetime());
		return due.isBefore(today) ? "INR review overdue: was due " + DAY.format(due) : "INR review due " + DAY.format(due);
	}
	
	/**
	 * The latest BPG form reporting anaphylaxis, even with a later BPG form: only a consultation
	 * reviews it.
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
	 * Whether a BPG form reported anaphylaxis, or an anaphylaxis answer sits in a prescription group
	 * whose regimen is an injection: BPG is then not a switch to suggest.
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
	
	/** On an oral regimen, the latest oral estimate's form, if the estimate is below the threshold. */
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
	 * Whether a consultation after the trigger, by BY_DATE, was saved outside this visit, in it, or
	 * not.
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
	
	/** A later rule's reason for the same form replaces an earlier one's. */
	private void put(String key, String reason, boolean isNew) {
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
	
	/** The newest unretired form of the step's encounter type. */
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
	
	/** A global property of key:value pairs, separated by commas. */
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
	
	private enum Review {
		BEFORE_THIS_VISIT,
		THIS_VISIT,
		NONE
	}
	
	private static final class Step {
		
		private final String key;
		
		private String reason;
		
		private boolean isNew;
		
		private boolean done;
		
		private boolean onHold;
		
		private boolean routine;
		
		private Step(String key, String reason, boolean isNew, boolean done) {
			this.key = key;
			this.reason = reason;
			this.isNew = isNew;
			this.done = done;
		}
		
		private NextStep toNextStep(String form) {
			// A step on hold names no form, so it offers nothing to record.
			return new NextStep(key, "refer".equals(key) || onHold ? null : form,
			        "refer".equals(key) ? "Refer to clinician" : TITLES.get(key), reason, isNew && !done, done, onHold);
		}
	}
}
