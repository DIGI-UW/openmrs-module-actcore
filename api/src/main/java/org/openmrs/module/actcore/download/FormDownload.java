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
 * ACT 2.0's Download: a row per saved form of a kind, or a row per patient across forms. A row
 * always keeps the ACT ID, as ACT 2.0 kept the registry id, then the patient fields chosen.
 */
public class FormDownload {
	
	/** The identifier types a row without identifiers leaves out, such as the National ID. */
	public static final String IDENTIFIER_TYPES_PROPERTY = "actcore.download.identifyingIdentifierTypes";
	
	/** The person attribute types a row without identifiers leaves out, such as the phones. */
	public static final String ATTRIBUTE_TYPES_PROPERTY = "actcore.download.identifyingAttributeTypes";
	
	/** The questions a download without identifiers leaves out, such as the Home Address. */
	public static final String CONCEPTS_PROPERTY = "actcore.download.identifyingConcepts";
	
	private static final int OBS_BATCH = 500;
	
	/**
	 * The patient columns a request may choose, by key, with their labels; the ACT ID is always first.
	 * identifiers and phones give a column per type the identifying properties name.
	 */
	public static final Map<String, String> PATIENT_FIELDS = new LinkedHashMap<String, String>();
	
	/** The patient fields only an identified download gives. */
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
	
	/** The CSV's header row and its rows. */
	public static class Table {
		
		public final List<String> headers = new ArrayList<String>();
		
		public final List<List<String>> rows = new ArrayList<List<String>>();
		
		/** The labels of the questions left out because they identify the patient. */
		public final List<String> withheld = new ArrayList<String>();
		
		/** The keys of the patient fields given, in their columns' order. */
		public final List<String> patient = new ArrayList<String>();
		
		/** The keys of the patient fields asked for and left out because they identify the patient. */
		public final List<String> withheldPatient = new ArrayList<String>();
		
		/** Whether a patient field or question that identifies the patient was given. */
		public boolean identifiers;
	}
	
	/**
	 * A form and the questions chosen of it, under the name the columns of a row per patient give it.
	 */
	public static class Choice {
		
		public final Form form;
		
		public final String name;
		
		public final List<Concept> concepts;
		
		public final List<String> labels;
		
		public Choice(Form form, String name, List<Concept> concepts, List<String> labels) {
			this.form = form;
			this.name = name;
			this.concepts = concepts;
			this.labels = labels;
		}
	}
	
	/**
	 * ACT 2.0's download filters, status and clinics, a null leaving one out. Make one per download: it
	 * keeps each patient's answer.
	 */
	public static class Filters {
		
		/** active, inactive or null. */
		public final String status;
		
		public final Location cardiacClinic;
		
		public final Location primaryCareClinic;
		
		private Program registry;
		
		private List<String> clinicTypes;
		
		private final Map<Integer, Boolean> kept = new HashMap<Integer, Boolean>();
		
		public Filters(String status, Location cardiacClinic, Location primaryCareClinic) {
			this.status = status;
			this.cardiacClinic = cardiacClinic;
			this.primaryCareClinic = primaryCareClinic;
		}
		
		/** Decided once per patient, as each form's saves ask again and a status costs a query. */
		boolean matches(Patient patient) {
			return kept.computeIfAbsent(patient.getPatientId(), id -> {
				if (status != null && active(patient) != "active".equals(status)) {
					return false;
				}
				// The order actcore.logs.clinicAttributeTypes's description sets: primary care, then cardiac.
				return atClinic(patient, 0, primaryCareClinic) && atClinic(patient, 1, cardiacClinic);
			});
		}
		
		/** The distro's Active: the latest RHD Registry enrolment open, and the person alive. */
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
	 * The forms between the days, either open, a column per chosen concept. Only an identified download
	 * gives the names, birth date, ID numbers, phones and identifying questions.
	 */
	public Table build(Form form, List<Concept> asked, List<String> askedLabels, LocalDate from, LocalDate to,
	        boolean identified, boolean researchOnly) {
		return build(form, asked, askedLabels, from, to, identified, researchOnly, new Filters(null, null, null));
	}
	
	/** As build, of the patients the filters keep. */
	public Table build(Form form, List<Concept> asked, List<String> askedLabels, LocalDate from, LocalDate to,
	        boolean identified, boolean researchOnly, Filters filters) {
		return build(form, asked, askedLabels, from, to, identified, researchOnly, filters, null);
	}
	
	/** As build, with the patient fields chosen, or null for the default patientFields gives. */
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
	 * ACT 2.0's row per patient: each chosen form's saves in the range as numbered columns, oldest
	 * first, of the patients with at least one of them that the filters keep.
	 */
	public Table buildPerPatient(List<Choice> choices, LocalDate from, LocalDate to, boolean identified,
	        boolean researchOnly, Filters filters) {
		return buildPerPatient(choices, from, to, identified, researchOnly, filters, null);
	}
	
	/**
	 * As buildPerPatient, with the patient fields chosen, or null for the default patientFields gives.
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
	 * Sets the patient fields given, in PATIENT_FIELDS's order, and whether one identifies. Without a
	 * choice, those given before they could be chosen: every one when identified, else sex and age.
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
	
	/** The answers to the concepts, by encounter and concept, in the order they were saved. */
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
	 * The patient's preferred identifier, unless it is an identifying one, else the first that is not.
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
	
	/** The types are configuration, so the caller needs no privilege to read them. */
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
