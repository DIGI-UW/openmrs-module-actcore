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

import java.time.LocalDate;
import java.util.Date;
import java.util.concurrent.locks.ReentrantLock;

import org.openmrs.module.actcore.adherence.AdherenceRefreshTask;
import org.openmrs.scheduler.tasks.AbstractTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Snapshots each clinic's run chart metrics for today, daily and on demand, as ACT 2.0's
 * RunchartUpdate, after recomputing the adherence rows its report reads.
 */
public class RunChartSnapshotTask extends AbstractTask {
	
	private static final Logger log = LoggerFactory.getLogger(RunChartSnapshotTask.class);
	
	// Static, as the scheduler's instance and the run now endpoint's are different objects.
	private static final ReentrantLock RUNNING = new ReentrantLock();
	
	@Override
	public void execute() {
		try {
			RunChartRuns.Run run = runIfIdle(RunChartRuns.SCHEDULED);
			if (run != null) {
				log.info("Run chart snapshot finished: {} rows", run.rows);
			}
		}
		catch (RuntimeException e) {
			log.error("Run chart snapshot failed", e);
		}
	}
	
	/** Whether a run is in progress. */
	public static boolean isRunning() {
		return RUNNING.isLocked();
	}
	
	/**
	 * Takes today's snapshot and returns the run, or null when another run is in progress. Each run is
	 * recorded, taken or failed, and a failure is thrown on.
	 */
	public RunChartRuns.Run runIfIdle(String source) {
		if (!RUNNING.tryLock()) {
			log.info("Run chart snapshot skipped: another run is in progress");
			return null;
		}
		long started = System.currentTimeMillis();
		// Once, so a run past midnight is kept for the day it snapshotted.
		LocalDate day = LocalDate.now();
		RunChartRuns.Run run = new RunChartRuns.Run();
		run.runDate = day;
		run.source = source;
		try {
			AdherenceRefreshTask.refreshBeforeFlags();
			RunCharts runCharts = runCharts();
			run.rows = runCharts.snapshot(day);
			run.taken = true;
			run.clinics = clinicsOf(runCharts, day);
			return run;
		}
		catch (Throwable e) {
			run.reason = RunChartRuns.reasonOf(e);
			throw e;
		}
		finally {
			run.finished = new Date();
			run.durationMillis = run.finished.getTime() - started;
			try {
				new RunChartRuns().record(run);
			}
			finally {
				RUNNING.unlock();
			}
		}
	}
	
	/** The run charts the snapshot is taken on. */
	protected RunCharts runCharts() {
		return new RunCharts();
	}
	
	private static int clinicsOf(RunCharts runCharts, LocalDate day) {
		try {
			return runCharts.clinicRows(day);
		}
		catch (RuntimeException e) {
			log.warn("Could not count the clinics of the run chart snapshot of " + day, e);
			return 0;
		}
	}
}
