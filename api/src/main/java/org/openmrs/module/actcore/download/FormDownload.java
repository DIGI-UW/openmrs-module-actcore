/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.download;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.openmrs.Concept;
import org.openmrs.ConceptName;
import org.openmrs.Encounter;
import org.openmrs.Form;
import org.openmrs.Location;
import org.openmrs.Obs;
import org.openmrs.Patient;
import org.openmrs.PatientIdentifier;
import org.openmrs.PatientIdentifierType;
import org.openmrs.PatientProgram;
import org.openmrs.PersonAttribute;
import org.openmrs.PersonAttributeType;
import org.openmrs.Program;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.logs.ActivityLog;
import org.openmrs.module.actcore.registry.EnrolOnRegistration;
import org.openmrs.module.actcore.studies.StudyEnrolmentRules;
import org.openmrs.parameter.EncounterSearchCriteriaBuilder;
import org.openmrs.util.OpenmrsUtil;
import org.openmrs.util.PrivilegeConstants;

/**
 * Builds the tables behind ACT 2.0's Download, which exports saved forms as CSV. A table has either
 * a row for each save of one form, or a row for each patient across several forms. Each row starts
 * with the patient's ACT ID, as ACT 2.0's rows started with the registry id, followed by the
 * patient fields chosen and then the form's columns.
 * <p>
 * A download without identifiers leaves out the patient fields, identifier types, attribute types
 * and questions that identify the patient, as the global properties named here configure them.
 */
public class FormDownload {
	
	/**
	 * The name of the global property listing, by uuid, the identifier types that identify a patient,
	 * such as the National ID. A download without identifiers leaves them out, and the ACT ID is never
	 * taken from one of them.
	 */
	public static final String IDENTIFIER_TYPES_PROPERTY = "actcore.download.identifyingIdentifierTypes";
	
	/**
	 * The name of the global property listing, by uuid, the person attribute types that identify a
	 * patient, such as the phone numbers. Only a download with identifiers gives them.
	 */
	public static final String ATTRIBUTE_TYPES_PROPERTY = "actcore.download.identifyingAttributeTypes";
	
	/**
	 * The name of the global property listing, by uuid, the questions that identify a patient, such as
	 * the Home Address. A download without identifiers leaves them out.
	 */
	public static final String CONCEPTS_PROPERTY = "actcore.download.identifyingConcepts";
	
	private static final int OBS_BATCH = 500;
	
	/**
	 * The patient fields a request may choose, as their keys mapped to their column labels, in the
	 * order their columns take. The ACT ID is not among them, since every row starts with it. The
	 * identifiers and phones fields each give a column per type that the identifying properties name.
	 */
	public static final Map<String, String> PATIENT_FIELDS = new LinkedHashMap<String, String>();
	
	/**
	 * The keys of the patient fields that identify the patient, which only a download with identifiers
	 * gives.
	 */
	public static final List<String> IDENTIFYING_PATIENT_FIELDS = Arrays.asList("givenName", "familyName", "birthDate",
	    "identifiers", "phones");
	
	static {
		PATIENT_FIELDS.put("givenName", "Given name");
		PATIENT_FIELDS.put("familyName", "Family name");
		PATIENT_FIELDS.put("birthDate", "Birth date");
		PATIENT_FIELDS.put("identifiers", "ID numbers");
		PATIENT_FIELDS.put("phones", "Phone numbers");
		PATIENT_FIELDS.put("sex", "Sex");
		PATIENT_FIELDS.put("age", "Age");
	}
	
	/**
	 * A built download: the CSV's header row and its rows, with what was given and what was left out.
	 */
	public static class Table {
		
		/** The CSV's column headers, in order. */
		public final List<String> headers = new ArrayList<String>();
		
		/** The CSV's rows, each a cell for each header, empty where there is no value. */
		public final List<List<String>> rows = new ArrayList<List<String>>();
		
		/**
		 * The labels of the questions asked for that were left out because they identify the patient. A row
		 * per patient prefixes each label with its form's name.
		 */
		public final List<String> withheld = new ArrayList<String>();
		
		/** The keys of the patient fields given, in the order of their columns. */
		public final List<String> patient = new ArrayList<String>();
		
		/**
		 * The keys of the patient fields asked for that were left out because they identify the patient.
		 */
		public final List<String> withheldPatient = new ArrayList<String>();
		
