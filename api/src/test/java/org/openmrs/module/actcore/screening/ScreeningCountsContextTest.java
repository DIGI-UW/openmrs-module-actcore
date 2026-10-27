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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.time.YearMonth;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.openmrs.api.context.Context;
import org.openmrs.test.BaseModuleContextSensitiveTest;

public class ScreeningCountsContextTest extends BaseModuleContextSensitiveTest {
	
	private static final int CLINIC_A = 1;
	
	private static final int CLINIC_B = 2;
	
	private final ScreeningCounts screeningCounts = new ScreeningCounts();
	
	@Before
	public void createTheTable() {
		createTable();
	}
	
	/** The table as the module's changeset makes it, which the test context does not run. */
	private static void createTable() {
		StringBuilder columns = new StringBuilder();
		for (String column : ScreeningCounts.COLUMNS) {
			columns.append(", ").append(column).append(" int not null default 0");
		}
		Context.getAdministrationService().executeSQL("create table if not exists " + ScreeningCounts.TABLE
		        + " (screening_count_id int auto_increment primary key, location_id int not null, month date not null"
		        + columns + ", notes varchar(1000), changed_by int, date_changed datetime not null,"
		        + " unique (location_id, month))",
		    false);
	}
	
	/**
	 * A school row of 412 screened (96, 316, 0), 231 female, 14 positive, and the other settings as
	 * given.
	 */
	private static Map<String, Integer> september(int communityScreened) {
		Map<String, Integer> counts = new HashMap<String, Integer>();
		counts.put("school_screened", 412);
		counts.put("school_children", 96);
		counts.put("school_adolescents", 316);
		counts.put("school_adults", 0);
		counts.put("school_females", 231);
		counts.put("school_positive", 14);
		counts.put("community_screened", communityScreened);
		counts.put("community_adults", communityScreened);
		counts.put("community_females", 0);
		counts.put("community_positive", 1);
		return counts;
	}
	
	@Test
	public void save_shouldKeepTheMonthsCountsAndNotesWithAQuoteAsTyped() {
		screeningCounts.save(location(CLINIC_A), YearMonth.of(2026, 9), september(138), "Two primary schools'); --");
		
		ScreeningCounts.Month month = screeningCounts.get(location(CLINIC_A), YearMonth.of(2026, 9));
		
		assertTrue(month.saved);
		assertEquals(Integer.valueOf(412), month.counts.get("school_screened"));
		assertEquals(Integer.valueOf(316), month.counts.get("school_adolescents"));
		assertEquals(Integer.valueOf(138), month.counts.get("community_screened"));
		assertEquals(Integer.valueOf(0), month.counts.get("facility_screened"));
		assertEquals(ScreeningCounts.COLUMNS.size(), month.counts.size());
		assertEquals("Two primary schools'); --", month.notes);
	}
	
	@Test
	public void save_shouldReplaceTheMonthRatherThanAddASecondRow() {
		screeningCounts.save(location(CLINIC_A), YearMonth.of(2026, 9), september(138), "first");
		screeningCounts.save(location(CLINIC_A), YearMonth.of(2026, 9), september(55), null);
		
		ScreeningCounts.Month month = screeningCounts.get(location(CLINIC_A), YearMonth.of(2026, 9));
		
		assertEquals(Integer.valueOf(55), month.counts.get("community_screened"));
		assertNull(month.notes);
		assertEquals(1, ((Number) Context.getAdministrationService()
		        .executeSQL("select count(*) from " + ScreeningCounts.TABLE, true).get(0).get(0)).intValue());
	}
	
	@Test
	public void get_shouldGiveZerosForAMonthNotSaved() {
		ScreeningCounts.Month month = screeningCounts.get(location(CLINIC_A), YearMonth.of(2026, 8));
		
		assertFalse(month.saved);
		assertEquals(ScreeningCounts.COLUMNS.size(), month.counts.size());
		assertEquals(Integer.valueOf(0), month.counts.get("school_screened"));
		assertNull(month.notes);
	}
	
	@Test
	public void save_shouldRefuseAgeGroupsThatDoNotAddUpToTheScreened() {
		Map<String, Integer> counts = september(138);
		counts.put("school_children", 95);
		
		refused(counts, "school");
	}
	
