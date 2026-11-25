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
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.hibernate.SessionFactory;
import org.junit.Test;
import org.openmrs.GlobalProperty;
import org.openmrs.Patient;
import org.openmrs.PatientProgram;
import org.openmrs.Privilege;
import org.openmrs.Role;
import org.openmrs.api.AdministrationService;
import org.openmrs.api.ProgramWorkflowService;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.Daemon;
import org.openmrs.api.context.ServiceContext;
import org.openmrs.module.DaemonTokens;
import org.openmrs.module.actcore.ActCoreActivator;
import org.openmrs.module.actcore.ActCoreConstants;
import org.openmrs.module.patientflags.DisplayPoint;
import org.openmrs.module.patientflags.Flag;
import org.openmrs.module.patientflags.Tag;
import org.openmrs.module.patientflags.api.FlagService;
import org.openmrs.module.patientflags.api.impl.FlagServiceImpl;
import org.openmrs.module.patientflags.db.FlagDAO;
import org.openmrs.test.BaseModuleContextSensitiveTest;
import org.springframework.aop.AfterReturningAdvice;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Runs patientflags' own FlagServiceImpl over a stub DAO whose tag query, which the cache rebuild
 * runs between publishing the new flag list and sorting it, holds the rebuild open until a
 * patient's evaluation has started on that list. On an empty cache it holds the first reader's fill
 * open until a second reader has started filling it too.
 */
public class GuardFlagCacheContextTest extends BaseModuleContextSensitiveTest {
	
	private static final long WAIT_SECONDS = 10;
	
	private static final String HIV_PROGRAM = "da4a0391-ba62-4fad-ad66-1e3722d16380";
	
	/** The concept of the terminal state in the standard test data's HIV program. */
	private static final String TERMINAL_STATE_CONCEPT = "7d104a6f-8337-4afa-b936-41083a5d9d88";
	
	// How long the rebuild holds the unsorted list open for an evaluation that never comes.
	private static final long HOLD_SECONDS = 2;
	
	private final CountDownLatch published = new CountDownLatch(1);
	
	private final CountDownLatch evaluating = new CountDownLatch(1);
	
	private final CountDownLatch released = new CountDownLatch(1);
	
	private final CountDownLatch secondFill = new CountDownLatch(1);
	
	private final AtomicBoolean rebuilding = new AtomicBoolean();
	
	private final AtomicBoolean cold = new AtomicBoolean();
	
	private final AtomicInteger tagQueries = new AtomicInteger();
	
	private final AtomicInteger evaluations = new AtomicInteger();
	
	private final AtomicInteger filling = new AtomicInteger();
	
	private final AtomicInteger mostFilling = new AtomicInteger();
	
	private volatile Work onEvaluation;
	
	private volatile Work onEveryEvaluation;
	
	@Autowired
	private SessionFactory sessionFactory;
	
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
	public void generateFlagsForPatient_shouldThrowWhenTwoReadersFillAnEmptyCacheTogetherWithoutTheFill() throws Exception {
		AtomicReference<Throwable> failure = coldReaders(service(false, false));
		
		assertTrue(String.valueOf(failure.get()), failure.get() instanceof ConcurrentModificationException);
	}
	
	@Test
	public void generateFlagsForPatient_shouldFillAnEmptyCacheOnceBeforeTwoReadersReadIt() throws Exception {
		AtomicReference<Throwable> failure = coldReaders(service(true, false));
		
		assertNull(failure.get());
		assertEquals(1, tagQueries.get());
	}
	
	@Test
	public void getFlagsForPatient_shouldFillAnEmptyCacheOnceBeforeItFiltersByTag() throws Exception {
		FlagService service = service(true, false);
		AtomicReference<Throwable> failure = coldReaders(service,
		    () -> service.getFlagsForPatient(new Patient(2), new HashSet<Role>(), (DisplayPoint) null));
		
		assertNull(failure.get());
		assertEquals(1, tagQueries.get());
	}
	
	@Test
	public void getPrivileges_shouldFillAnEmptyCacheBeforeAReaderReadsIt() throws Exception {
		FlagService service = service(true, false);
		
		assertNull(race(service, service::getPrivileges));
	}
	
