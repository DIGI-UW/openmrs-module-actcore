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

import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.api.RaisedFlagService;
import org.openmrs.module.actcore.api.db.RaisedFlagDAO;
import org.springframework.aop.TargetSource;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.target.SingletonTargetSource;

/**
 * The raised flag service with each dismissal recorded inside the test's transaction, which a unit
 * of its own could not see into. It is swapped in behind the service's proxy, as
 * {@link Context#addAdvice} adds advice there, so calls keep the proxy's authorization checks.
 */
public final class RaisedFlagServiceInTest {
	
	private RaisedFlagServiceInTest() {
	}
	
	/**
	 * Makes the service record dismissals in the test's transaction until the returned work is run.
	 *
	 * @return the work that puts the service's own implementation back
	 */
	public static Runnable recordInTestTransaction() {
		Advised service = (Advised) Context.getService(RaisedFlagService.class);
		TargetSource own = service.getTargetSource();
		RaisedFlagServiceImpl inTest = new RaisedFlagServiceImpl() {
			
			@Override
			void inPatientTransaction(int patientId, Runnable work) {
				work.run();
				// Where a transaction of its own would commit.
				Context.flushSession();
			}
		};
		inTest.setDao(Context.getRegisteredComponents(RaisedFlagDAO.class).get(0));
		service.setTargetSource(new SingletonTargetSource(inTest));
		return () -> service.setTargetSource(own);
	}
}
