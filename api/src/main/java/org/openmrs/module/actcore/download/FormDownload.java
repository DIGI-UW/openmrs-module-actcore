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
import org.openmrs.Obs;
import org.openmrs.Patient;
import org.openmrs.PatientIdentifier;
import org.openmrs.PatientIdentifierType;
import org.openmrs.PersonAttribute;
import org.openmrs.PersonAttributeType;
import org.openmrs.api.context.Context;
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
	 * The forms between the days, either open, a column per chosen concept. Only identified rows have
	 * the names, birth date, ID numbers, phones and identifying questions.
	 */
	public Table build(Form form, List<Concept> asked, List<String> askedLabels, LocalDate from, LocalDate to,
	        boolean identified, boolean researchOnly) {
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
		
		List<Encounter> encounters = encounters(form, from, to, researchOnly);
		Map<Integer, Map<Integer, List<Obs>>> answers = answers(encounters, concepts);
		for (Encounter encounter : encounters) {
			Patient patient = encounter.getPatient();
			List<String> row = new ArrayList<String>();
			row.add(actId(patient, identifying));
			if (identified) {
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
			row.add(StringUtils.defaultString(patient.getGender()));
			Integer age = patient.getBirthdate() == null ? null : patient.getAge(encounter.getEncounterDatetime());
			row.add(age == null ? "" : age.toString());
			row.add(date(encounter.getEncounterDatetime()));
			row.add(encounter.getLocation() == null ? "" : encounter.getLocation().getName());
			Map<Integer, List<Obs>> byConcept = answers.getOrDefault(encounter.getEncounterId(), Collections.emptyMap());
			for (Concept concept : concepts) {
				List<String> values = new ArrayList<String>();
				for (Obs obs : byConcept.getOrDefault(concept.getConceptId(), Collections.emptyList())) {
					values.add(value(obs, locale));
				}
				row.add(StringUtils.join(values, "; "));
			}
			table.rows.add(row);
		}
		return table;
	}
	
	private static List<Encounter> encounters(Form form, LocalDate from, LocalDate to, boolean researchOnly) {
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
		encounters.removeIf(encounter -> encounter.getPatient().getVoided());
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
	
	private static List<String> uuids(String property) {
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
	
	private static Concept concept(String uuid) {
		return StringUtils.isBlank(uuid) ? null : Context.getConceptService().getConceptByUuid(uuid.trim());
	}
}
