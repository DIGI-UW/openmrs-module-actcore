/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.adherence;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.openmrs.Concept;
import org.openmrs.Encounter;
import org.openmrs.EncounterType;
import org.openmrs.Obs;
import org.openmrs.Patient;
import org.openmrs.Person;
import org.openmrs.api.context.Context;
import org.openmrs.util.PrivilegeConstants;

/**
 * Whether a BPG Delivery form recorded anaphylaxis that no RHD Consultation Visit has followed, by
 * encounter date and then id, as Next steps reads it from the same global properties.
 */
final class UnreviewedAnaphylaxis {
	
	private static final List<String> READS = Arrays.asList(PrivilegeConstants.GET_ENCOUNTERS, PrivilegeConstants.GET_OBS,
	    PrivilegeConstants.GET_ENCOUNTER_TYPES, PrivilegeConstants.GET_CONCEPTS, PrivilegeConstants.GET_GLOBAL_PROPERTIES);
	
	private UnreviewedAnaphylaxis() {
	}
	
	static boolean of(Patient patient) {
		for (String privilege : READS) {
			Context.addProxyPrivilege(privilege);
		}
		try {
			EncounterType bpg = type("bpg");
			EncounterType consult = type("consult");
			Concept answer = Context.getConceptService().getConceptByUuid(property("actcore.nextSteps.anaphylaxisAnswer"));
			if (bpg == null || answer == null) {
				return false;
			}
			Encounter trigger = null;
			for (Obs obs : Context.getObsService().getObservations(Collections.<Person> singletonList(patient), null, null,
			    Collections.singletonList(answer), null, null, null, null, null, null, null, false)) {
				Encounter encounter = obs.getEncounter();
				if (encounter != null && !encounter.getVoided() && bpg.equals(encounter.getEncounterType())
				        && (trigger == null || later(encounter, trigger))) {
					trigger = encounter;
				}
			}
			if (trigger == null) {
				return false;
			}
			for (Encounter encounter : Context.getEncounterService().getEncountersByPatient(patient)) {
				if (encounter.getEncounterType().equals(consult) && later(encounter, trigger)) {
					return false;
				}
			}
			return true;
		}
		finally {
			for (String privilege : READS) {
				Context.removeProxyPrivilege(privilege);
			}
		}
	}
	
	private static boolean later(Encounter a, Encounter b) {
		int byDate = a.getEncounterDatetime().compareTo(b.getEncounterDatetime());
		return byDate != 0 ? byDate > 0 : a.getEncounterId() > b.getEncounterId();
	}
	
	/** The type mapped to the key in actcore.nextSteps.encounterTypes, a list of key:uuid pairs. */
	private static EncounterType type(String key) {
		for (String pair : StringUtils.split(StringUtils.defaultString(property("actcore.nextSteps.encounterTypes")), ',')) {
			String[] parts = StringUtils.split(pair, ':');
			if (parts.length == 2 && key.equals(parts[0].trim())) {
				return Context.getEncounterService().getEncounterTypeByUuid(parts[1].trim());
			}
		}
		return null;
	}
	
	private static String property(String name) {
		return Context.getAdministrationService().getGlobalProperty(name);
	}
}
