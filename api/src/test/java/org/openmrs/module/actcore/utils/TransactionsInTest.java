/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.utils;

import org.springframework.transaction.TransactionDefinition;

/** Work that a test commits apart from its own transaction. */
public final class TransactionsInTest {
	
	private TransactionsInTest() {
	}
	
	/**
	 * Runs the work in a new transaction at the database's default isolation, as
	 * {@link Transactions#inNewTransaction(int, Runnable)} describes, and commits it.
	 *
	 * @param work the work to run
	 */
	public static void inNewTransaction(Runnable work) {
		Transactions.inNewTransaction(TransactionDefinition.ISOLATION_DEFAULT, work);
	}
}
