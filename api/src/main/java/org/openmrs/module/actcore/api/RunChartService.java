/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.openmrs.Location;
import org.openmrs.annotation.Authorized;
import org.openmrs.api.OpenmrsService;
import org.openmrs.module.actcore.ActCoreConstants;
import org.openmrs.module.actcore.ActCorePrivileges;
import org.openmrs.module.actcore.api.impl.RunCharts;

/**
 * The service that calculates ACT 2.0's run charts for the Dashboard when they are asked for,
 * without storing them. It evaluates the report that {@value ActCoreConstants#GP_RUN_CHARTS_REPORT}
 * names and has {@link AdherenceService} replay the adherence of the patients on BPG. The run chart
 * endpoint calls it on a daemon thread, so that the report reads every clinic's records.
 */
public interface RunChartService extends OpenmrsService {
	
	/**
	 * Calculates the points of every metric of {@link RunCharts#METRICS} over the range, as
	 * {@link RunCharts#calculate(Location, LocalDate, LocalDate, RunCharts.Period)} describes.
	 *
	 * @param clinic the clinic, or null for every clinic
	 * @param start the range's first day
	 * @param end the range's last day
	 * @param period the period each point closes
	 * @return each metric's points, earliest first, by metric in the order of {@link RunCharts#METRICS}
	 * @throws IllegalArgumentException when the range has more than {@link RunCharts#MOST_POINTS}
	 *             points
	 * @throws org.openmrs.api.APIException when {@value ActCoreConstants#GP_RUN_CHARTS_REPORT} names no
	 *             report, or the report cannot be evaluated or gives rows ACT Core cannot read
	 * @throws org.openmrs.api.APIAuthenticationException when the user lacks
	 *             {@link ActCorePrivileges#DASHBOARD}
	 */
	@Authorized(ActCorePrivileges.DASHBOARD)
	Map<String, List<RunCharts.Point>> getRunCharts(Location clinic, LocalDate start, LocalDate end,
	        RunCharts.Period period);
}
