/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.registry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openmrs.Location;
import org.openmrs.PatientProgram;
import org.openmrs.Program;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.adherence.AdherenceRefresh;
import org.openmrs.util.PrivilegeConstants;

/**
 * Groups the registry's active enrolments by the facility they were taken at, counting distinct
 * patients and averaging the adherence the nightly refresh last wrote.
 */
public final class FacilityRollup {
	
	public static final class Row {
		
		private final Location facility;
		
		private final int patients;
		
		private final Double adherence;
		
		Row(Location facility, int patients, Double adherence) {
			this.facility = facility;
			this.patients = patients;
			this.adherence = adherence;
		}
		
		/** The facility the enrolments were taken at; null when they recorded none. */
		public Location getFacility() {
			return facility;
		}
		
		public int getPatients() {
			return patients;
		}
		
		/** The mean of the adherence stored for these patients, null when none has one. */
		public Double getAdherence() {
			return adherence;
		}
	}
	
	private FacilityRollup() {
	}
	
	/**
	 * The rollup, ordered by patient count descending then facility name, with the no-facility bucket
	 * last.
	 */
	public static List<Row> of(Program registry) {
		Map<Integer, Set<Integer>> patientsByFacility = new LinkedHashMap<Integer, Set<Integer>>();
		for (PatientProgram enrolment : Context.getProgramWorkflowService().getPatientPrograms(null, registry, null, null,
		    null, null, false)) {
			if (enrolment.getDateCompleted() != null) {
				continue;
			}
			Integer facilityId = enrolment.getLocation() == null ? null : enrolment.getLocation().getLocationId();
			if (!patientsByFacility.containsKey(facilityId)) {
				patientsByFacility.put(facilityId, new HashSet<Integer>());
			}
			patientsByFacility.get(facilityId).add(enrolment.getPatient().getPatientId());
		}
		
		Map<Integer, Double> adherenceByPatient = loadAdherence();
		List<Row> rows = new ArrayList<Row>();
		for (Map.Entry<Integer, Set<Integer>> entry : patientsByFacility.entrySet()) {
			Location facility = entry.getKey() == null ? null : Context.getLocationService().getLocation(entry.getKey());
			rows.add(new Row(facility, entry.getValue().size(), mean(entry.getValue(), adherenceByPatient)));
		}
		rows.sort(new Comparator<Row>() {
			
			@Override
			public int compare(Row a, Row b) {
				if ((a.facility == null) != (b.facility == null)) {
					return a.facility == null ? 1 : -1;
				}
				int byCount = Integer.compare(b.patients, a.patients);
				if (byCount != 0 || a.facility == null) {
					return byCount;
				}
				return a.facility.getName().compareToIgnoreCase(b.facility.getName());
			}
		});
		return rows;
	}
	
	private static Double mean(Set<Integer> patientIds, Map<Integer, Double> adherenceByPatient) {
		double total = 0;
		int count = 0;
		for (Integer patientId : patientIds) {
			Double adherence = adherenceByPatient.get(patientId);
			if (adherence != null) {
				total += adherence;
				count++;
			}
		}
		return count == 0 ? null : total / count;
	}
	
	/**
	 * Every patient's last-computed adherence, read with the same proxy privilege the nightly refresh
	 * uses.
	 */
	private static Map<Integer, Double> loadAdherence() {
		Map<Integer, Double> adherence = new HashMap<Integer, Double>();
		Context.addProxyPrivilege(PrivilegeConstants.SQL_LEVEL_ACCESS);
		try {
			List<List<Object>> rows = Context.getAdministrationService().executeSQL(
			    "select patient_id, adherence from " + AdherenceRefresh.TABLE + " where adherence is not null", true);
			for (List<Object> row : rows) {
				adherence.put(((Number) row.get(0)).intValue(), ((Number) row.get(1)).doubleValue());
			}
		}
		finally {
			Context.removeProxyPrivilege(PrivilegeConstants.SQL_LEVEL_ACCESS);
		}
		return adherence;
	}
}