	@Test
	public void getPrivileges_shouldNotWaitForTheFillOnAThreadThatAnEvaluationStarted() throws Exception {
		FlagService evaluated = service(true);
		// The service a context refresh puts in its place, whose cache is still empty.
		FlagService replacement = service(true, false);
		AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
		AtomicReference<Thread> groovy = new AtomicReference<Thread>();
		AtomicBoolean groovyWaited = new AtomicBoolean();
		released.countDown();
		// A Groovy evaluator thread asks the current service for privileges while the evaluation waits.
		onEvaluation = () -> {
			groovy.set(start(failure, replacement::getPrivileges));
			groovy.get().join(TimeUnit.SECONDS.toMillis(HOLD_SECONDS));
			groovyWaited.set(groovy.get().isAlive());
		};
		Thread reader = start(failure,
		    () -> evaluated.generateFlagsForPatient(new Patient(1), new HashMap<Object, Object>()));
		join(reader);
		join(groovy.get());
		
		assertFalse(groovyWaited.get());
		assertNull(failure.get());
	}
	
	@Test
	public void getPrivileges_shouldReturnACopyThatTheCallerCanNarrow() throws Exception {
		// The test data holds no privileges, so the superuser's privileges are this one.
		Context.getUserService().savePrivilege(new Privilege("Flag Privilege", "Read by a flag"));
		Context.getAdministrationService().saveGlobalProperty(new GlobalProperty("patientflags.username", "admin"));
		FlagService service = service(true, false);
		
		// As a Groovy flag's evaluator narrows them to the privileges of a user who is not a superuser.
		service.getPrivileges().retainAll(Collections.emptySet());
		
		assertFalse(service.getPrivileges().isEmpty());
	}
	
	@Test
	public void flagService_shouldCarryTheAdviceOnceFromTheSpringContext() {
		int guards = 0;
		for (Advisor advisor : ((Advised) Context.getService(FlagService.class)).getAdvisors()) {
			if (advisor.getAdvice() instanceof GuardFlagCache) {
				guards++;
			}
		}
		
		assertEquals(1, guards);
	}
	
	@Test
	public void started_shouldFillAnEmptyCacheOnceForTheFlagThreadsItsEnrolmentSavesStart() throws Exception {
		FlagService service = service(true, false);
		AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
		List<Thread> flagThreads = Collections.synchronizedList(new ArrayList<Thread>());
		cold.set(true);
		// As patientflags' advice does after each save, generates the patient's flags on a thread of its own.
		AfterReturningAdvice flagThread = (returned, method, args, target) -> {
			if ("savePatientProgram".equals(method.getName())) {
				Patient patient = ((PatientProgram) args[0]).getPatient();
				flagThreads.add(start(failure, () -> {
					service.generateFlagsForPatient(patient, new HashMap<Object, Object>());
					released.countDown();
				}));
			}
		};
		DaemonTokens.pass("actcore", new ActCoreActivator());
		try {
			// The daemon thread sees only what is committed, so it commits two dead patients' enrolments.
			asDaemon(() -> {
				property(ActCoreConstants.GP_REGISTRY_PROGRAM, HIV_PROGRAM);
				property(ActCoreConstants.GP_REGISTRY_DEATH_STATE_CONCEPT, TERMINAL_STATE_CONCEPT);
				AdministrationService admin = Context.getAdministrationService();
				admin.executeSQL("update person set dead = true, death_date = '2025-06-01' where person_id in (7, 8)",
				    false);
				for (int patientId : new int[] { 7, 8 }) {
					admin.executeSQL("insert into patient_program (patient_id, program_id, date_enrolled, creator,"
					        + " date_created, voided, uuid) values (" + patientId
					        + ", 1, '2025-01-01', 1, '2025-01-01', false, '" + enrolment(patientId) + "')",
					    false);
				}
			});
			Context.addAdvice(ProgramWorkflowService.class, flagThread);
			try {
				new ActCoreActivator().started();
			}
			finally {
				Context.removeAdvice(ProgramWorkflowService.class, flagThread);
			}
			join(flagThreads.toArray(new Thread[0]));
		}
		finally {
			asDaemon(() -> {
				AdministrationService admin = Context.getAdministrationService();
				String enrolments = "(select patient_program_id from patient_program where uuid in ('" + enrolment(7)
				        + "', '" + enrolment(8) + "'))";
				admin.executeSQL("delete from patient_state where patient_program_id in " + enrolments, false);
				admin.executeSQL(
				    "delete from patient_program where uuid in ('" + enrolment(7) + "', '" + enrolment(8) + "')", false);
				admin.executeSQL("update person set dead = false, death_date = null where person_id in (7, 8)", false);
				admin.executeSQL("delete from global_property where property in ('" + ActCoreConstants.GP_REGISTRY_PROGRAM
				        + "', '" + ActCoreConstants.GP_REGISTRY_DEATH_STATE_CONCEPT + "')",
				    false);
			});
			sessionFactory.getCache().evictAllRegions();
			new ActCoreActivator().setDaemonToken(null);
		}
		
		assertEquals(2, flagThreads.size());
		assertNull(failure.get());
		assertEquals(1, tagQueries.get());
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
		groovy.join(TimeUnit.SECONDS.toMillis(HOLD_SECONDS));
		boolean groovyWaited = groovy.isAlive();
		released.countDown();
		join(reader, writer, groovy);
		
		assertFalse(groovyWaited);
		assertNull(failure.get());
	}
	
