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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.patientflags.Flag;
import org.openmrs.module.patientflags.Tag;
import org.openmrs.module.patientflags.api.FlagService;
import org.openmrs.module.patientflags.api.impl.FlagServiceImpl;
import org.openmrs.module.patientflags.db.FlagDAO;
import org.openmrs.test.BaseModuleContextSensitiveTest;
import org.springframework.aop.framework.ProxyFactory;

/**
 * Runs patientflags' own FlagServiceImpl over a stub DAO whose tag query, which the cache rebuild
 * runs between publishing the new flag list and sorting it, holds the rebuild open until a
 * patient's evaluation has started on that list.
 */
public class GuardFlagCacheContextTest extends BaseModuleContextSensitiveTest {
	
	private static final long WAIT_SECONDS = 10;
	
	// How long the rebuild holds the unsorted list open for an evaluation that never comes.
	private static final long HOLD_SECONDS = 2;
	
	private final CountDownLatch published = new CountDownLatch(1);
	
	private final CountDownLatch evaluating = new CountDownLatch(1);
	
	private final CountDownLatch released = new CountDownLatch(1);
	
	private final AtomicBoolean rebuilding = new AtomicBoolean();
	
	@Test
	public void generateFlagsForPatient_shouldThrowWhenItReadsTheCacheThatSaveFlagIsSortingWithoutTheAdvice()
	        throws Exception {
		Throwable thrown = race(service(false));
		
		assertTrue(String.valueOf(thrown), thrown instanceof ConcurrentModificationException);
	}
	
	@Test
	public void generateFlagsForPatient_shouldWaitForSaveFlagToFinishRebuildingTheCache() throws Exception {
		assertNull(race(service(true)));
	}
	
	@Test
	public void getPrivileges_shouldNotWaitBehindARebuildThatWaitsForAnEvaluation() throws Exception {
		FlagService service = service(true);
		AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
		Thread reader = start(failure, () -> service.generateFlagsForPatient(new Patient(1), new HashMap<Object, Object>()));
		assertTrue(evaluating.await(WAIT_SECONDS, TimeUnit.SECONDS));
		Thread writer = start(failure, () -> service.saveFlag(new Flag()));
		waitUntilWaiting(writer);
		
		// A Groovy flag's evaluator thread asks for the privileges while its evaluation waits for it.
		Thread groovy = start(failure, () -> service.getPrivileges());
		groovy.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
		boolean groovyWaited = groovy.isAlive();
		released.countDown();
		join(reader, writer, groovy);
		
		assertFalse(groovyWaited);
		assertNull(failure.get());
	}
	
	/**
	 * Saves a flag on one thread while a patient's flags are generated on another, and returns what the
	 * generation threw.
	 */
	private Throwable race(FlagService service) throws Exception {
		AtomicReference<Throwable> writerFailure = new AtomicReference<Throwable>();
		AtomicReference<Throwable> readerFailure = new AtomicReference<Throwable>();
		rebuilding.set(true);
		Thread writer = start(writerFailure, () -> {
			service.saveFlag(new Flag());
			released.countDown();
		});
		Thread reader = start(readerFailure, () -> {
			assertTrue(published.await(WAIT_SECONDS, TimeUnit.SECONDS));
			service.generateFlagsForPatient(new Patient(1), new HashMap<Object, Object>());
		});
		join(writer, reader);
		
		assertNull(writerFailure.get());
		return readerFailure.get();
	}
	
	private FlagService service(boolean guarded) {
		FlagServiceImpl impl = new FlagServiceImpl();
		impl.setFlagDAO(dao());
		// Fills the cache now, so the evaluation reads it rather than rebuilding it itself.
		impl.getPrivileges();
		ProxyFactory proxy = new ProxyFactory(impl);
		proxy.addInterface(FlagService.class);
		if (guarded) {
			proxy.addAdvice(new GuardFlagCache());
		}
		return (FlagService) proxy.getProxy();
	}
	
	private FlagDAO dao() {
		return (FlagDAO) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { FlagDAO.class },
		    (proxy, method, args) -> {
			    switch (method.getName()) {
				    case "getAllEnabledFlags":
					    return new ArrayList<Flag>(Arrays.asList(new StalledFlag("b"), new StalledFlag("a")));
				    case "getAllTags":
					    if (rebuilding.get()) {
						    published.countDown();
						    evaluating.await(HOLD_SECONDS, TimeUnit.SECONDS);
					    }
					    return new ArrayList<Tag>();
				    case "saveFlag":
					    return null;
				    default:
					    throw new UnsupportedOperationException(method.getName());
			    }
		    });
	}
	
	/** A flag whose first evaluation stays open until the test releases it. */
	private class StalledFlag extends Flag {
		
		StalledFlag(String name) {
			setName(name);
		}
		
		@Override
		public Boolean eval(Patient patient, Map<Object, Object> context) {
			evaluating.countDown();
			try {
				released.await(WAIT_SECONDS, TimeUnit.SECONDS);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return false;
		}
	}
	
	private interface Work {
		
		void run() throws Exception;
	}
	
	private static Thread start(AtomicReference<Throwable> failure, Work work) {
		Thread thread = new Thread(() -> {
			Context.openSession();
			try {
				work.run();
			}
			catch (Throwable e) {
				failure.compareAndSet(null, e);
			}
			finally {
				Context.closeSession();
			}
		});
		thread.start();
		return thread;
	}
	
	private static void waitUntilWaiting(Thread thread) throws InterruptedException {
		long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(WAIT_SECONDS);
		while (thread.getState() != Thread.State.WAITING && System.currentTimeMillis() < deadline) {
			Thread.sleep(10);
		}
		assertEquals(Thread.State.WAITING, thread.getState());
	}
	
	private static void join(Thread... threads) throws InterruptedException {
		for (Thread thread : threads) {
			thread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS * 2));
			assertFalse(thread.getName() + " is still running", thread.isAlive());
		}
	}
}
