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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLTransactionRollbackException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import javax.persistence.OptimisticLockException;
import javax.persistence.PersistenceException;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.StaleStateException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.Test;
import org.openmrs.api.context.Context;
import org.openmrs.api.db.DAOException;
import org.openmrs.test.BaseModuleContextSensitiveTest;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.hibernate5.HibernateOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * A patient's unit run as the save-time and nightly refreshes run it: outside any transaction, so
 * that each one commits for real and another thread can wait on its lock.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class TransactionsContextTest extends BaseModuleContextSensitiveTest {
	
	private static final int PATIENT = 7;
	
	private static final int OTHER_PATIENT = 2;
	
	// Long enough for a unit that does not wait on the lock to have started.
	private static final long WAIT_MILLIS = 500;
	
	@Test
	public void runsAPatientsUnitAtReadCommitted() {
		List<Integer> isolation = new ArrayList<Integer>();
		
		Transactions.inPatientTransaction(PATIENT,
		    () -> isolation.add(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()));
		
		assertEquals(Collections.singletonList(Connection.TRANSACTION_READ_COMMITTED), isolation);
	}
	
	@Test
	public void holdsThePatientsRowUntilTheUnitEnds() {
		List<Throwable> failed = Collections.synchronizedList(new ArrayList<Throwable>());
		CountDownLatch entered = new CountDownLatch(1);
		Thread[] second = new Thread[1];
		
		Transactions.inPatientTransaction(PATIENT, () -> {
			second[0] = inThread(failed, () -> Transactions.inPatientTransaction(PATIENT, entered::countDown));
			assertFalse("a second unit for the patient started while the first held the patient",
			    await(entered, WAIT_MILLIS));
		});
		
		join(second[0]);
		assertEquals(0, entered.getCount());
		assertEquals(Collections.emptyList(), failed);
	}
	
	@Test
	public void letsAnotherPatientsUnitRunAtTheSameTime() {
		List<Throwable> failed = Collections.synchronizedList(new ArrayList<Throwable>());
		CountDownLatch entered = new CountDownLatch(1);
		Thread[] second = new Thread[1];
		
		Transactions.inPatientTransaction(PATIENT, () -> {
			second[0] = inThread(failed, () -> Transactions.inPatientTransaction(OTHER_PATIENT, entered::countDown));
			assertTrue("another patient's unit waited for the first patient", await(entered, 10000));
		});
		
		join(second[0]);
		assertEquals(Collections.emptyList(), failed);
	}
	
	@Test
	public void retriesAUnitOnceInAFreshSessionWhenItLosesADeadlock() {
		List<Object> sessions = new ArrayList<Object>();
		
		runFailingFirst(() -> new CannotAcquireLockException("Deadlock found when trying to get lock"),
		    () -> sessions.add(Context.getRegisteredComponent("sessionFactory", SessionFactory.class).getCurrentSession()));
		
		assertEquals(2, sessions.size());
		assertNotSame(sessions.get(0), sessions.get(1));
	}
	
	/**
	 * The retry runs as the first attempt does. Spring reports each attempt's isolation, and a unit for
	 * the same patient on another thread must still wait during the retry. The connection is checked
	 * too: at READ COMMITTED inside each attempt, and back at the pool's default once the unit returns.
	 * H2 opens every connection at READ COMMITTED, so here those connection checks hold whatever
	 * isolation the unit asks for; the reported isolation and the waiting unit are what fail when the
	 * retry runs otherwise.
	 */
	@Test
	public void retriesAUnitAtReadCommittedWithThePatientStillLocked() {
		List<Throwable> failed = Collections.synchronizedList(new ArrayList<Throwable>());
		CountDownLatch entered = new CountDownLatch(1);
		Thread[] second = new Thread[1];
		List<Integer> isolation = new ArrayList<Integer>();
		List<Integer> connectionIsolation = new ArrayList<Integer>();
		List<Connection> connections = new ArrayList<Connection>();
		int[] poolDefault = new int[1];
		Transactions.inNewTransaction(
		    () -> poolDefault[0] = currentSession().doReturningWork(Connection::getTransactionIsolation));
		
		runFailingFirst(() -> new CannotAcquireLockException("Deadlock found when trying to get lock"), () -> {
			isolation.add(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
			currentSession().doWork(connection -> {
				connectionIsolation.add(connection.getTransactionIsolation());
				connections.add(connection.unwrap(Connection.class));
			});
			if (isolation.size() == 2) {
				second[0] = inThread(failed, () -> Transactions.inPatientTransaction(PATIENT, entered::countDown));
				assertFalse("a unit for the patient started during the retry", await(entered, WAIT_MILLIS));
			}
		});
		
		join(second[0]);
		assertEquals(0, entered.getCount());
		assertEquals(Collections.emptyList(), failed);
		assertEquals(Arrays.asList(Connection.TRANSACTION_READ_COMMITTED, Connection.TRANSACTION_READ_COMMITTED), isolation);
		assertEquals(Arrays.asList(Connection.TRANSACTION_READ_COMMITTED, Connection.TRANSACTION_READ_COMMITTED),
		    connectionIsolation);
		for (Connection connection : connections) {
			try {
				assertEquals(poolDefault[0], connection.getTransactionIsolation());
			}
			catch (SQLException e) {
				throw new IllegalStateException(e);
			}
		}
	}
	
	@Test
	public void logsNothingWhenTheRetryRecovers() {
		List<LogEvent> logged = new ArrayList<LogEvent>();
		AbstractAppender appender = new AbstractAppender("capture", null, null, true, Property.EMPTY_ARRAY) {
			
			@Override
			public void append(LogEvent event) {
				logged.add(event.toImmutable());
			}
		};
		appender.start();
		Logger logger = (Logger) LogManager.getLogger(Transactions.class);
		Level level = logger.getLevel();
		logger.addAppender(appender);
		// The test log4j2.xml turns every logger off.
		logger.setLevel(Level.ALL);
		try {
			runFailingFirst(
			    () -> new DAOException("Error while running sql: insert into actcore_prophylaxis_adherence",
			            new SQLTransactionRollbackException("Deadlock found when trying to get lock", "40001", 1213)),
			    () -> {});
		}
		finally {
			logger.removeAppender(appender);
			logger.setLevel(level);
		}
		
		assertEquals(Collections.emptyList(), logged);
	}
	
	@Test
	public void retriesAUnitOnceWhenItsDeleteFoundTheRowGoneAtCommit() {
		assertEquals(2,
		    runFailingFirst(() -> new HibernateOptimisticLockingFailureException(new StaleStateException(
		            "Batch update returned" + " unexpected row count from update [0]; actual row count: 0; expected: 1")),
		        () -> {}));
	}
	
	@Test
	public void retriesAUnitOnceWhenItsDeleteFoundTheRowGoneOnAFlushWithinIt() {
		assertEquals(2,
		    runFailingFirst(
		        () -> new OptimisticLockException(new StaleStateException("Batch update returned unexpected row count")),
		        () -> {}));
	}
	
	@Test
	public void retriesAUnitOnceWhenItsSqlLosesADeadlock() {
		assertEquals(2,
		    runFailingFirst(
		        () -> new DAOException("Error while running sql: insert into actcore_prophylaxis_adherence",
		                new SQLTransactionRollbackException("Deadlock found when trying to get lock", "40001", 1213)),
		        () -> {}));
	}
	
	@Test
	public void doesNotRetryAUnitThatFailsForAnotherReason() {
		AtomicInteger attempts = new AtomicInteger();
		IllegalStateException failure = new IllegalStateException("broken criteria");
		
		try {
			Transactions.inPatientTransaction(PATIENT, () -> {
				attempts.incrementAndGet();
				throw failure;
			});
			fail("the failure was not thrown");
		}
		catch (IllegalStateException e) {
			assertSame(failure, e);
		}
		assertEquals(1, attempts.get());
	}
	
	@Test
	public void doesNotRetryAUnitThatWritesADuplicateKey() {
		AtomicInteger attempts = new AtomicInteger();
		
		try {
			Transactions.inPatientTransaction(PATIENT, () -> {
				attempts.incrementAndGet();
				throw new PersistenceException(new ConstraintViolationException("could not execute batch",
				        new SQLIntegrityConstraintViolationException("Duplicate entry '7-4' for key 'PRIMARY'"), "PRIMARY"));
			});
			fail("the failure was not thrown");
		}
		catch (PersistenceException e) {
			assertEquals(1, attempts.get());
		}
		attempts.set(0);
		try {
			Transactions.inPatientTransaction(PATIENT, () -> {
				attempts.incrementAndGet();
				throw new DataIntegrityViolationException("Duplicate entry");
			});
			fail("the failure was not thrown");
		}
		catch (DataIntegrityViolationException e) {
			assertEquals(1, attempts.get());
		}
	}
	
	@Test
	public void givesUpWhenTheRetryFailsOnConcurrencyToo() {
		AtomicInteger attempts = new AtomicInteger();
		List<RuntimeException> thrown = new ArrayList<RuntimeException>();
		
		try {
			Transactions.inPatientTransaction(PATIENT, () -> {
				attempts.incrementAndGet();
				RuntimeException failure = new CannotAcquireLockException("Deadlock " + attempts.get());
				thrown.add(failure);
				throw failure;
			});
			fail("the failure was not thrown");
		}
		catch (CannotAcquireLockException e) {
			assertSame(thrown.get(1), e);
		}
		assertEquals(2, attempts.get());
	}
	
	private static Session currentSession() {
		return Context.getRegisteredComponent("sessionFactory", SessionFactory.class).getCurrentSession();
	}
	
	/** Runs a unit that throws the failure on its first attempt only, and gives how often it ran. */
	private static int runFailingFirst(Supplier<RuntimeException> failure, Runnable work) {
		AtomicInteger attempts = new AtomicInteger();
		Transactions.inPatientTransaction(PATIENT, () -> {
			work.run();
			if (attempts.incrementAndGet() == 1) {
				throw failure.get();
			}
		});
		return attempts.get();
	}
	
	public static Thread inThread(List<Throwable> failed, Runnable work) {
		Thread thread = new Thread(() -> {
			Context.openSession();
			try {
				Context.authenticate("admin", "test");
				work.run();
			}
			catch (Throwable e) {
				failed.add(e);
			}
			finally {
				Context.closeSession();
			}
		});
		thread.start();
		return thread;
	}
	
	public static boolean await(CountDownLatch latch, long millis) {
		try {
			return latch.await(millis, TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException e) {
			throw new IllegalStateException(e);
		}
	}
	
	public static void join(Thread thread) {
		try {
			thread.join(20000);
		}
		catch (InterruptedException e) {
			throw new IllegalStateException(e);
		}
		assertFalse("the second unit never finished", thread.isAlive());
	}
}
