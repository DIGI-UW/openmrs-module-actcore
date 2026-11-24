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

import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.SortedSet;

import org.openmrs.Patient;
import org.openmrs.api.impl.BaseOpenmrsService;
import org.openmrs.module.actcore.adherence.AdherenceRefresh;
import org.openmrs.module.actcore.adherence.ProphylaxisSummary;
import org.openmrs.module.actcore.api.AdherenceService;
import org.openmrs.module.actcore.api.db.AdherenceDAO;
import org.springframework.transaction.annotation.Transactional;

/** Default implementation of {@link org.openmrs.module.actcore.api.AdherenceService}. */
public class AdherenceServiceImpl extends BaseOpenmrsService implements AdherenceService {
	
	private AdherenceDAO dao;
	
	public void setDao(AdherenceDAO dao) {
		this.dao = dao;
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.AdherenceService#refreshAll(LocalDate)
	 */
	@Override
	// No @Transactional: the rewrite commits in its own before the rows are let go.
	public int refreshAll(LocalDate today) {
		return new AdherenceRefresh(dao).refreshAll(today);
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.AdherenceService#refreshPatient(int, LocalDate)
	 */
	@Override
	@Transactional
	public void refreshPatient(int patientId, LocalDate today) {
		new AdherenceRefresh(dao).refreshPatient(patientId, today);
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.AdherenceService#getProphylaxisSummary(Patient, LocalDate)
	 */
	@Override
	@Transactional(readOnly = true)
	public ProphylaxisSummary getProphylaxisSummary(Patient patient, LocalDate today) {
		return new AdherenceRefresh(dao).summaryOf(patient, today);
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.AdherenceService#getAdherenceOn(Collection, SortedSet)
	 */
	@Override
	@Transactional(readOnly = true)
	public Map<Integer, Map<LocalDate, Double>> getAdherenceOn(Collection<Integer> patientIds, SortedSet<LocalDate> days) {
		return new AdherenceRefresh(dao).adherenceOn(patientIds, days);
	}
}
