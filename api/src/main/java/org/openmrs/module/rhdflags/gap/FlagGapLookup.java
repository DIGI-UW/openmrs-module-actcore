/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.rhdflags.gap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openmrs.Concept;
import org.openmrs.Encounter;
import org.openmrs.Patient;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.context.Context;
import org.openmrs.module.patientflags.Flag;
import org.openmrs.module.patientflags.PatientFlagsConstants;
import org.openmrs.module.patientflags.api.FlagService;
import org.openmrs.module.patientflags.evaluator.SQLFlagEvaluator;
import org.openmrs.util.PrivilegeConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lists a SQL flag's gaps from its criteria's (patient_id, encounter uuid, question uuid) rows.
 */
public class FlagGapLookup {
	
	private static final Pattern PATIENT_COLUMN = Pattern.compile("(\\w+\\.patient_id)");
	
	private static final Logger log = LoggerFactory.getLogger(FlagGapLookup.class);
	
	/**
	 * Reading a flag definition takes Test Flags, which a clinician who sees flags on the chart need
	 * not hold.
	 */
	public Flag getFlag(String uuid) {
		requireViewPatientFlags();
		try {
			Context.addProxyPrivilege(PatientFlagsConstants.PRIV_TEST_FLAGS);
			return Context.getService(FlagService.class).getFlagByUuid(uuid);
		}
		finally {
			Context.removeProxyPrivilege(PatientFlagsConstants.PRIV_TEST_FLAGS);
		}
	}
	
	/**
	 * The gaps behind this flag for this patient in encounter date order, or null when the flag is not
	 * a SQL flag with a patient column, the patient is voided, or no row of three columns comes back.
	 */
	public List<FlagGap> find(Patient patient, Flag flag) {
		requireViewPatientFlags();
		if (!SQLFlagEvaluator.class.getName().equals(flag.getEvaluator()) || patient.getVoided()) {
			return null;
		}
		Matcher column = PATIENT_COLUMN.matcher(flag.getCriteria());
		if (!column.find()) {
			return null;
		}
		// Rewritten for one patient as SQLFlagEvaluator.eval does, so patientflags and the look-up agree.
		String criteria = flag.getCriteria().replaceFirst(";?\\s*$", "");
		String query = criteria + (criteria.matches("(?i)(?s).*where.*") ? " and " : " where ") + column.group() + " = "
		        + patient.getPatientId();
		
		List<List<Object>> rows;
		try {
			Context.addProxyPrivilege(PrivilegeConstants.SQL_LEVEL_ACCESS);
			rows = Context.getAdministrationService().executeSQL(query, true);
		}
		finally {
			Context.removeProxyPrivilege(PrivilegeConstants.SQL_LEVEL_ACCESS);
		}
		
		if (rows.isEmpty()) {
			return null;
		}
		if (rows.get(0).size() < 3) {
			if (rows.get(0).size() == 2) {
				log.warn("The criteria of flag {} return 2 columns, where a gap row needs patient_id, an"
				        + " encounter uuid and a concept uuid",
				    flag.getUuid());
			}
			return null;
		}
		
		List<FlagGap> gaps = new ArrayList<FlagGap>();
		for (List<Object> row : rows) {
			if (row.get(1) == null || row.get(2) == null) {
				continue;
			}
			Encounter encounter = Context.getEncounterService().getEncounterByUuid(row.get(1).toString());
			if (encounter == null) {
				log.warn("The criteria of flag {} returned a value of type {} in column 2, which is not an encounter uuid",
				    flag.getUuid(), row.get(1).getClass().getSimpleName());
				continue;
			}
			if (encounter.getVoided() || !patient.equals(encounter.getPatient())
			        || !Context.getEncounterService().canViewEncounter(encounter, Context.getAuthenticatedUser())) {
				continue;
			}
			Concept question = Context.getConceptService().getConceptByUuid(row.get(2).toString());
			if (question == null) {
				log.warn("The criteria of flag {} returned a value of type {} in column 3, which is not a concept uuid",
				    flag.getUuid(), row.get(2).getClass().getSimpleName());
				continue;
			}
			gaps.add(new FlagGap(encounter, question));
		}
		Collections.sort(gaps, BY_ENCOUNTER_DATE);
		return gaps;
	}
	
	private static final Comparator<FlagGap> BY_ENCOUNTER_DATE = new Comparator<FlagGap>() {
		
		@Override
		public int compare(FlagGap a, FlagGap b) {
			return a.getEncounter().getEncounterDatetime().compareTo(b.getEncounter().getEncounterDatetime());
		}
	};
	
	/**
	 * Throws the exception the platform's authorization advice throws, rather than the one
	 * Context.requirePrivilege does.
	 */
	private static void requireViewPatientFlags() {
		if (!Context.hasPrivilege(PatientFlagsConstants.PRIV_VIEW_PATIENT_FLAGS)) {
			throw new APIAuthenticationException("Privilege required: " + PatientFlagsConstants.PRIV_VIEW_PATIENT_FLAGS);
		}
	}
}
