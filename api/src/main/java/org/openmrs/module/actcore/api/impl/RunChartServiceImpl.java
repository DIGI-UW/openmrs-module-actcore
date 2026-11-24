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
import java.util.Map;

import org.openmrs.Location;
import org.openmrs.api.impl.BaseOpenmrsService;
import org.openmrs.module.actcore.api.RunChartService;

/**
 * Default implementation of {@link org.openmrs.module.actcore.api.RunChartService}. It has no DAO,
 * since it reads only through the reporting module's service and
 * {@link org.openmrs.module.actcore.api.AdherenceService}.
 */
public class RunChartServiceImpl extends BaseOpenmrsService implements RunChartService {
	
	/**
	 * @see org.openmrs.module.actcore.api.RunChartService#getRunCharts(Location, LocalDate, LocalDate,
	 *      RunCharts.Period)
	 */
	@Override
	// No @Transactional: reporting evaluates a report outside one, each read in its own.
	public Map<String, List<RunCharts.Point>> getRunCharts(Location clinic, LocalDate start, LocalDate end,
	        RunCharts.Period period) {
		return new RunCharts().calculate(clinic, start, end, period);
	}
}
