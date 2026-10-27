/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.runchart;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.LocalDate;
import java.util.Date;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.test.BaseModuleContextSensitiveTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The log as the scheduler meets it: each record commits on its own, so the tests run outside a
 * transaction.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class RunChartRunsContextTest extends BaseModuleContextSensitiveTest {
	
	private final RunChartRuns runs = new RunChartRuns();
	
	@Before
	public void createTheTable() {
		createTable();
	}
	
	@After
	public void emptyTheTable() {
		createTable();
		Context.getAdministrationService().executeSQL("delete from " + RunChartRuns.TABLE, false);
	}
	
	/** The table as the module's changeset makes it, which the test context does not run. */
	static void createTable() {
		Context.getAdministrationService().executeSQL(
		    "create table if not exists " + RunChartRuns.TABLE
		            + " (run_id int auto_increment primary key, run_date date not null, finished datetime not null,"
		            + " duration_millis bigint not null default 0, source varchar(16) not null, status varchar(16) not null,"
		            + " row_count int not null default 0, clinic_count int not null default 0, failure_reason varchar(500))",
		    false);
	}
	
	private static RunChartRuns.Run run(LocalDate day, long finished, boolean taken, String reason) {
		RunChartRuns.Run run = new RunChartRuns.Run();
		run.runDate = day;
		run.finished = new Date(finished);
		run.durationMillis = 38000;
		run.source = RunChartRuns.SCHEDULED;
		run.taken = taken;
		run.rows = taken ? 61 : 0;
		run.clinics = taken ? 60 : 0;
		run.reason = reason;
		return run;
	}
	
	@Test
	public void record_shouldKeepATakenRunAndAFailedRunWithTheirFigures() {
		runs.record(run(LocalDate.of(2026, 10, 20), 1000, true, null));
		runs.record(run(LocalDate.of(2026, 10, 21), 2000, false, "the report could not run"));
		
		List<RunChartRuns.Run> latest = runs.latest(7);
		
		assertEquals(2, latest.size());
		RunChartRuns.Run failed = latest.get(0);
		assertFalse(failed.taken);
		assertEquals(LocalDate.of(2026, 10, 21), failed.runDate);
		assertEquals("the report could not run", failed.reason);
		assertEquals(0, failed.rows);
		RunChartRuns.Run taken = latest.get(1);
		assertTrue(taken.taken);
		assertEquals(61, taken.rows);
		assertEquals(60, taken.clinics);
		assertEquals(38000, taken.durationMillis);
		assertEquals(RunChartRuns.SCHEDULED, taken.source);
		assertNull(taken.reason);
	}
	
	@Test
	public void latest_shouldBeNewestFirstAndNoMoreThanAsked() {
		for (int day = 1; day <= 9; day++) {
			runs.record(run(LocalDate.of(2026, 10, day), day * 1000L, true, null));
		}
		
		List<RunChartRuns.Run> latest = runs.latest(7);
		
		assertEquals(7, latest.size());
		assertEquals(LocalDate.of(2026, 10, 9), latest.get(0).runDate);
		assertEquals(LocalDate.of(2026, 10, 3), latest.get(6).runDate);
	}
	
	@Test
	public void lastTaken_shouldIgnoreAFailedRunAndBeNullBeforeTheFirst() {
		assertNull(runs.lastTaken());
		
		runs.record(run(LocalDate.of(2026, 10, 20), 1000, true, null));
		runs.record(run(LocalDate.of(2026, 10, 21), 2000, false, "failed"));
		
		assertEquals(LocalDate.of(2026, 10, 20), runs.lastTaken().runDate);
	}
	
	@Test
	public void record_shouldNotThrowWhenItCannotWriteSoItNeverChangesHowARunEnded() {
		Context.getAdministrationService().executeSQL("drop table " + RunChartRuns.TABLE, false);
		
		runs.record(run(LocalDate.of(2026, 10, 20), 1000, true, null));
	}
	
	@Test
	public void reasonOf_shouldBeTheRootCausesFirstLineKeptShort() {
		Exception failure = new APIException("The run chart snapshot failed", new IllegalStateException(
		        "could not execute statement: insert into t values (1)\n" + StringUtils.repeat("x", 800)));
		
		String reason = RunChartRuns.reasonOf(failure);
		
		assertTrue(reason, reason.startsWith("IllegalStateException: could not execute statement"));
		assertFalse(reason.contains("\n"));
		assertTrue(reason.length() <= RunChartRuns.REASON_LENGTH);
		assertEquals(RunChartRuns.REASON_LENGTH,
		    RunChartRuns.reasonOf(new RuntimeException(StringUtils.repeat("y", 900))).length());
	}
}
