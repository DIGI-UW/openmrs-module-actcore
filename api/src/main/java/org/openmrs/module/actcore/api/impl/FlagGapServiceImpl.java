/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.api.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openmrs.Concept;
import org.openmrs.Encounter;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.api.impl.BaseOpenmrsService;
import org.openmrs.module.actcore.api.FlagGapService;
import org.openmrs.module.actcore.api.db.FlagGapDAO;
import org.openmrs.module.actcore.gap.FlagGap;
import org.openmrs.module.patientflags.Flag;
import org.openmrs.module.patientflags.PatientFlagsConstants;
import org.openmrs.module.patientflags.api.FlagService;
import org.openmrs.module.patientflags.evaluator.SQLFlagEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/** Default implementation of {@link org.openmrs.module.actcore.api.FlagGapService}. */
public class FlagGapServiceImpl extends BaseOpenmrsService implements FlagGapService {
	
	private static final Pattern PATIENT_COLUMN = Pattern.compile("(\\w+\\.patient_id)");
	
	private static final Logger log = LoggerFactory.getLogger(FlagGapServiceImpl.class);
	
	private static final Comparator<FlagGap> BY_ENCOUNTER_DATE = Comparator
	        .comparing(gap -> gap.getEncounter().getEncounterDatetime());
	
	private FlagGapDAO dao;
	
	public void setDao(FlagGapDAO dao) {
		this.dao = dao;
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.FlagGapService#getFlag(String)
	 */
	@Override
	@Transactional(readOnly = true)
	public Flag getFlag(String uuid) {
		try {
			Context.addProxyPrivilege(PatientFlagsConstants.PRIV_TEST_FLAGS);
			return Context.getService(FlagService.class).getFlagByUuid(uuid);
		}
		finally {
			Context.removeProxyPrivilege(PatientFlagsConstants.PRIV_TEST_FLAGS);
		}
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.FlagGapService#getGaps(Patient, Flag)
	 */
	@Override
	@Transactional(readOnly = true)
	public List<FlagGap> getGaps(Patient patient, Flag flag) {
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
		
		List<List<Object>> rows = dao.getCriteriaRows(query);
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
}
