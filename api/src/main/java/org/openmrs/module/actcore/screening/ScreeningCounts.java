/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.screening;

import java.sql.Date;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.hibernate.SQLQuery;
import org.hibernate.type.StringType;
import org.openmrs.Location;
import org.openmrs.User;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.api.db.hibernate.DbSession;
import org.openmrs.api.db.hibernate.DbSessionFactory;
import org.openmrs.module.actcore.Transactions;

/**
 * ACT 2.0's monthly screening counts: per clinic and month, for each setting, the people screened,
 * their age groups, the females and the positives, kept in actcore_screening_count.
 */
public class ScreeningCounts {
	
	public static final String TABLE = "actcore_screening_count";
	
	public static final List<String> SETTINGS = Collections
	        .unmodifiableList(Arrays.asList("school", "community", "facility"));
	
	public static final List<String> METRICS = Collections
	        .unmodifiableList(Arrays.asList("screened", "children", "adolescents", "adults", "females", "positive"));
	
	/** The table's count columns, setting_metric, in the order of SETTINGS and then METRICS. */
	public static final List<String> COLUMNS;
	
	static {
		List<String> columns = new ArrayList<String>();
		for (String setting : SETTINGS) {
			for (String metric : METRICS) {
				columns.add(setting + "_" + metric);
			}
		}
		COLUMNS = Collections.unmodifiableList(columns);
	}
	
	public static final int NOTES_LENGTH = 1000;
	
	public static class Month {
		
		/** Whether the month has been saved; one that has not is all zeros. */
		public boolean saved;
		
		/** Every column's count. */
		public Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
		
		public String notes;
	}
	
	public static class Summary {
		
		/** How many clinic months were saved in the range. */
		public int months;
		
		/** Every column's sum. */
		public Map<String, Long> sums = new LinkedHashMap<String, Long>();
		
		/** A metric summed over the settings. */
		public Long total(String metric) {
			long total = 0;
			for (String setting : SETTINGS) {
				total += sums.get(setting + "_" + metric);
			}
			return total;
		}
		
		public Long screened() {
			return total("screened");
		}
		
		public Long positive() {
			return total("positive");
		}
		
		public Long negative() {
			return screened() - positive();
		}
	}
	
	/** The clinic's counts for the month, or zeros when it has not been saved. */
	public Month get(Location clinic, YearMonth month) {
		SQLQuery query = session().createSQLQuery("select notes, " + StringUtils.join(COLUMNS, ", ") + " from " + TABLE
		        + " where location_id = :clinic and month = :month");
		query.setParameter("clinic", clinic.getLocationId()).setParameter("month", firstDay(month));
		List<?> rows = query.list();
		Month result = new Month();
		Object[] row = rows.isEmpty() ? null : (Object[]) rows.get(0);
		result.saved = row != null;
		result.notes = row == null ? null : (String) row[0];
		for (int i = 0; i < COLUMNS.size(); i++) {
			result.counts.put(COLUMNS.get(i), row == null ? 0 : ((Number) row[i + 1]).intValue());
		}
		return result;
	}
	
	/**
	 * Replaces the clinic's month with these counts, a column not given being zero, once they hold
	 * together; an IllegalArgumentException says what does not.
	 */
	public void save(Location clinic, YearMonth month, Map<String, Integer> counts, String notes) {
		final Map<String, Integer> values = validated(month, counts, notes);
		final Integer clinicId = clinic.getLocationId();
		final Date day = firstDay(month);
		final String text = StringUtils.trimToNull(notes);
		final User user = Context.getAuthenticatedUser();
		try {
			Transactions.inTransaction(() -> {
				// Saves of one clinic take turns here rather than racing each other to the unique index.
				session().createSQLQuery("select location_id from location where location_id = :clinic for update")
				        .setParameter("clinic", clinicId).list();
				session().createSQLQuery("delete from " + TABLE + " where location_id = :clinic and month = :month")
				        .setParameter("clinic", clinicId).setParameter("month", day).executeUpdate();
				SQLQuery insert = session().createSQLQuery("insert into " + TABLE + " (location_id, month, "
				        + StringUtils.join(COLUMNS, ", ") + ", notes, changed_by, date_changed) values (:clinic, :month, :"
				        + StringUtils.join(COLUMNS, ", :") + ", :notes, :user, current_timestamp)");
				insert.setParameter("clinic", clinicId).setParameter("month", day)
				        .setParameter("notes", text, StringType.INSTANCE)
				        .setParameter("user", user == null ? null : user.getUserId());
				for (Map.Entry<String, Integer> value : values.entrySet()) {
					insert.setParameter(value.getKey(), value.getValue());
				}
				return insert.executeUpdate();
			});
		}
		catch (RuntimeException e) {
			throw e;
		}
		catch (Throwable e) {
			throw new APIException("Could not save the screening counts", e);
		}
	}
	