		/** Whether the download gave a patient field or a question that identifies the patient. */
		public boolean identifiers;
	}
	
	/**
	 * A form chosen for a row per patient, with the questions chosen of it. The name heads the form's
	 * groups of columns.
	 */
	public static class Choice {
		
		/** The form whose saves to give. */
		public final Form form;
		
		/** The name that prefixes the form's column headers, followed by the number of the save. */
		public final String name;
		
		/** The questions chosen of the form, a column each. */
		public final List<Concept> concepts;
		
		/** Each question's column label, in the order of {@link #concepts}. */
		public final List<String> labels;
		
		public Choice(Form form, String name, List<Concept> concepts, List<String> labels) {
			this.form = form;
			this.name = name;
			this.concepts = concepts;
			this.labels = labels;
		}
	}
	
	/**
	 * The filters ACT 2.0's Download offered for choosing which patients' saves to give: their status
	 * in the registry, their cardiac clinic and their primary care clinic. A filter left null is not
	 * applied.
	 * <p>
	 * Make a new instance for each download, since it remembers each patient's result for as long as it
	 * lives.
	 */
	public static class Filters {
		
		/**
		 * The status to keep: active for a living patient whose latest RHD Registry enrolment is open,
		 * inactive for anyone else, or null for both.
		 */
		public final String status;
		
		/** The cardiac clinic a patient must be recorded at, or null for any. */
		public final Location cardiacClinic;
		
		/** The primary care clinic a patient must be recorded at, or null for any. */
		public final Location primaryCareClinic;
		
		private Program registry;
		
		private List<String> clinicTypes;
		
		private final Map<Integer, Boolean> kept = new HashMap<Integer, Boolean>();
		
		public Filters(String status, Location cardiacClinic, Location primaryCareClinic) {
			this.status = status;
			this.cardiacClinic = cardiacClinic;
			this.primaryCareClinic = primaryCareClinic;
		}
		
		/**
		 * Tells whether the filters keep the patient. The answer is worked out once per patient and then
		 * remembered, since each of the patient's saves asks again and working out a status costs a query.
		 */
		boolean matches(Patient patient) {
			return kept.computeIfAbsent(patient.getPatientId(), id -> {
				if (status != null && active(patient) != "active".equals(status)) {
					return false;
				}
				// The order actcore.logs.clinicAttributeTypes's description sets: primary care, then cardiac.
				return atClinic(patient, 0, primaryCareClinic) && atClinic(patient, 1, cardiacClinic);
			});
		}
		
		/**
		 * Tells whether the patient is active as the distribution defines it: alive, with their latest RHD
		 * Registry enrolment still open.
		 */
		private boolean active(Patient patient) {
			if (Boolean.TRUE.equals(patient.getDead())) {
				return false;
			}
			if (registry == null) {
				registry = programByUuid(
				    Context.getAdministrationService().getGlobalProperty(EnrolOnRegistration.PROGRAM_PROPERTY));
			}
			if (registry == null) {
				return false;
			}
			PatientProgram latest = null;
			for (PatientProgram enrolment : Context.getProgramWorkflowService().getPatientPrograms(patient, registry, null,
			    null, null, null, false)) {
				if (latest == null || later(enrolment, latest)) {
					latest = enrolment;
				}
			}
			return latest != null && latest.getDateCompleted() == null;
		}
		
		private boolean atClinic(Patient patient, int which, Location clinic) {
			if (clinic == null) {
				return true;
			}
			if (clinicTypes == null) {
				clinicTypes = uuids(ActivityLog.CLINIC_ATTRIBUTE_TYPES_PROPERTY);
			}
			PersonAttributeType type = which < clinicTypes.size() ? attributeType(clinicTypes.get(which)) : null;
			PersonAttribute attribute = type == null ? null : patient.getAttribute(type);
			return attribute != null && clinic.getLocationId().toString().equals(attribute.getValue());
		}
		
		private static boolean later(PatientProgram a, PatientProgram b) {
			int byDate = a.getDateEnrolled().compareTo(b.getDateEnrolled());
			return byDate != 0 ? byDate > 0 : a.getPatientProgramId() > b.getPatientProgramId();
		}
	}
	
