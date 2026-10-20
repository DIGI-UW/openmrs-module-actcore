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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.function.IntConsumer;

import org.junit.Test;
import org.openmrs.Patient;
import org.openmrs.Person;
import org.openmrs.PersonName;
import org.openmrs.api.PatientService;
import org.openmrs.api.PersonService;
import org.openmrs.api.context.Context;
import org.openmrs.module.patientflags.Flag;
import org.openmrs.module.patientflags.api.FlagService;
import org.openmrs.module.patientflags.evaluator.SQLFlagEvaluator;
import org.openmrs.test.BaseModuleContextSensitiveTest;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public class RefreshFlagsOnPersonSaveContextTest extends BaseModuleContextSensitiveTest {
	
	private final List<Integer> refreshed = new ArrayList<Integer>();
	
	private final IntConsumer recorder = new IntConsumer() {
		
		@Override
		public void accept(int patientId) {
			refreshed.add(patientId);
		}
	};
	
	private PatientService patients(IntConsumer flagRefresher) {
		ProxyFactory proxy = new ProxyFactory(Context.getPatientService());
		proxy.addInterface(PatientService.class);
		proxy.addAdvice(new RefreshFlagsOnPersonSave(flagRefresher));
		return (PatientService) proxy.getProxy();
	}
	
	private PersonService persons(IntConsumer flagRefresher) {
		ProxyFactory proxy = new ProxyFactory(Context.getPersonService());
		proxy.addInterface(PersonService.class);
		proxy.addAdvice(new RefreshFlagsOnPersonSave(flagRefresher));
		return (PersonService) proxy.getProxy();
	}
	
	/** What the transaction manager does once the test's transaction commits. */
	private static void commit() {
		for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
			synchronization.afterCommit();
		}
	}
	
	@Test
	public void savePatient_shouldRefreshThePatientsFlagsOnceTheSaveCommits() {
		Patient patient = Context.getPatientService().getPatient(2);
		patient.setDead(true);
		patient.setDeathDate(new Date());
		patient.setCauseOfDeath(Context.getConceptService().getConcept(3));
		
		patients(recorder).savePatient(patient);
		assertTrue(refreshed.isEmpty());
		commit();
		
		assertEquals(Arrays.asList(2), refreshed);
	}
	
	@Test
	public void savePerson_shouldRefreshTheFlagsOfAPersonWhoIsAPatient() {
		Person person = Context.getPersonService().getPerson(2);
		person.setDead(true);
		person.setDeathDate(new Date());
		person.setCauseOfDeath(Context.getConceptService().getConcept(3));
		
		persons(recorder).savePerson(person);
		commit();
		
		assertEquals(Arrays.asList(2), refreshed);
	}
	
	@Test
	public void savePerson_shouldLeaveAPersonWhoIsNotAPatientAlone() {
		Person person = new Person();
		person.addName(new PersonName("Nurse", null, "Okello"));
		person.setGender("F");
		
		persons(recorder).savePerson(person);
		commit();
		
		assertNotNull(person.getPersonId());
		assertTrue(refreshed.isEmpty());
	}
	
	@Test
	public void savePatient_shouldRefreshEachPatientOnceHoweverManyTimesTheTransactionSavesThem() {
		PatientService service = patients(recorder);
		Patient patient = Context.getPatientService().getPatient(2);
		service.savePatient(patient);
		service.savePatient(patient);
		service.savePatient(Context.getPatientService().getPatient(7));
		
		commit();
		
		assertEquals(Arrays.asList(2, 7), refreshed);
	}
	
	@Test
	public void voidPerson_shouldRefreshTheFlagsOfAPersonWhoIsAPatient() {
		persons(recorder).voidPerson(Context.getPersonService().getPerson(2), "duplicate");
		commit();
		
		assertEquals(Arrays.asList(2), refreshed);
	}
	
	@Test
	public void savePatient_shouldRefreshAPatientOnceWhenThePersonAndPatientAdviceBothSaveThem() {
		// As core's mergePatients voids the patient and then their person, through both services.
		patients(recorder).savePatient(Context.getPatientService().getPatient(2));
		persons(recorder).savePerson(Context.getPersonService().getPerson(2));
		commit();
		
		assertEquals(Arrays.asList(2), refreshed);
	}
	
	@Test
	public void savePatient_shouldKeepTheSaveWhenTheFlagRefreshFails() {
		Patient patient = Context.getPatientService().getPatient(2);
		patient.setDead(true);
		patient.setDeathDate(new Date());
		patient.setCauseOfDeath(Context.getConceptService().getConcept(3));
		
		patients(new IntConsumer() {
			
			@Override
			public void accept(int patientId) {
				throw new IllegalStateException("flags failed");
			}
		}).savePatient(patient);
		commit();
		
		assertTrue(Context.getPatientService().getPatient(2).getDead());
	}
	
	@Test
	public void savePatient_shouldClearAFlagThatReadsThePersonOnceMarkingDeceasedCommits() {
		// As RHD death not recorded on patient reads the person: raised while the patient is not marked dead.
		Flag notRecorded = new Flag("Death not recorded " + System.nanoTime(),
		        "select pat.patient_id from patient pat join person p on p.person_id = pat.patient_id"
		                + " where p.dead = false and pat.patient_id = 2",
		        "Death not recorded on patient");
		notRecorded.setEvaluator(SQLFlagEvaluator.class.getName());
		notRecorded.setEnabled(true);
		Context.getService(FlagService.class).saveFlag(notRecorded);
		PatientFlagRefreshInTest.refreshPatient(2);
		assertEquals(1, flagged(notRecorded, 2));
		Patient patient = Context.getPatientService().getPatient(2);
		patient.setDead(true);
		patient.setDeathDate(new Date());
		patient.setCauseOfDeath(Context.getConceptService().getConcept(3));
		
		patients(new IntConsumer() {
			
			@Override
			public void accept(int patientId) {
				Context.flushSession();
				PatientFlagRefreshInTest.refreshPatient(patientId);
			}
		}).savePatient(patient);
		assertEquals(1, flagged(notRecorded, 2));
		commit();
		
		assertEquals(0, flagged(notRecorded, 2));
	}
	
	private static int flagged(Flag flag, int patientId) {
		Context.flushSession();
		return ((Number) Context.getAdministrationService()
		        .executeSQL("select count(*) from patientflags_patient_flag where flag_id = " + flag.getFlagId()
		                + " and patient_id = " + patientId + " and voided = false",
		            true)
		        .get(0).get(0)).intValue();
	}
}
