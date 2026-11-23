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

import java.sql.Date;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.openmrs.Location;
import org.openmrs.User;
import org.openmrs.api.context.Context;
import org.openmrs.api.handler.BaseVoidHandler;
import org.openmrs.api.impl.BaseOpenmrsService;
import org.openmrs.module.actcore.api.ScreeningCountService;
import org.openmrs.module.actcore.api.db.ScreeningCountDAO;
import org.openmrs.module.actcore.screening.ScreeningCount;
import org.openmrs.module.actcore.screening.ScreeningCounts;
import org.springframework.transaction.annotation.Transactional;

/** Default implementation of {@link org.openmrs.module.actcore.api.ScreeningCountService}. */
@Transactional
public class ScreeningCountServiceImpl extends BaseOpenmrsService implements ScreeningCountService {
	
	private ScreeningCountDAO dao;
	
	public void setDao(ScreeningCountDAO dao) {
		this.dao = dao;
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.ScreeningCountService#getMonth(Location, YearMonth)
	 */
	@Override
	@Transactional(readOnly = true)
	public ScreeningCounts.Month getMonth(Location clinic, YearMonth month) {
		List<ScreeningCount> rows = dao.getCurrentCounts(clinic.getLocationId(), firstDay(month));
		ScreeningCounts.Month result = new ScreeningCounts.Month();
		ScreeningCount saved = rows.isEmpty() ? null : rows.get(0);
		result.saved = saved != null;
		result.notes = saved == null ? null : saved.getNotes();
		if (saved == null) {
			for (String column : ScreeningCounts.COLUMNS) {
				result.counts.put(column, 0);
			}
		} else {
			result.counts.putAll(saved.getCounts());
		}
		return result;
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.ScreeningCountService#saveMonth(Location, YearMonth, Map,
	 *      String)
	 */
	@Override
	public void saveMonth(Location clinic, YearMonth month, Map<String, Integer> counts, String notes) {
		Map<String, Integer> values = validated(month, counts, notes);
		Integer clinicId = clinic.getLocationId();
		Date day = firstDay(month);
		User user = Context.getAuthenticatedUser();
		// Saves of one clinic take turns here, so a month never has two current rows.
		dao.lockLocation(clinicId);
		java.util.Date now = new java.util.Date();
		for (ScreeningCount current : dao.getCurrentCounts(clinicId, day)) {
			new BaseVoidHandler().handle(current, user, now, ScreeningCounts.REPLACED_REASON);
		}
		dao.saveScreeningCount(new ScreeningCount(clinicId, day, values, StringUtils.trimToNull(notes)));
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.ScreeningCountService#getSummary(Collection, YearMonth,
	 *      YearMonth)
	 */
	@Override
	@Transactional(readOnly = true)
	public ScreeningCounts.Summary getSummary(Collection<Integer> clinics, YearMonth start, YearMonth end) {
		if (clinics != null && clinics.isEmpty()) {
			ScreeningCounts.Summary none = new ScreeningCounts.Summary();
			for (String column : ScreeningCounts.COLUMNS) {
				none.sums.put(column, 0L);
			}
			return none;
		}
		return dao.getSummary(clinics, firstDay(start), firstDay(end));
	}
	
	private static Map<String, Integer> validated(YearMonth month, Map<String, Integer> counts, String notes) {
		// The zone where a month starts first, so a browser ahead of the server can save the month it is in.
		if (month.isAfter(YearMonth.now(ZoneId.of("Etc/GMT-14")))) {
			throw new IllegalArgumentException(month + " has not started, so it has no screening counts yet");
		}
		for (String column : counts.keySet()) {
			if (!ScreeningCounts.COLUMNS.contains(column)) {
				throw new IllegalArgumentException("No screening count is named " + column);
			}
		}
		if (notes != null && notes.trim().length() > ScreeningCounts.NOTES_LENGTH) {
			throw new IllegalArgumentException("Notes may be at most " + ScreeningCounts.NOTES_LENGTH + " characters");
		}
		Map<String, Integer> values = new LinkedHashMap<String, Integer>();
		for (String column : ScreeningCounts.COLUMNS) {
			Integer value = counts.get(column);
			if (value != null && value < 0) {
				throw new IllegalArgumentException(column + " cannot be below 0");
			}
			values.put(column, value == null ? 0 : value);
		}
		for (String setting : ScreeningCounts.SETTINGS) {
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
}