	/**
	 * Builds a table with a row for each save of the form between the days, for every patient, and a
	 * column for each question chosen. Only a download with identifiers gives the names, birth date, ID
	 * numbers, phone numbers and identifying questions; without them, the patient fields are sex and
	 * age.
	 *
	 * @param form the form whose unvoided saves to list
	 * @param asked the questions chosen, a column each
	 * @param askedLabels each question's column label, in the order of asked
	 * @param from the first day, or null for no start
	 * @param to the last day, or null for no end
	 * @param identified whether the rows may give the patient fields and questions that identify the
	 *            patient
	 * @param researchOnly whether to keep only the patients who answered Yes to the research opt-in;
	 *            nobody is kept when the opt-in or Yes is not configured
	 * @return the table, a row per save, oldest first, leaving out voided patients; no rows when no
	 *         save matches
	 */
	public Table build(Form form, List<Concept> asked, List<String> askedLabels, LocalDate from, LocalDate to,
	        boolean identified, boolean researchOnly) {
		return build(form, asked, askedLabels, from, to, identified, researchOnly, new Filters(null, null, null));
	}
	
	/**
	 * Builds a table with a row for each save of the form between the days, as
	 * {@link #build(Form, List, List, LocalDate, LocalDate, boolean, boolean)} does, but only for the
	 * patients the filters keep.
	 *
	 * @param form the form whose unvoided saves to list
	 * @param asked the questions chosen, a column each
	 * @param askedLabels each question's column label, in the order of asked
	 * @param from the first day, or null for no start
	 * @param to the last day, or null for no end
	 * @param identified whether the rows may give the patient fields and questions that identify the
	 *            patient
	 * @param researchOnly whether to keep only the patients who answered Yes to the research opt-in;
	 *            nobody is kept when the opt-in or Yes is not configured
	 * @param filters the status and clinics to keep patients by
	 * @return the table, a row per save, oldest first, leaving out voided patients; no rows when no
	 *         save matches
	 */
	public Table build(Form form, List<Concept> asked, List<String> askedLabels, LocalDate from, LocalDate to,
	        boolean identified, boolean researchOnly, Filters filters) {
		return build(form, asked, askedLabels, from, to, identified, researchOnly, filters, null);
	}
	
	/**
	 * Builds a table with a row for each save of the form between the days, for the patients the
	 * filters keep, giving the patient fields chosen. Without a choice, it gives every patient field
	 * when the download has identifiers, and sex and age when it does not.
	 *
	 * @param form the form whose unvoided saves to list
	 * @param asked the questions chosen, a column each
	 * @param askedLabels each question's column label, in the order of asked
	 * @param from the first day, or null for no start
	 * @param to the last day, or null for no end
	 * @param identified whether the rows may give the patient fields and questions that identify the
	 *            patient
	 * @param researchOnly whether to keep only the patients who answered Yes to the research opt-in;
	 *            nobody is kept when the opt-in or Yes is not configured
	 * @param filters the status and clinics to keep patients by
	 * @param chosen the keys of the {@link #PATIENT_FIELDS} to give, or null for sex and age, and every
	 *            field when identified
	 * @return the table, a row per save, oldest first, leaving out voided patients; no rows when no
	 *         save matches
	 */
	public Table build(Form form, List<Concept> asked, List<String> askedLabels, LocalDate from, LocalDate to,
	        boolean identified, boolean researchOnly, Filters filters, List<String> chosen) {
		Locale locale = Context.getLocale();
		List<PatientIdentifierType> identifying = identifierTypes();
		List<PersonAttributeType> attributeTypes = identified ? attributeTypes() : Collections.emptyList();
		
		Table table = new Table();
		patientFields(table, chosen, identified);
		List<String> identifyingConcepts = uuids(CONCEPTS_PROPERTY);
		List<Concept> concepts = new ArrayList<Concept>();
		List<String> labels = new ArrayList<String>();
		for (int i = 0; i < asked.size(); i++) {
			boolean question = identifyingConcepts.contains(asked.get(i).getUuid());
			if (question && !identified) {
				table.withheld.add(askedLabels.get(i));
			} else {
				table.identifiers |= question;
				concepts.add(asked.get(i));
				labels.add(askedLabels.get(i));
			}
		}
		patientHeaders(table, identifying, attributeTypes);
		table.headers.addAll(Arrays.asList("Form date", "Location"));
		table.headers.addAll(labels);
		
		List<Encounter> encounters = encounters(form, from, to, researchOnly, filters);
		Map<Integer, Map<Integer, List<Obs>>> answers = answers(encounters, concepts);
		for (Encounter encounter : encounters) {
			Patient patient = encounter.getPatient();
			List<String> row = new ArrayList<String>();
			Integer age = patient.getBirthdate() == null ? null : patient.getAge(encounter.getEncounterDatetime());
			patientCells(row, table.patient, patient, age, identifying, attributeTypes);
			row.add(date(encounter.getEncounterDatetime()));
			row.add(encounter.getLocation() == null ? "" : encounter.getLocation().getName());
			Map<Integer, List<Obs>> byConcept = answers.getOrDefault(encounter.getEncounterId(), Collections.emptyMap());
			for (Concept concept : concepts) {
				row.add(joined(byConcept.getOrDefault(concept.getConceptId(), Collections.emptyList()), locale));
			}
			table.rows.add(row);
		}
		return table;
	}
	
