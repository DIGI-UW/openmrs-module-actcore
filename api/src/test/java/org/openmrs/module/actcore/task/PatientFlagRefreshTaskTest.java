/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.task;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.openmrs.Cohort;
import org.openmrs.Patient;
import org.openmrs.module.patientflags.Flag;
import org.openmrs.module.patientflags.PatientFlag;
import org.openmrs.module.patientflags.api.FlagService;

public class PatientFlagRefreshTaskTest {
	
	private FlagService flagService;
	
	private Flag flag;
	
	private Set<Integer> alreadyFlagged;
	
	private PatientFlagRefreshTask task;
	
	@Before
	public void setUp() {
		flagService = mock(FlagService.class);
		flag = new Flag();
		flag.setFlagId(1);
		flag.setName("overdue");
		flag.setMessage("overdue");
		flag.setEnabled(Boolean.TRUE);
		alreadyFlagged = new LinkedHashSet<Integer>();
		
		task = new PatientFlagRefreshTask() {
			
			@Override
			Map<Integer, String> alreadyFlagged(Flag flag) {
				Map<Integer, String> messages = new LinkedHashMap<Integer, String>();
				for (Integer patientId : alreadyFlagged) {
					messages.put(patientId, flag.getMessage());
				}
				return messages;
			}
		};
	}
	
	private void matches(Integer... patientIds) {
		Cohort cohort = new Cohort();
		for (Integer patientId : patientIds) {
			cohort.addMembership(new org.openmrs.CohortMembership(patientId));
		}
		when(flagService.getFlaggedPatients(eq(flag), any(Map.class))).thenReturn(cohort);
	}
	
	@Test
	public void raisesAFlagForAPatientThatHasStartedMatching() {
		matches(7);
		
		task.reconcile(flagService, flag);
		
		assertEquals(1, task.raised);
		assertEquals(0, task.cleared);
		ArgumentCaptor<PatientFlag> saved = ArgumentCaptor.forClass(PatientFlag.class);
		verify(flagService).savePatientFlag(saved.capture());
		assertEquals(Integer.valueOf(7), saved.getValue().getPatient().getPatientId());
	}
	
	@Test
	public void clearsAFlagForAPatientThatHasStoppedMatching() {
		alreadyFlagged.addAll(Arrays.asList(7));
		matches();
		
		task.reconcile(flagService, flag);
		
		assertEquals(0, task.raised);
		assertEquals(1, task.cleared);
		verify(flagService).deletePatientFlagForPatient(any(Patient.class), eq(flag));
	}
	
	/**
	 * Rewriting a row that did not change would reset its date_created.
	 */
	@Test
	public void leavesAPatientThatStillMatchesUntouched() {
		alreadyFlagged.addAll(Arrays.asList(7));
		matches(7);
		
		task.reconcile(flagService, flag);
		
		assertEquals(0, task.raised);
		assertEquals(0, task.cleared);
		verify(flagService, never()).savePatientFlag(any(PatientFlag.class));
		verify(flagService, never()).deletePatientFlagForPatient(any(Patient.class), any(Flag.class));
	}
	
	@Test
	public void addsAndRemovesInTheSamePassWithoutDisturbingTheRest() {
		alreadyFlagged.addAll(Arrays.asList(7, 8));
		matches(8, 9);
		
		task.reconcile(flagService, flag);
		
		assertEquals(1, task.raised);
		assertEquals(1, task.cleared);
		verify(flagService, times(1)).savePatientFlag(any(PatientFlag.class));
		verify(flagService, times(1)).deletePatientFlagForPatient(any(Patient.class), eq(flag));
	}
	
	@Test
	public void treatsAnEmptyResultAsNobodyMatching() {
		alreadyFlagged.addAll(new HashSet<Integer>(Arrays.asList(7)));
		when(flagService.getFlaggedPatients(eq(flag), any(Map.class))).thenReturn(null);
		
		task.reconcile(flagService, flag);
		
		assertEquals(0, task.raised);
		assertEquals(1, task.cleared);
	}
}