	@Test
	public void getPrivileges_shouldFillAnEmptyCacheOnceForThreadsThatTwoEvaluationsStarted() throws Exception {
		FlagService evaluated = service(true);
		// The service a context refresh puts in its place, whose cache is still empty.
		FlagService replacement = service(true, false);
		AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
		cold.set(true);
		released.countDown();
		// Each evaluation's Groovy thread asks the current service for privileges while the evaluation waits.
		onEveryEvaluation = () -> join(start(failure, replacement::getPrivileges));
		Thread first = start(failure,
		    () -> evaluated.generateFlagsForPatient(new Patient(1), new HashMap<Object, Object>()));
		Thread second = start(failure,
		    () -> evaluated.generateFlagsForPatient(new Patient(2), new HashMap<Object, Object>()));
		join(first, second);
		
		assertNull(failure.get());
		assertEquals(1, mostFilling.get());
	}
	
	@Test
	public void generateFlagsForPatient_shouldReadAFilledCacheWhileAFillWaitsForAContextRefresh() throws Exception {
		FlagService evaluated = service(true);
		FlagService replacement = service(true, false);
		AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
		released.countDown();
		Thread filler;
		Thread reader;
		boolean readerWaited;
		ServiceContext.getInstance().startRefreshingContext();
		try {
			// The fill asks ServiceContext for AdministrationService, so it waits for the refresh to end.
			filler = start(failure, replacement::getPrivileges);
			waitUntilWaiting(filler);
			reader = start(failure, () -> evaluated.generateFlagsForPatient(new Patient(1), new HashMap<Object, Object>()));
			reader.join(TimeUnit.SECONDS.toMillis(HOLD_SECONDS));
			readerWaited = reader.isAlive();
		}
		finally {
			ServiceContext.getInstance().doneRefreshingContext();
		}
		join(filler, reader);
		
		assertFalse(readerWaited);
		assertNull(failure.get());
	}
	
	@Test
	public void getPrivileges_shouldNotFillAnEmptyCacheWhileSaveFlagRebuildsIt() throws Exception {
		FlagService service = service(true, false);
		AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
		rebuilding.set(true);
		Thread writer = start(failure, () -> service.saveFlag(new Flag()));
		assertTrue(published.await(WAIT_SECONDS, TimeUnit.SECONDS));
		Thread filler = start(failure, service::getPrivileges);
		join(writer, filler);
		
		assertNull(failure.get());
		assertEquals(1, mostFilling.get());
	}
	
	/**
	 * Saves a flag on one thread while a patient's flags are generated on another, and returns what the
	 * generation threw.
	 */
	private Throwable race(FlagService service) throws Exception {
		return race(service, () -> service.saveFlag(new Flag()));
	}
	