	/**
	 * Builds a table with a row for each patient, as ACT 2.0's row per patient did. Each chosen form
	 * gives numbered groups of columns, one group for each of a patient's saves between the days,
	 * oldest first, as many groups as the patient with the most saves needs. The rows are of the
	 * patients the filters keep who saved at least one of the forms in the range.
	 *
	 * @param choices the forms and the questions chosen of each
	 * @param from the first day, or null for no start
	 * @param to the last day, or null for no end
	 * @param identified whether the rows may give the patient fields and questions that identify the
	 *            patient
	 * @param researchOnly whether to keep only the patients who answered Yes to the research opt-in;
	 *            nobody is kept when the opt-in or Yes is not configured
	 * @param filters the status and clinics to keep patients by
	 * @return the table, a row per patient by ACT ID, leaving out voided patients; no rows when no save
	 *         matches, while each form still heads one group of columns
	 */
	public Table buildPerPatient(List<Choice> choices, LocalDate from, LocalDate to, boolean identified,
	        boolean researchOnly, Filters filters) {
		return buildPerPatient(choices, from, to, identified, researchOnly, filters, null);
	}
	
	/**
	 * Builds a table with a row for each patient across the chosen forms, as
	 * {@link #buildPerPatient(List, LocalDate, LocalDate, boolean, boolean, Filters)} does, giving the
	 * patient fields chosen. Without a choice, it gives every patient field when the download has
	 * identifiers, and sex and age when it does not.
	 *
	 * @param choices the forms and the questions chosen of each
	 * @param from the first day, or null for no start
	 * @param to the last day, or null for no end
	 * @param identified whether the rows may give the patient fields and questions that identify the
	 *            patient
	 * @param researchOnly whether to keep only the patients who answered Yes to the research opt-in;
	 *            nobody is kept when the opt-in or Yes is not configured
	 * @param filters the status and clinics to keep patients by
	 * @param chosen the keys of the {@link #PATIENT_FIELDS} to give, or null for sex and age, and every
	 *            field when identified
	 * @return the table, a row per patient by ACT ID, leaving out voided patients; no rows when no save
	 *         matches, while each form still heads one group of columns
	 */
	public Table buildPerPatient(List<Choice> choices, LocalDate from, LocalDate to, boolean identified,
	        boolean researchOnly, Filters filters, List<String> chosen) {
		Locale locale = Context.getLocale();
		List<PatientIdentifierType> identifying = identifierTypes();
		List<PersonAttributeType> attributeTypes = identified ? attributeTypes() : Collections.emptyList();
		List<String> identifyingConcepts = uuids(CONCEPTS_PROPERTY);
		Table table = new Table();
		patientFields(table, chosen, identified);
		patientHeaders(table, identifying, attributeTypes);
		
		Map<Integer, Patient> patients = new LinkedHashMap<Integer, Patient>();
		List<List<Concept>> concepts = new ArrayList<List<Concept>>();
		List<Map<Integer, List<Encounter>>> saves = new ArrayList<Map<Integer, List<Encounter>>>();
		List<Map<Integer, Map<Integer, List<Obs>>>> answers = new ArrayList<Map<Integer, Map<Integer, List<Obs>>>>();
		List<Integer> groups = new ArrayList<Integer>();
		for (Choice choice : choices) {
			List<Concept> given = new ArrayList<Concept>();
			List<String> labels = new ArrayList<String>();
			for (int i = 0; i < choice.concepts.size(); i++) {
				boolean question = identifyingConcepts.contains(choice.concepts.get(i).getUuid());
				if (question && !identified) {
					table.withheld.add(choice.name + ": " + choice.labels.get(i));
				} else {
					table.identifiers |= question;
					given.add(choice.concepts.get(i));
					labels.add(choice.labels.get(i));
				}
			}
			List<Encounter> encounters = encounters(choice.form, from, to, researchOnly, filters);
			Map<Integer, List<Encounter>> byPatient = new LinkedHashMap<Integer, List<Encounter>>();
			for (Encounter encounter : encounters) {
				patients.putIfAbsent(encounter.getPatient().getPatientId(), encounter.getPatient());
				byPatient.computeIfAbsent(encounter.getPatient().getPatientId(), id -> new ArrayList<>()).add(encounter);
			}
			// At least one group, so a form no kept patient saved still heads its columns.
			int most = Math.max(1, byPatient.values().stream().mapToInt(List::size).max().orElse(0));
			groups.add(most);
			for (int n = 1; n <= most; n++) {
				String prefix = choice.name + " " + n + ": ";
				table.headers.add(prefix + "Form date");
				table.headers.add(prefix + "Location");
				labels.forEach(label -> table.headers.add(prefix + label));
			}
			concepts.add(given);
			saves.add(byPatient);
			answers.add(answers(encounters, given));
		}
		
		List<Patient> ordered = new ArrayList<Patient>(patients.values());
		ordered.sort(
		    Comparator.comparing((Patient patient) -> actId(patient, identifying)).thenComparing(Patient::getPatientId));
		for (Patient patient : ordered) {
			List<String> row = new ArrayList<String>();
			patientCells(row, table.patient, patient, patient.getAge(), identifying, attributeTypes);
			for (int c = 0; c < choices.size(); c++) {
				List<Encounter> theirs = saves.get(c).getOrDefault(patient.getPatientId(), Collections.emptyList());
				for (int n = 0; n < groups.get(c); n++) {
					Encounter encounter = n < theirs.size() ? theirs.get(n) : null;
					row.add(encounter == null ? "" : date(encounter.getEncounterDatetime()));
					row.add(encounter == null || encounter.getLocation() == null ? "" : encounter.getLocation().getName());
					Map<Integer, List<Obs>> byConcept = encounter == null ? Collections.emptyMap()
					        : answers.get(c).getOrDefault(encounter.getEncounterId(), Collections.emptyMap());
					for (Concept concept : concepts.get(c)) {
						row.add(joined(byConcept.getOrDefault(concept.getConceptId(), Collections.emptyList()), locale));
					}
				}
			}
			table.rows.add(row);
		}
		return table;
	}
	
