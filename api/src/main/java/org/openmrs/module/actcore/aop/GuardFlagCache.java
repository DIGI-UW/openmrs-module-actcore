/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.aop;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;

/**
 * Advice on FlagService that keeps the patientflags module from rebuilding its flag cache while
 * another thread reads it. The service's saveFlag, purgeFlag, saveTag, purgeTag, savePriority and
 * savePatientFlagsProperties rebuild the cache by publishing a new list of flags and then sorting
 * it in place, and generateFlagsForPatient iterates that list as it evaluates each flag, so an
 * evaluation that picks up the list before the sort ends throws a ConcurrentModificationException.
 * The rebuilding methods hold the write lock, and the methods that read the flag cache or the tag
 * cache hold the read lock.
 * <p>
 * getPrivileges reads the privilege cache, which a rebuild replaces whole, and is left unlocked. A
 * Groovy flag's evaluation calls it from a thread of its own and waits for that thread, so under
 * the read lock that thread would queue behind a waiting rebuild, which waits for the evaluation.
 */
public class GuardFlagCache implements MethodInterceptor {
	
	private static final ReentrantReadWriteLock CACHE = new ReentrantReadWriteLock();
	
	private static final List<String> REBUILDING = Arrays.asList("saveFlag", "purgeFlag", "saveTag", "purgeTag",
	    "savePriority", "savePatientFlagsProperties");
	
	// Their overloads taking roles and a display point filter by the tag cache.
	private static final List<String> FILTERING_BY_TAG = Arrays.asList("getFlagsForPatient", "getPatientFlags");
	
	/** @see org.aopalliance.intercept.MethodInterceptor#invoke(MethodInvocation) */
	@Override
	public Object invoke(MethodInvocation invocation) throws Throwable {
		Lock lock = lockFor(invocation.getMethod());
		if (lock == null) {
			return invocation.proceed();
		}
		lock.lock();
		try {
			return invocation.proceed();
		}
		finally {
			lock.unlock();
		}
	}
	
	private static Lock lockFor(Method method) {
		String name = method.getName();
		if (REBUILDING.contains(name)) {
			return CACHE.writeLock();
		}
		if ("generateFlagsForPatient".equals(name)
		        || FILTERING_BY_TAG.contains(name) && method.getParameterTypes().length == 3) {
			return CACHE.readLock();
		}
		return null;
	}
}
