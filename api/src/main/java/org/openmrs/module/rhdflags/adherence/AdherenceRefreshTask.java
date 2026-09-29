/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.rhdflags.adherence;

import java.time.LocalDate;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;

import org.openmrs.scheduler.tasks.AbstractTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Recomputes every patient's prophylaxis adherence and next due date, daily and on demand. */
public class AdherenceRefreshTask extends AbstractTask {
	
	private static final Logger log = LoggerFactory.getLogger(AdherenceRefreshTask.class);
	
	// Static for the same reason as the flag refresh's: taskaction runs a second instance alongside the scheduler's.
	private static final ReentrantLock RUNNING = new ReentrantLock();
	
	@Override
	public void execute() {
		if (!RUNNING.tryLock()) {
			log.warn("Prophylaxis adherence refresh skipped: another run is in progress");
			return;
		}
		try {
			long startedAt = System.currentTimeMillis();
			int patients = new AdherenceRefresh().refreshAll(LocalDate.now());
			log.info(String.format(Locale.ROOT, "Prophylaxis adherence refresh finished in %.1fs: %d patients",
			    (System.currentTimeMillis() - startedAt) / 1000.0, patients));
		}
		catch (RuntimeException e) {
			log.error("Prophylaxis adherence refresh failed", e);
		}
		finally {
			RUNNING.unlock();
		}
	}
}
