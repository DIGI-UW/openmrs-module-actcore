/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.runchart;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.hibernate.SQLQuery;
import org.hibernate.type.IntegerType;
import org.hibernate.type.LongType;
import org.hibernate.type.StringType;
import org.hibernate.type.TimestampType;
import org.openmrs.api.context.Context;
import org.openmrs.api.db.hibernate.DbSessionFactory;
import org.openmrs.module.actcore.Transactions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Each run of the run chart snapshot, taken or failed, kept so a missing night can be told from a
 * failed one.
 */
public class RunChartRuns {
	
	private static final Logger log = LoggerFactory.getLogger(RunChartRuns.class);
	
	public static final String TABLE = "actcore_run_chart_run";
	
	public static final String SCHEDULED = "scheduled";
	
	public static final String MANUAL = "manual";
	
	/** The longest reason kept. */
	public static final int REASON_LENGTH = 500;
	
	public static class Run {
		
		/** The day snapshotted. */
		public LocalDate runDate;
		
		public java.util.Date finished;
		
		public long durationMillis;
		
		public String source;
		
		public boolean taken;
		
		/** Rows written, 0 for a failed run. */
		public int rows;
		
		/** Of rows, those with a location; the every-clinic row has none. */
		public int clinics;
		
		/** Why a failed run failed. */
		public String reason;
	}
	
	/**
	 * Records the run in a transaction of its own, since a failed run's snapshot has rolled back. A
	 * failure to record is logged and not thrown, so it cannot change how the run ended.
	 */
	public void record(final Run run) {
		try {
			Transactions.inNewTransaction(() -> write(run));
		}
		catch (Exception e) {
			log.error("Could not record the run chart snapshot run of " + run.runDate, e);
		}
	}
	
	private void write(Run run) {
		sessionFactory().getCurrentSession()
		        .createSQLQuery("insert into " + TABLE
		                + " (run_date, finished, duration_millis, source, status, row_count, clinic_count, failure_reason)"
		                + " values (:day, :finished, :duration, :source, :status, :rows, :clinics, :reason)")
		        .setParameter("day", Date.valueOf(run.runDate)).setParameter("finished", run.finished)
		        .setParameter("duration", run.durationMillis).setParameter("source", run.source)
		        .setParameter("status", run.taken ? "taken" : "failed").setParameter("rows", run.rows)
		        .setParameter("clinics", run.clinics).setParameter("reason", run.reason, StringType.INSTANCE)
		        .executeUpdate();
	}
	
	/** The latest runs, newest first. */
	public List<Run> latest(int limit) {
		return select("", limit);
	}
	
	/** The latest run that took a snapshot, or null before the first. */
	public Run lastTaken() {
		List<Run> runs = select(" where status = 'taken'", 1);
		return runs.isEmpty() ? null : runs.get(0);
	}
	
	private List<Run> select(String where, int limit) {
		SQLQuery query = sessionFactory().getCurrentSession()
		        .createSQLQuery("select run_date, finished, duration_millis, source, status, row_count, clinic_count,"
		                + " failure_reason from " + TABLE + where + " order by finished desc, run_id desc")
		        .addScalar("run_date", TimestampType.INSTANCE).addScalar("finished", TimestampType.INSTANCE)
		        .addScalar("duration_millis", LongType.INSTANCE).addScalar("source", StringType.INSTANCE)
		        .addScalar("status", StringType.INSTANCE).addScalar("row_count", IntegerType.INSTANCE)
		        .addScalar("clinic_count", IntegerType.INSTANCE).addScalar("failure_reason", StringType.INSTANCE);
		query.setMaxResults(limit);
		List<Run> runs = new ArrayList<Run>();
		for (Object row : query.list()) {
			Object[] columns = (Object[]) row;
			Run run = new Run();
			run.runDate = new Date(((java.util.Date) columns[0]).getTime()).toLocalDate();
			run.finished = (java.util.Date) columns[1];
			run.durationMillis = (Long) columns[2];
			run.source = (String) columns[3];
			run.taken = "taken".equals(columns[4]);
			run.rows = (Integer) columns[5];
			run.clinics = (Integer) columns[6];
			run.reason = (String) columns[7];
			runs.add(run);
		}
		return runs;
	}
	
	/**
	 * The reason a run failed: the root cause's message, first line, kept short, as a statement is no
	 * reason.
	 */
	public static String reasonOf(Throwable failure) {
		String message = ExceptionUtils.getRootCauseMessage(failure);
		String firstLine = StringUtils.defaultString(message).split("\\R", 2)[0].trim();
		return StringUtils.abbreviate(firstLine, REASON_LENGTH);
	}
	
	private static DbSessionFactory sessionFactory() {
		return Context.getRegisteredComponent("dbSessionFactory", DbSessionFactory.class);
	}
}