	@Test
	public void save_shouldRefuseMoreFemalesOrPositivesThanScreened() {
		Map<String, Integer> females = september(138);
		females.put("community_females", 139);
		refused(females, "community");
		
		Map<String, Integer> positive = september(138);
		positive.put("community_positive", 139);
		refused(positive, "community");
	}
	
	@Test
	public void save_shouldRefuseANegativeCountAColumnItDoesNotKnowAndAMonthToCome() {
		Map<String, Integer> negative = september(138);
		negative.put("facility_females", -1);
		refused(negative, "facility_females");
		
		Map<String, Integer> unknown = september(138);
		unknown.put("school_total", 412);
		refused(unknown, "school_total");
		
		try {
			screeningCounts.save(location(CLINIC_A), YearMonth.now().plusMonths(1), september(138), null);
			fail("expected a month to come to be refused");
		}
		catch (IllegalArgumentException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("has not started"));
		}
	}
	
	@Test
	public void save_shouldRefuseAgeGroupsWhoseSumOverflowsAnInt() {
		Map<String, Integer> counts = september(138);
		counts.put("facility_children", Integer.MAX_VALUE);
		counts.put("facility_adolescents", Integer.MAX_VALUE);
		counts.put("facility_adults", 2);
		
		refused(counts, "facility");
	}
	
	@Test
	public void summary_shouldSumTheClinicsAndMonthsInTheRange() {
		screeningCounts.save(location(CLINIC_A), YearMonth.of(2026, 8), september(10), null);
		screeningCounts.save(location(CLINIC_A), YearMonth.of(2026, 9), september(20), null);
		screeningCounts.save(location(CLINIC_B), YearMonth.of(2026, 9), september(30), null);
		screeningCounts.save(location(CLINIC_A), YearMonth.of(2026, 10), september(40), null);
		
		ScreeningCounts.Summary both = screeningCounts.summary(Arrays.asList(CLINIC_A, CLINIC_B), YearMonth.of(2026, 8),
		    YearMonth.of(2026, 9));
		
		assertEquals(3, both.months);
		assertEquals(Long.valueOf(3 * 412 + 10 + 20 + 30), both.screened());
		assertEquals(Long.valueOf(3 * 14 + 3), both.positive());
		assertEquals(Long.valueOf(3 * 412 + 60 - 45), both.negative());
		assertEquals(Long.valueOf(60), both.sums.get("community_screened"));
		assertEquals(Long.valueOf(3 * 96), both.total("children"));
		assertEquals(Long.valueOf(3 * 231), both.total("females"));
		
		ScreeningCounts.Summary every = screeningCounts.summary(null, YearMonth.of(2026, 1), YearMonth.of(2026, 12));
		assertEquals(4, every.months);
		assertEquals(Long.valueOf(4 * 412 + 100), every.screened());
		
		ScreeningCounts.Summary one = screeningCounts.summary(Collections.singletonList(CLINIC_B), YearMonth.of(2026, 1),
		    YearMonth.of(2026, 12));
		assertEquals(1, one.months);
		assertEquals(Long.valueOf(412 + 30), one.screened());
	}
	
	@Test
	public void summary_shouldCountNothingForNoClinicsRatherThanEveryClinic() {
		screeningCounts.save(location(CLINIC_A), YearMonth.of(2026, 9), september(20), null);
		
		ScreeningCounts.Summary none = screeningCounts.summary(Collections.<Integer> emptyList(), YearMonth.of(2026, 1),
		    YearMonth.of(2026, 12));
		
		assertEquals(0, none.months);
		assertEquals(Long.valueOf(0), none.screened());
		assertEquals(Long.valueOf(0), none.sums.get("school_screened"));
	}
	
	private void refused(Map<String, Integer> counts, String naming) {
		try {
			screeningCounts.save(location(CLINIC_A), YearMonth.of(2026, 9), counts, null);
			fail("expected the counts to be refused");
		}
		catch (IllegalArgumentException e) {
			assertTrue(e.getMessage(), e.getMessage().contains(naming));
		}
		assertFalse(screeningCounts.get(location(CLINIC_A), YearMonth.of(2026, 9)).saved);
	}
	
	private static org.openmrs.Location location(int id) {
		return Context.getLocationService().getLocation(id);
	}
}