	/**
	 * The sums of the clinics' months from start to end, both included: of every clinic when clinics is
	 * null, of none when it is empty.
	 */
	public Summary summary(Collection<Integer> clinics, YearMonth start, YearMonth end) {
		Summary summary = new Summary();
		Object[] row = null;
		if (clinics == null || !clinics.isEmpty()) {
			StringBuilder select = new StringBuilder("select count(*)");
			for (String column : COLUMNS) {
				select.append(", coalesce(sum(").append(column).append("), 0)");
			}
			select.append(" from ").append(TABLE).append(" where month >= :start and month <= :end");
			if (clinics != null) {
				select.append(" and location_id in (:clinics)");
			}
			SQLQuery query = session().createSQLQuery(select.toString());
			query.setParameter("start", firstDay(start)).setParameter("end", firstDay(end));
			if (clinics != null) {
				query.setParameterList("clinics", clinics);
			}
			row = (Object[]) query.uniqueResult();
		}
		summary.months = row == null ? 0 : ((Number) row[0]).intValue();
		for (int i = 0; i < COLUMNS.size(); i++) {
			summary.sums.put(COLUMNS.get(i), row == null ? 0L : ((Number) row[i + 1]).longValue());
		}
		return summary;
	}
	
	private static Map<String, Integer> validated(YearMonth month, Map<String, Integer> counts, String notes) {
		// The zone where a month starts first, so a browser ahead of the server can save the month it is in.
		if (month.isAfter(YearMonth.now(ZoneId.of("Etc/GMT-14")))) {
			throw new IllegalArgumentException(month + " has not started, so it has no screening counts yet");
		}
		for (String column : counts.keySet()) {
			if (!COLUMNS.contains(column)) {
				throw new IllegalArgumentException("No screening count is named " + column);
			}
		}
		if (notes != null && notes.trim().length() > NOTES_LENGTH) {
			throw new IllegalArgumentException("Notes may be at most " + NOTES_LENGTH + " characters");
		}
		Map<String, Integer> values = new LinkedHashMap<String, Integer>();
		for (String column : COLUMNS) {
			Integer value = counts.get(column);
			if (value != null && value < 0) {
				throw new IllegalArgumentException(column + " cannot be below 0");
			}
			values.put(column, value == null ? 0 : value);
		}
		for (String setting : SETTINGS) {
			int screened = values.get(setting + "_screened");
			long ages = (long) values.get(setting + "_children") + values.get(setting + "_adolescents")
			        + values.get(setting + "_adults");
			if (ages != screened) {
				throw new IllegalArgumentException(
				        "The " + setting + " age groups add up to " + ages + ", not the " + screened + " screened");
			}
			for (String part : new String[] { "females", "positive" }) {
				if (values.get(setting + "_" + part) > screened) {
					throw new IllegalArgumentException(
					        "The " + setting + " " + part + " are more than the " + screened + " screened");
				}
			}
		}
		return values;
	}
	
	private static Date firstDay(YearMonth month) {
		return Date.valueOf(month.atDay(1));
	}
	
	private static DbSession session() {
		return Context.getRegisteredComponent("dbSessionFactory", DbSessionFactory.class).getCurrentSession();
	}
}
