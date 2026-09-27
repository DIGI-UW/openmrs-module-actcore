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
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.openmrs.Concept;
import org.openmrs.Encounter;
import org.openmrs.Patient;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.module.patientflags.Flag;
import org.openmrs.module.patientflags.PatientFlagsConstants;
import org.openmrs.module.patientflags.api.FlagService;
import org.openmrs.util.PrivilegeConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lists a flag's gaps from the query in {@value #GAP_QUERY_PREFIX}&lt;flag uuid&gt;, which names
 * the patient as {@value #PATIENT_TOKEN} and returns (encounter uuid, question concept uuid) rows.
 */
public class FlagGapLookup {
	
	public static final String GAP_QUERY_PREFIX = "rhdflags.gapQuery.";
	
	public static final String PATIENT_TOKEN = ":patientId";
	
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
	 * The gaps behind this flag for this patient in query order, or null when the flag has no query.
	 * 
	 * @throws APIException if the query does not name the patient, or returns a row of fewer than two
	 *             columns
	 */
	public List<FlagGap> find(Patient patient, Flag flag) {
		requireViewPatientFlags();
		String query;
		try {
			// platform 2.7 and later authorize reading a global property, which a clinician need not hold
			Context.addProxyPrivilege(PrivilegeConstants.GET_GLOBAL_PROPERTIES);
			query = Context.getAdministrationService().getGlobalProperty(GAP_QUERY_PREFIX + flag.getUuid());
		}
		finally {
			Context.removeProxyPrivilege(PrivilegeConstants.GET_GLOBAL_PROPERTIES);
		}
		if (StringUtils.isBlank(query)) {
			return null;
		}
		if (!query.contains(PATIENT_TOKEN)) {
			throw new APIException(
			        "The gap query for flag " + flag.getUuid() + " does not name the patient as " + PATIENT_TOKEN);
		}
		
		List<List<Object>> rows;
		try {
			Context.addProxyPrivilege(PrivilegeConstants.SQL_LEVEL_ACCESS);
			rows = Context.getAdministrationService()
			        .executeSQL(query.replace(PATIENT_TOKEN, patient.getPatientId().toString()), true);
		}
		finally {
			Context.removeProxyPrivilege(PrivilegeConstants.SQL_LEVEL_ACCESS);
		}
		
		List<FlagGap> gaps = new ArrayList<FlagGap>();
		for (List<Object> row : rows) {
			if (row.size() < 2) {
				throw new APIException(
				        "The gap query for flag " + flag.getUuid() + " must return an encounter uuid and a concept uuid");
			}
			if (row.get(0) == null || row.get(1) == null) {
				continue;
			}
			Encounter encounter = Context.getEncounterService().getEncounterByUuid(row.get(0).toString());
			if (encounter == null) {
				log.warn("The gap query for flag {} returned a value of type {} in column 1, which is not an encounter uuid",
				    flag.getUuid(), row.get(0).getClass().getSimpleName());
				continue;
			}
			if (encounter.getVoided() || !patient.equals(encounter.getPatient())
			        || !Context.getEncounterService().canViewEncounter(encounter, Context.getAuthenticatedUser())) {
				continue;
			}
			Concept question = Context.getConceptService().getConceptByUuid(row.get(1).toString());
			if (question == null) {
				log.warn("The gap query for flag {} returned a value of type {} in column 2, which is not a concept uuid",
				    flag.getUuid(), row.get(1).getClass().getSimpleName());
				continue;
			}
			gaps.add(new FlagGap(encounter, question));
		}
		return gaps;
	}
	
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
