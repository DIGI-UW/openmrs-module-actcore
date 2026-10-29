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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.time.LocalDate;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.openmrs.Encounter;
import org.openmrs.GlobalProperty;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.runchart.RunCharts;
import org.openmrs.module.reporting.dataset.definition.SqlDataSetDefinition;
import org.openmrs.module.reporting.evaluation.parameter.Mapped;
import org.openmrs.module.reporting.evaluation.parameter.Parameter;
import org.openmrs.module.reporting.report.definition.ReportDefinition;
import org.openmrs.module.reporting.report.definition.service.ReportDefinitionService;

/** The adherent count of a past point is a replay of each listed patient's history to that day. */
public class RunChartAdherenceContextTest extends AdherenceContextTest {
	
	private static final String REPORT_UUID = "7b0f6a9e-3c51-4e0b-9d1a-2f6c8e4a1b33";
	
	private static final LocalDate EARLIER = TODAY.minusDays(35);
	
	/** A report listing patient 7 as the one active BPG patient on two days, 35 days apart. */
	@Before
	public void pointAtAReportListingPatient7() {
		StringBuilder select = new StringBuilder();
		for (LocalDate day : new LocalDate[] { EARLIER, TODAY }) {
			if (select.length() > 0) {
				select.append(" union all ");
			}
			select.append("select date '").append(day).append("' as point_date, null as clinic_uuid,")
			        .append(" 1 as total_registrants, 1 as active_registrants, 1 as active_registrants_seen_last_7_months,")
			        .append(" 1 as active_registrants_prescribed_prophylaxis, 0 as consultation_visit_total,")
			        .append(
			            " 0 as consultation_visits_with_echocardiogram_resulted, 0 as consultation_visits_with_intervention,")
			        .append(" 0 as interventions_completed, 1 as active_registrants_on_bpg, '7' as bpg_patient_ids");
		}
		SqlDataSetDefinition sql = new SqlDataSetDefinition();
		sql.addParameter(new Parameter("startDate", "From", java.util.Date.class));
		sql.addParameter(new Parameter("endDate", "To", java.util.Date.class));
		sql.addParameter(new Parameter("points", "Points", String.class));
		sql.setSqlQuery(select.toString());
		ReportDefinition report = new ReportDefinition();
		report.setUuid(REPORT_UUID);
		report.setName("Run chart adherence test report");
		report.addParameter(new Parameter("startDate", "From", java.util.Date.class));
		report.addParameter(new Parameter("endDate", "To", java.util.Date.class));
		report.addParameter(new Parameter("points", "Points", String.class));
		report.addDataSetDefinition("metrics", Mapped.mapStraightThrough(sql));
		Context.getService(ReportDefinitionService.class).saveDefinition(report);
		Context.getAdministrationService().saveGlobalProperty(new GlobalProperty(RunCharts.GP_REPORT, REPORT_UUID));
	}
	
	@Test
	public void calculate_shouldReplayEachListedPatientsAdherenceToThePointsDayAndWriteNoRow() {
		// Q21 BPG started 60 days ago, given then and 21 days later, then never again.
		Encounter consultation = encounter(7, 60);
		prescribe(consultation, q21, 60, null);
		given(consultation, 60);
		given(encounter(7, 39), 39);
		
		List<RunCharts.Point> adherent = new RunCharts().calculate(null, EARLIER, TODAY, RunCharts.Period.DAY)
		        .get("active_registrants_adherent");
		
		// On the earlier day the next injection, due 18 days ago, was not yet late; today it is 18 days late.
		assertEquals(EARLIER, adherent.get(0).date);
		assertEquals(Double.valueOf(100), adherent.get(0).value);
		assertEquals(Long.valueOf(1), adherent.get(0).count);
		assertEquals(TODAY, adherent.get(35).date);
		assertEquals(Double.valueOf(0), adherent.get(35).value);
		// A day the report lists nobody for counts nobody, whatever the patient's adherence then.
		assertNull(adherent.get(1).value);
		assertEquals(0, rows().size());
	}
}
