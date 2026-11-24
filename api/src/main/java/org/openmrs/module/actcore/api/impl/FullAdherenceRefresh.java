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
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;

import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.api.AdherenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The refresh of every patient's prophylaxis adherence and next due date that
 * {@link org.openmrs.module.actcore.tasks.AdherenceRefreshTask} runs, and that the patient flag
 * refresh runs before it evaluates the flags. One lock keeps two of these runs from going at once.
 */
public final class FullAdherenceRefresh {
	
	private static final Logger log = LoggerFactory.getLogger(FullAdherenceRefresh.class);
	
	private static final ReentrantLock RUNNING = new ReentrantLock();
	
	private FullAdherenceRefresh() {
	}
	
	/**
	 * Runs the refresh now unless another run is in progress, logging how long it took and how many
	 * patients it refreshed, or why it failed.
	 */
	public static void runIfIdle() {
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
	 * before it evaluates the flags, since the overdue flag reads these rows. Unlike
	 * {@link #runIfIdle()}, it waits for a run already in progress rather than skipping, so that the
	 * flags read finished rows.
	 */
	static void refreshBeforeFlags() {
		RUNNING.lock();
		try {
			Context.getService(AdherenceService.class).refreshAll(LocalDate.now());
		}
		finally {
			RUNNING.unlock();
		}
	}
}
