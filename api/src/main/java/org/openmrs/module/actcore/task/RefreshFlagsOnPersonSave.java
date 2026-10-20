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

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.IntConsumer;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.openmrs.Person;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.Transactions;
import org.openmrs.util.PrivilegeConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Re-evaluates a patient's flags and flag lists once a save, void or unvoid of their person
 * commits; the adherence rows read encounters only, so they are left alone.
 */
public class RefreshFlagsOnPersonSave implements MethodInterceptor {
	
	private static final Logger log = LoggerFactory.getLogger(RefreshFlagsOnPersonSave.class);
	
	private static final Set<String> METHODS = new HashSet<String>(
	        Arrays.asList("savePerson", "voidPerson", "unvoidPerson", "savePatient", "voidPatient", "unvoidPatient"));
	
	private final IntConsumer flagRefresher;
	
	public RefreshFlagsOnPersonSave() {
		this(new IntConsumer() {
			
			// Not in one transaction: it runs each flag in its own, so one that fails loses no other.
			@Override
			public void accept(int patientId) {
				PatientFlagRefreshTask.refreshPatient(patientId);
			}
		});
	}
	
	RefreshFlagsOnPersonSave(IntConsumer flagRefresher) {
		this.flagRefresher = flagRefresher;
	}
	
	@Override
	public Object invoke(MethodInvocation invocation) throws Throwable {
		Object[] args = invocation.getArguments();
		boolean watched = METHODS.contains(invocation.getMethod().getName()) && args.length > 0 && args[0] instanceof Person;
		Object result = invocation.proceed();
		// The id is read after the save, as a new person has none before it.
		if (watched && ((Person) args[0]).getPersonId() != null) {
			Transactions.afterCommit(RefreshFlagsOnPersonSave.class, ((Person) args[0]).getPersonId(), new IntConsumer() {
				
				@Override
				public void accept(int personId) {
					refresh(personId);
				}
			});
		}
		return result;
	}
	
	/** A person who is not a patient, such as a user's or a provider's, has no flags to refresh. */
	private void refresh(int personId) {
		try {
			if (!isPatient(personId)) {
				return;
			}
			flagRefresher.accept(personId);
		}
		catch (RuntimeException e) {
			// The save stands; the next full refresh evaluates the flags.
			log.error("Could not refresh the flags of patient {} after their person was saved", personId, e);
		}
	}
	
	private static boolean isPatient(int personId) {
		// The user marking a patient deceased holds Edit People, not necessarily Get Patients.
		Context.addProxyPrivilege(PrivilegeConstants.GET_PATIENTS);
		try {
			return Context.getPatientService().getPatient(personId) != null;
		}
		finally {
			Context.removeProxyPrivilege(PrivilegeConstants.GET_PATIENTS);
		}
	}
}