	/**
	 * Records on the table which patient fields it gives, in the order of PATIENT_FIELDS, which it
	 * withholds, and whether one it gives identifies the patient. Without a choice it gives the fields
	 * given before they could be chosen: every field when the download has identifiers, and sex and age
	 * when it does not.
	 */
	private static void patientFields(Table table, List<String> asked, boolean identified) {
		for (String key : PATIENT_FIELDS.keySet()) {
			boolean identifying = IDENTIFYING_PATIENT_FIELDS.contains(key);
			if (asked == null ? identified || !identifying : asked.contains(key)) {
				if (identifying && !identified) {
					table.withheldPatient.add(key);
				} else {
					table.patient.add(key);
					table.identifiers |= identifying;
				}
			}
		}
	}
	
	private static void patientHeaders(Table table, List<PatientIdentifierType> identifying,
	        List<PersonAttributeType> attributeTypes) {
		table.headers.add("ACT ID");
		for (String key : table.patient) {
			if (key.equals("identifiers")) {
				identifying.forEach(type -> table.headers.add(type.getName()));
			} else if (key.equals("phones")) {
				attributeTypes.forEach(type -> table.headers.add(type.getName()));
			} else {
				table.headers.add(PATIENT_FIELDS.get(key));
			}
		}
	}
	
