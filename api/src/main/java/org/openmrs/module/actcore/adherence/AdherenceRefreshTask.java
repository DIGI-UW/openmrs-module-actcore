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

import java.time.LocalDate;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;

import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.api.AdherenceService;
import org.openmrs.scheduler.tasks.AbstractTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The scheduled task that recomputes every patient's prophylaxis adherence and next due date. The
 * scheduler runs it daily, and it can also be run on demand; a run that starts while another is in
 * progress is skipped.
 */
public class AdherenceRefreshTask extends AbstractTask {
	
	private static final Logger log = LoggerFactory.getLogger(AdherenceRefreshTask.class);
	
	// Static for the same reason as the flag refresh's: taskaction runs a second instance alongside the scheduler's.
	private static final ReentrantLock RUNNING = new ReentrantLock();
	
	/** @see org.openmrs.scheduler.tasks.AbstractTask#execute() */
	@Override
	public void execute() {
		if (!RUNNING.tryLock()) {
			// Expected nightly, as the flag refresh starts with the same recompute.
			log.info("Prophylaxis adherence refresh skipped: another run is in progress");
			return;
		}
		try {
			long startedAt = System.currentTimeMillis();
			int patients = Context.getService(AdherenceService.class).refreshAll(LocalDate.now());
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
	
	/**
	 * Recomputes every patient's adherence rows as of today, for the patient flag refresh to call
	 * before it evaluates the flags, since the overdue flag reads these rows. Unlike a scheduled run,
	 * it waits for a run already in progress rather than skipping, so that the flags read finished
	 * rows.
	 */
	public static void refreshBeforeFlags() {
		RUNNING.lock();
		try {
			Context.getService(AdherenceService.class).refreshAll(LocalDate.now());
		}
		finally {
			RUNNING.unlock();
		}
	}
}
