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
 * One row per saved form of a kind with the questions chosen, as ACT 2.0's Download gave them.
 * Without identifiers a row keeps the ACT ID, sex and age, as ACT 2.0 kept the registry id.
 */
public class FormDownload {
	
	/** The identifier types a row without identifiers leaves out, such as the National ID. */
	public static final String IDENTIFIER_TYPES_PROPERTY = "actcore.download.identifyingIdentifierTypes";
	
	/** The person attribute types a row without identifiers leaves out, such as the phones. */
	public static final String ATTRIBUTE_TYPES_PROPERTY = "actcore.download.identifyingAttributeTypes";
	
	/** The questions a download without identifiers leaves out, such as the Home Address. */
	public static final String CONCEPTS_PROPERTY = "actcore.download.identifyingConcepts";
	
	private static final int OBS_BATCH = 500;
	
	/** The CSV's header row and its rows. */
	public static class Table {
		
		public final List<String> headers = new ArrayList<String>();
		
		public final List<List<String>> rows = new ArrayList<List<String>>();
		
		/** The labels of the questions left out because they identify the patient. */
		public final List<String> withheld = new ArrayList<String>();
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
	
	/** ACT 2.0's download filters: the patient's status and clinics. A null leaves that one out. */
	public static class Filters {
		
		public static final Filters NONE = new Filters(null, null, null);
		
		/** active, inactive or null. */
		public final String status;
		
		public final Location cardiacClinic;
		
		public final Location primaryCareClinic;
		
		private Program registry;
		
		private List<String> clinicTypes;
		
		public Filters(String status, Location cardiacClinic, Location primaryCareClinic) {
			this.status = status;
			this.cardiacClinic = cardiacClinic;
			this.primaryCareClinic = primaryCareClinic;
		}
		
		boolean matches(Patient patient) {
			if (status != null && active(patient) != "active".equals(status)) {
				return false;
			}
			// The clinic types in the order ActivityLog keeps them: primary care, then cardiac.
			return atClinic(patient, 0, primaryCareClinic) && atClinic(patient, 1, cardiacClinic);
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
	 * The forms between the days, either open, a column per chosen concept. Only identified rows have
	 * the names, birth date, ID numbers, phones and identifying questions.
	 */
	public Table build(Form form, List<Concept> asked, List<String> askedLabels, LocalDate from, LocalDate to,
	        boolean identified, boolean researchOnly) {
		return build(form, asked, askedLabels, from, to, identified, researchOnly, Filters.NONE);
	}
	
	/** As build, of the patients the filters keep. */
	public Table build(Form form, List<Concept> asked, List<String> askedLabels, LocalDate from, LocalDate to,
	        boolean identified, boolean researchOnly, Filters filters) {
		Locale locale = Context.getLocale();
		List<PatientIdentifierType> identifying = identifierTypes();
		List<PersonAttributeType> attributeTypes = identified ? attributeTypes() : Collections.emptyList();
		
		Table table = new Table();
		List<String> identifyingConcepts = identified ? Collections.emptyList() : uuids(CONCEPTS_PROPERTY);
		List<Concept> concepts = new ArrayList<Concept>();
		List<String> labels = new ArrayList<String>();
		for (int i = 0; i < asked.size(); i++) {
			if (identifyingConcepts.contains(asked.get(i).getUuid())) {
				table.withheld.add(askedLabels.get(i));
			} else {
				concepts.add(asked.get(i));
				labels.add(askedLabels.get(i));
			}
		}
		table.headers.add("ACT ID");
		if (identified) {
			table.headers.addAll(Arrays.asList("Given name", "Family name", "Birth date"));
			identifying.forEach(type -> table.headers.add(type.getName()));
			attributeTypes.forEach(type -> table.headers.add(type.getName()));
		}
		table.headers.addAll(Arrays.asList("Sex", "Age", "Form date", "Location"));
		table.headers.addAll(labels);
		
		List<Encounter> encounters = encounters(form, from, to, researchOnly, filters);
		Map<Integer, Map<Integer, List<Obs>>> answers = answers(encounters, concepts);
		for (Encounter encounter : encounters) {
			Patient patient = encounter.getPatient();
			List<String> row = new ArrayList<String>();
			row.add(actId(patient, identifying));
			if (identified) {
				identityCells(row, patient, identifying, attributeTypes);
			}
			row.add(StringUtils.defaultString(patient.getGender()));
			Integer age = patient.getBirthdate() == null ? null : patient.getAge(encounter.getEncounterDatetime());
			row.add(age == null ? "" : age.toString());
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
		Locale locale = Context.getLocale();
		List<PatientIdentifierType> identifying = identifierTypes();
		List<PersonAttributeType> attributeTypes = identified ? attributeTypes() : Collections.emptyList();
		List<String> identifyingConcepts = identified ? Collections.emptyList() : uuids(CONCEPTS_PROPERTY);
		Table table = new Table();
		table.headers.add("ACT ID");
		if (identified) {
			table.headers.addAll(Arrays.asList("Given name", "Family name", "Birth date"));
			identifying.forEach(type -> table.headers.add(type.getName()));
			attributeTypes.forEach(type -> table.headers.add(type.getName()));
		}
		table.headers.addAll(Arrays.asList("Sex", "Age"));
		
		Map<Integer, Patient> patients = new LinkedHashMap<Integer, Patient>();
		List<List<Concept>> concepts = new ArrayList<List<Concept>>();
		List<Map<Integer, List<Encounter>>> saves = new ArrayList<Map<Integer, List<Encounter>>>();
		List<Map<Integer, Map<Integer, List<Obs>>>> answers = new ArrayList<Map<Integer, Map<Integer, List<Obs>>>>();
		for (Choice choice : choices) {
			List<Concept> given = new ArrayList<Concept>();
			List<String> labels = new ArrayList<String>();
			for (int i = 0; i < choice.concepts.size(); i++) {
				if (identifyingConcepts.contains(choice.concepts.get(i).getUuid())) {
					table.withheld.add(choice.name + ": " + choice.labels.get(i));
				} else {
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
			int most = byPatient.values().stream().mapToInt(List::size).max().orElse(0);
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
			row.add(actId(patient, identifying));
			if (identified) {
				identityCells(row, patient, identifying, attributeTypes);
			}
			row.add(StringUtils.defaultString(patient.getGender()));
			row.add(patient.getAge() == null ? "" : patient.getAge().toString());
			for (int c = 0; c < choices.size(); c++) {
				List<Encounter> theirs = saves.get(c).getOrDefault(patient.getPatientId(), Collections.emptyList());
				int most = saves.get(c).values().stream().mapToInt(List::size).max().orElse(0);
				for (int n = 0; n < most; n++) {
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
	
	private static void identityCells(List<String> row, Patient patient, List<PatientIdentifierType> identifying,
	        List<PersonAttributeType> attributeTypes) {
		row.add(StringUtils.defaultString(patient.getGivenName()));
		row.add(StringUtils.defaultString(patient.getFamilyName()));
		row.add(patient.getBirthdate() == null ? "" : date(patient.getBirthdate()));
		for (PatientIdentifierType type : identifying) {
			PatientIdentifier identifier = patient.getPatientIdentifier(type);
			row.add(identifier == null ? "" : identifier.getIdentifier());
		}
		for (PersonAttributeType type : attributeTypes) {
			PersonAttribute attribute = patient.getAttribute(type);
			row.add(attribute == null ? "" : StringUtils.defaultString(attribute.getValue()));
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