	private static void patientCells(List<String> row, List<String> fields, Patient patient, Integer age,
	        List<PatientIdentifierType> identifying, List<PersonAttributeType> attributeTypes) {
		row.add(actId(patient, identifying));
		for (String key : fields) {
			if (key.equals("givenName")) {
				row.add(StringUtils.defaultString(patient.getGivenName()));
			} else if (key.equals("familyName")) {
				row.add(StringUtils.defaultString(patient.getFamilyName()));
			} else if (key.equals("birthDate")) {
				row.add(patient.getBirthdate() == null ? "" : date(patient.getBirthdate()));
			} else if (key.equals("identifiers")) {
				for (PatientIdentifierType type : identifying) {
					PatientIdentifier identifier = patient.getPatientIdentifier(type);
					row.add(identifier == null ? "" : identifier.getIdentifier());
				}
			} else if (key.equals("phones")) {
				for (PersonAttributeType type : attributeTypes) {
					PersonAttribute attribute = patient.getAttribute(type);
					row.add(attribute == null ? "" : StringUtils.defaultString(attribute.getValue()));
				}
			} else if (key.equals("sex")) {
				row.add(StringUtils.defaultString(patient.getGender()));
			} else {
				row.add(age == null ? "" : age.toString());
			}
		}
	}
	
	private static String joined(List<Obs> answers, Locale locale) {
		List<String> values = new ArrayList<String>();
		for (Obs obs : answers) {
			values.add(value(obs, locale));
		}
		return StringUtils.join(values, "; ");
	}
	
	private static List<Encounter> encounters(Form form, LocalDate from, LocalDate to, boolean researchOnly,
	        Filters filters) {
		List<Encounter> encounters = new ArrayList<Encounter>(Context.getEncounterService()
		        .getEncounters(new EncounterSearchCriteriaBuilder().setEnteredViaForms(Collections.singletonList(form))
		                .setFromDate(startOf(from))
		                .setToDate(to == null ? null : OpenmrsUtil.getLastMomentOfDay(startOf(to))).setIncludeVoided(false)
		                .createEncounterSearchCriteria()));
		if (researchOnly) {
			PersonAttributeType optIn = attributeType(
			    Context.getAdministrationService().getGlobalProperty(StudyEnrolmentRules.OPT_IN_PROPERTY));
			Concept yes = concept(Context.getAdministrationService().getGlobalProperty(StudyEnrolmentRules.YES_PROPERTY));
			// Unlike the enrolment rules, an unset opt-in or Yes admits no one.
			encounters.removeIf(encounter -> optIn == null || yes == null
			        || !StudyEnrolmentRules.answeredYes(encounter.getPatient(), optIn, yes));
		}
		encounters.removeIf(encounter -> encounter.getPatient().getVoided() || !filters.matches(encounter.getPatient()));
		encounters.sort(Comparator.comparing(Encounter::getEncounterDatetime).thenComparing(Encounter::getEncounterId));
		return encounters;
	}
	
	/**
	 * Reads the answers to the concepts in the encounters, keyed by encounter id and then by concept
	 * id, each list in the order the answers were saved. It reads the observations in batches.
	 */
	private static Map<Integer, Map<Integer, List<Obs>>> answers(List<Encounter> encounters, List<Concept> concepts) {
		Map<Integer, Map<Integer, List<Obs>>> answers = new HashMap<Integer, Map<Integer, List<Obs>>>();
		if (concepts.isEmpty()) {
			return answers;
		}
		for (int i = 0; i < encounters.size(); i += OBS_BATCH) {
			List<Obs> batch = new ArrayList<Obs>(Context.getObsService().getObservations(null,
			    encounters.subList(i, Math.min(encounters.size(), i + OBS_BATCH)), concepts, null, null, null, null, null,
			    null, null, null, false));
			batch.sort(Comparator.comparing(Obs::getObsId));
			for (Obs obs : batch) {
				answers.computeIfAbsent(obs.getEncounter().getEncounterId(), id -> new LinkedHashMap<>())
				        .computeIfAbsent(obs.getConcept().getConceptId(), id -> new ArrayList<>()).add(obs);
			}
		}
		return answers;
	}
	