	/**
	 * Runs a call that rebuilds the cache on one thread while a patient's flags are generated on
	 * another, and returns what the generation threw.
	 */
	private Throwable race(FlagService service, Work rebuild) throws Exception {
		AtomicReference<Throwable> writerFailure = new AtomicReference<Throwable>();
		AtomicReference<Throwable> readerFailure = new AtomicReference<Throwable>();
		rebuilding.set(true);
		Thread writer = start(writerFailure, () -> {
			rebuild.run();
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
	
	/**
	 * Generates two patients' flags on an empty cache, the second starting while the first fills it,
	 * and returns what either generation threw.
	 */
	private AtomicReference<Throwable> coldReaders(FlagService service) throws Exception {
		return coldReaders(service, () -> service.generateFlagsForPatient(new Patient(2), new HashMap<Object, Object>()));
	}
	
	/**
	 * Generates a patient's flags on an empty cache while a second read starts as the generation fills
	 * it, and returns what either threw.
	 */
	private AtomicReference<Throwable> coldReaders(FlagService service, Work secondRead) throws Exception {
		AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
		cold.set(true);
		Thread first = start(failure, () -> {
			service.generateFlagsForPatient(new Patient(1), new HashMap<Object, Object>());
			released.countDown();
		});
		Thread second = start(failure, () -> {
			assertTrue(published.await(WAIT_SECONDS, TimeUnit.SECONDS));
			secondRead.run();
			released.countDown();
		});
		join(first, second);
		return failure;
	}
	
	private FlagService service(boolean guarded) {
		return service(guarded, true);
	}
	
	private FlagService service(boolean guarded, boolean filled) {
		FlagServiceImpl impl = new FlagServiceImpl();
		impl.setFlagDAO(dao());
		ProxyFactory proxy = new ProxyFactory(impl);
		proxy.addInterface(FlagService.class);
		if (guarded) {
			proxy.addAdvice(new GuardFlagCache());
		}
		FlagService service = (FlagService) proxy.getProxy();
		if (filled) {
			// Fills the cache now, so the evaluation reads it rather than rebuilding it itself.
			service.getPrivileges();
		}
		return service;
	}
	
	private FlagDAO dao() {
		return (FlagDAO) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { FlagDAO.class },
		    (proxy, method, args) -> {
			    switch (method.getName()) {
				    case "getAllEnabledFlags":
					    return new ArrayList<Flag>(Arrays.asList(new StalledFlag("b"), new StalledFlag("a")));
				    case "getAllTags":
					    mostFilling.accumulateAndGet(filling.incrementAndGet(), Math::max);
					    try {
						    if (rebuilding.get() && tagQueries.incrementAndGet() == 1) {
							    published.countDown();
							    evaluating.await(HOLD_SECONDS, TimeUnit.SECONDS);
						    } else if (cold.get() && tagQueries.incrementAndGet() == 1) {
							    published.countDown();
							    secondFill.await(HOLD_SECONDS, TimeUnit.SECONDS);
						    } else if (cold.get()) {
							    secondFill.countDown();
							    evaluating.await(HOLD_SECONDS, TimeUnit.SECONDS);
						    }
						    return new ArrayList<Tag>();
					    }
					    finally {
						    filling.decrementAndGet();
					    }
				    case "getFlagsForPatient":
					    return new ArrayList<Flag>();
				    case "saveFlag":
					    return null;
				    default:
					    throw new UnsupportedOperationException(method.getName());
			    }
		    });
	}
	
	/**
	 * A flag whose first evaluation of all stays open until the test releases it, and whose every
	 * evaluation first runs the test's work for it.
	 */
	private class StalledFlag extends Flag {
		
		StalledFlag(String name) {
			setName(name);
		}
		
		@Override
		public Boolean eval(Patient patient, Map<Object, Object> context) {
			try {
				if (onEveryEvaluation != null) {
					onEveryEvaluation.run();
				}
				if (evaluations.getAndIncrement() > 0) {
					return false;
				}
				evaluating.countDown();
				if (onEvaluation != null) {
					onEvaluation.run();
				}
				released.await(WAIT_SECONDS, TimeUnit.SECONDS);
			}
			catch (Exception e) {
				throw new IllegalStateException(e);
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
	
	private static String enrolment(int patientId) {
		return "5b0c1f9e-4d0a-4c8e-9a3f-0e7d2b6c4a" + patientId + patientId;
	}
	
	/** Runs the work on the daemon thread, whose service calls commit, and rethrows what it throws. */
	private static void asDaemon(Runnable work) {
		RuntimeException[] thrown = new RuntimeException[1];
		Daemon.runInDaemonThreadAndWait(() -> {
			try {
				work.run();
			}
			catch (RuntimeException e) {
				thrown[0] = e;
			}
		}, ActCoreActivator.getDaemonToken());
		if (thrown[0] != null) {
			throw thrown[0];
		}
	}
	
	private static void property(String name, String value) {
		Context.getAdministrationService().saveGlobalProperty(new GlobalProperty(name, value));
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
