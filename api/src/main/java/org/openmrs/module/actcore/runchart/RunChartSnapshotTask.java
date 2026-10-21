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
import java.util.concurrent.locks.ReentrantLock;

import org.openmrs.scheduler.tasks.AbstractTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Snapshots each clinic's run chart metrics for today, daily and on demand, as ACT 2.0's
 * RunchartUpdate.
 */
public class RunChartSnapshotTask extends AbstractTask {
	
	private static final Logger log = LoggerFactory.getLogger(RunChartSnapshotTask.class);
	
	// Static, as the scheduler's instance and the run now endpoint's are different objects.
	private static final ReentrantLock RUNNING = new ReentrantLock();
	
	@Override
	public void execute() {
		try {
			Integer rows = runIfIdle();
			if (rows != null) {
				log.info("Run chart snapshot finished: {} rows", rows);
			}
		}
		catch (RuntimeException e) {
			log.error("Run chart snapshot failed", e);
		}
	}
	
	/** Takes today's snapshot and returns its rows, or null when another run is in progress. */
	public Integer runIfIdle() {
		if (!RUNNING.tryLock()) {
			log.info("Run chart snapshot skipped: another run is in progress");
			return null;
		}
		try {
			return new RunCharts().snapshot(LocalDate.now());
		}
		finally {
			RUNNING.unlock();
		}
	}
}
