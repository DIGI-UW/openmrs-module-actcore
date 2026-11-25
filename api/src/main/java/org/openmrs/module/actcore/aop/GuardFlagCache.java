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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.openmrs.api.context.ServiceContext;
import org.openmrs.module.patientflags.api.FlagService;

/**
 * Advice on FlagService that keeps the patientflags module from rebuilding its flag cache while
 * another thread reads it. The service's saveFlag, purgeFlag, saveTag, purgeTag, savePriority and
 * savePatientFlagsProperties rebuild the cache by publishing a new list of flags and then sorting
 * it in place, and generateFlagsForPatient iterates that list as it evaluates each flag, so an
 * evaluation that picks up the list before the sort ends throws a ConcurrentModificationException.
 * The rebuilding methods hold the write lock, and the methods that read the flag cache or the tag
 * cache hold the read lock.
 * <p>
 * A reader can also find the cache empty, on a FlagService that a context refresh has just created,
 * and fill it itself through the same rebuild, while other readers do the same under the shared
 * read lock. So the first reader of each FlagService fills its cache under the write lock before it
 * reads. The fill is skipped while the context is refreshing, since the rebuild asks ServiceContext
 * for a service and would wait for the refresh, and on a thread that holds the read lock, which
 * cannot be upgraded to the write lock.
 * <p>
 * getPrivileges rebuilds the cache too when it finds it empty, so it goes through the same fill and
 * then reads without a lock. A Groovy flag's evaluation calls it from a thread of its own and waits
 * for that thread, so under the read lock that thread would queue behind a waiting rebuild, which
 * waits for the evaluation. For the same reason the fill is also skipped on a thread started while
 * the thread that started it held the read lock: the evaluator thread may ask a FlagService that a
 * context refresh has just put in place of the one being read, and the fill would wait for the
 * evaluation's read lock. getPrivileges returns a copy of the privilege cache, because a Groovy
 * flag's evaluator narrows the collection it gets to the privileges of the user it runs for.
 * <p>
 * ACT Core's Spring context adds this advice to patientflags' flagService bean, which
 * ServiceContext keeps as the FlagService itself. So the FlagService that a context refresh creates
 * carries it before ServiceContext hands that service to any caller and before any module's advice
 * is loaded.
 */
public class GuardFlagCache implements MethodInterceptor {
	
	private static final ReentrantReadWriteLock CACHE = new ReentrantReadWriteLock();
	
	// True while the thread that set it, or the thread that started this one, holds the read lock.
	private static final InheritableThreadLocal<AtomicBoolean> READING = new InheritableThreadLocal<AtomicBoolean>();
	
	private static final List<String> REBUILDING = Arrays.asList("saveFlag", "purgeFlag", "saveTag", "purgeTag",
	    "savePriority", "savePatientFlagsProperties");
	
	// Their overloads taking roles and a display point filter by the tag cache.
	private static final List<String> FILTERING_BY_TAG = Arrays.asList("getFlagsForPatient", "getPatientFlags");
	
	private static final Set<Object> FILLED = Collections
	        .synchronizedSet(Collections.newSetFromMap(new WeakHashMap<Object, Boolean>()));
	
	/** @see org.aopalliance.intercept.MethodInterceptor#invoke(MethodInvocation) */
	@Override
	public Object invoke(MethodInvocation invocation) throws Throwable {
		if ("getPrivileges".equals(invocation.getMethod().getName())) {
			fillOnce(invocation.getThis());
			Collection<?> privileges = (Collection<?>) invocation.proceed();
			return privileges == null ? null : new ArrayList<Object>(privileges);
		}
		Lock lock = lockFor(invocation.getMethod());
		if (lock == null) {
			return invocation.proceed();
		}
		if (lock == CACHE.writeLock()) {
			lock.lock();
			try {
				return invocation.proceed();
			}
			finally {
				lock.unlock();
			}
		}
		fillOnce(invocation.getThis());
		AtomicBoolean outer = READING.get();
		AtomicBoolean reading = new AtomicBoolean(true);
		lock.lock();
		READING.set(reading);
		try {
			return invocation.proceed();
		}
		finally {
			reading.set(false);
			READING.set(outer);
			lock.unlock();
		}
	}
	
	private static void fillOnce(Object service) {
		AtomicBoolean reading = READING.get();
		if (FILLED.contains(service) || reading != null && reading.get()
		        || ServiceContext.getInstance().isRefreshingContext()) {
			return;
		}
		CACHE.writeLock().lock();
		try {
			if (!FILLED.contains(service)) {
				// getPrivileges fills the cache as a side effect and needs no patient.
				((FlagService) service).getPrivileges();
				FILLED.add(service);
			}
		}
		finally {
			CACHE.writeLock().unlock();
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