	/**
	 * Gets the patient's ACT ID: their preferred identifier unless its type identifies them, or else
	 * the first active identifier whose type does not. It is empty when every identifier identifies
	 * them.
	 */
	private static String actId(Patient patient, List<PatientIdentifierType> identifying) {
		List<PatientIdentifier> identifiers = new ArrayList<PatientIdentifier>(patient.getActiveIdentifiers());
		identifiers.sort(Comparator.comparing((PatientIdentifier identifier) -> !identifier.getPreferred()));
		for (PatientIdentifier identifier : identifiers) {
			if (!identifying.contains(identifier.getIdentifierType())) {
				return identifier.getIdentifier();
			}
		}
		return "";
	}
	
	private static String value(Obs obs, Locale locale) {
		if (obs.getValueCoded() != null) {
			ConceptName name = obs.getValueCodedName() != null ? obs.getValueCodedName()
			        : obs.getValueCoded().getName(locale);
			return name == null ? "" : name.getName();
		}
		if (obs.getValueDatetime() != null) {
			return obs.getConcept().getDatatype().isDate() ? date(obs.getValueDatetime())
			        : new SimpleDateFormat("yyyy-MM-dd HH:mm").format(obs.getValueDatetime());
		}
		if (obs.getValueNumeric() != null) {
			return BigDecimal.valueOf(obs.getValueNumeric()).stripTrailingZeros().toPlainString();
		}
		if (obs.getValueText() != null) {
			return obs.getValueText();
		}
		return StringUtils.defaultString(obs.getValueAsString(locale));
	}
	
	private static String date(Date date) {
		return new SimpleDateFormat("yyyy-MM-dd").format(date);
	}
	
	private static Date startOf(LocalDate day) {
		return day == null ? null : Date.from(day.atStartOfDay(ZoneId.systemDefault()).toInstant());
	}
	
	/**
	 * Gets the identifier types that identify a patient, as the global property lists them. It reads
	 * them with a proxy privilege, since the types are configuration and the caller needs no privilege
	 * to read them.
	 */
	private static List<PatientIdentifierType> identifierTypes() {
		List<PatientIdentifierType> types = new ArrayList<PatientIdentifierType>();
		Context.addProxyPrivilege(PrivilegeConstants.GET_IDENTIFIER_TYPES);
		try {
			for (String uuid : uuids(IDENTIFIER_TYPES_PROPERTY)) {
				PatientIdentifierType type = Context.getPatientService().getPatientIdentifierTypeByUuid(uuid);
				if (type != null) {
					types.add(type);
				}
			}
		}
		finally {
			Context.removeProxyPrivilege(PrivilegeConstants.GET_IDENTIFIER_TYPES);
		}
		return types;
	}
	
	private static List<PersonAttributeType> attributeTypes() {
		List<PersonAttributeType> types = new ArrayList<PersonAttributeType>();
		for (String uuid : uuids(ATTRIBUTE_TYPES_PROPERTY)) {
			PersonAttributeType type = attributeType(uuid);
			if (type != null) {
				types.add(type);
			}
		}
		return types;
	}
	
	/**
	 * Reads a global property that lists uuids separated by commas.
	 *
	 * @param property the global property's name
	 * @return the uuids, trimmed and in order, without blanks; empty when the property is unset
	 */
	public static List<String> uuids(String property) {
		String value = Context.getAdministrationService().getGlobalProperty(property);
		List<String> uuids = new ArrayList<String>();
		for (String uuid : StringUtils.split(StringUtils.defaultString(value), ',')) {
			if (StringUtils.isNotBlank(uuid)) {
				uuids.add(uuid.trim());
			}
		}
		return uuids;
	}
	
	private static PersonAttributeType attributeType(String uuid) {
		if (StringUtils.isBlank(uuid)) {
			return null;
		}
		Context.addProxyPrivilege(PrivilegeConstants.GET_PERSON_ATTRIBUTE_TYPES);
		try {
			return Context.getPersonService().getPersonAttributeTypeByUuid(uuid.trim());
		}
		finally {
			Context.removeProxyPrivilege(PrivilegeConstants.GET_PERSON_ATTRIBUTE_TYPES);
		}
	}
	
	private static Program programByUuid(String uuid) {
		return StringUtils.isBlank(uuid) ? null : Context.getProgramWorkflowService().getProgramByUuid(uuid.trim());
	}
	
	private static Concept concept(String uuid) {
		return StringUtils.isBlank(uuid) ? null : Context.getConceptService().getConceptByUuid(uuid.trim());
	}
}
