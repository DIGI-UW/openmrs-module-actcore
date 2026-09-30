/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.adherence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The port against ACT 2.0 itself: adherence-parity.json holds what ACT 2.0's
 * calculate_adherence_and_injection_date returns, with today pinned, for every call in its
 * test_adherence.py and for random histories, recorded once from the ACT 2.0 source.
 */
public class AdherenceCalculationParityTest {
	
	private static final Map<String, Integer> REGIMEN_DAYS = new HashMap<String, Integer>();
	
	private static final Map<String, Integer> DURATION_DAYS = new HashMap<String, Integer>();
	static {
		REGIMEN_DAYS.put("Q14 day BPG", 14);
		REGIMEN_DAYS.put("Q21 day BPG", 21);
		REGIMEN_DAYS.put("Q28 day BPG", 28);
		DURATION_DAYS.put("1 month", 30);
		DURATION_DAYS.put("3 months", 90);
		DURATION_DAYS.put("6 months", 180);
		DURATION_DAYS.put("12 months", 365);
	}
	
	@Test
	public void calculate_shouldGiveACT2sAdherenceAndDueDateForEveryCase() throws Exception {
		InputStream json = getClass().getClassLoader().getResourceAsStream("adherence-parity.json");
		JsonNode fixture = new ObjectMapper().readTree(json);
		LocalDate today = LocalDate.parse(fixture.get("today").asText());
		
		int index = 0;
		for (JsonNode c : fixture.get("cases")) {
			TreeMap<LocalDate, Integer> prescriptions = new TreeMap<LocalDate, Integer>();
			for (JsonNode p : c.get("prescriptions")) {
				Integer days = REGIMEN_DAYS.get(p.get(1).asText());
				prescriptions.put(LocalDate.parse(p.get(0).asText()), days == null ? 0 : days);
			}
			TreeSet<LocalDate> injections = new TreeSet<LocalDate>();
			for (JsonNode i : c.get("injections")) {
				injections.add(LocalDate.parse(i.asText()));
			}
			TreeMap<LocalDate, AdherenceCalculation.OralEntry> oral = new TreeMap<LocalDate, AdherenceCalculation.OralEntry>();
			for (JsonNode o : c.get("oral")) {
				Double estimate = o.get(1).isNull() ? null : o.get(1).asDouble();
				Integer duration = o.get(2).isNull() ? null : DURATION_DAYS.get(o.get(2).asText());
				oral.put(LocalDate.parse(o.get(0).asText()), new AdherenceCalculation.OralEntry(estimate, duration));
			}
			
			AdherenceCalculation.Result result = AdherenceCalculation.calculate(prescriptions, injections, oral, today);
			
			String label = "case " + index++ + ": " + c;
			if (c.get("adherence").isNull()) {
				assertNull(label, result.getAdherence());
			} else {
				assertEquals(label, c.get("adherence").asDouble(), result.getAdherence(), 0.0);
			}
			if (c.get("nextDue").isNull()) {
				assertNull(label, result.getNextDue());
			} else {
				assertEquals(label, LocalDate.parse(c.get("nextDue").asText()), result.getNextDue());
			}
		}
	}
}
