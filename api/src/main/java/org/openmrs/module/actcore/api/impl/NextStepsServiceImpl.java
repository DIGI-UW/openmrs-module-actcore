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
import java.util.List;

import org.openmrs.Patient;
import org.openmrs.api.impl.BaseOpenmrsService;
import org.openmrs.module.actcore.api.NextStepsService;
import org.openmrs.module.actcore.consultation.NextConsultation;
import org.openmrs.module.actcore.nextsteps.NextStep;
import org.openmrs.module.actcore.nextsteps.NextSteps;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default implementation of {@link org.openmrs.module.actcore.api.NextStepsService}. It has no DAO,
 * since it reads only through core's services and ACT Core's other services.
 */
public class NextStepsServiceImpl extends BaseOpenmrsService implements NextStepsService {
	
	/**
	 * @see org.openmrs.module.actcore.api.NextStepsService#getNextSteps(Patient, LocalDate)
	 */
	@Override
	@Transactional(readOnly = true)
	public List<NextStep> getNextSteps(Patient patient, LocalDate today) {
		return NextSteps.of(patient, today);
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.NextStepsService#getNextConsultationDate(Patient)
	 */
	@Override
	@Transactional(readOnly = true)
	public LocalDate getNextConsultationDate(Patient patient) {
		return NextConsultation.of(patient);
	}
}
