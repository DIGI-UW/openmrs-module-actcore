/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.task;

import java.util.function.Consumer;

import org.openmrs.api.context.Context;

/**
 * One patient's flag refresh run inside the test's transaction, which a transaction of its own per
 * flag could not see into.
 */
public final class PatientFlagRefreshInTest {
	
	private PatientFlagRefreshInTest() {
	}
	
	public static PatientFlagRefreshTask.Result runWithAdherence(final Runnable adherence) {
		return new PatientFlagRefreshTask() {
			
			@Override
			void refreshAdherence() {
				adherence.run();
			}
		}.runIfIdle();
	}
	
	public static void refreshPatient(int patientId) {
		PatientFlagRefreshTask.refreshPatient(patientId, new Consumer<Runnable>() {
			
			@Override
			public void accept(Runnable work) {
				work.run();
				// Where a transaction of its own would commit.
				Context.flushSession();
			}
		});
	}
}
