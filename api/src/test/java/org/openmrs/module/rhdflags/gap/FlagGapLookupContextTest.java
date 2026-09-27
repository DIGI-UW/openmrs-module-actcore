/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.rhdflags.gap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.Before;
import org.junit.Test;
import org.openmrs.Encounter;
import org.openmrs.EncounterType;
import org.openmrs.GlobalProperty;
import org.openmrs.Patient;
import org.openmrs.Person;
import org.openmrs.PersonName;
import org.openmrs.Privilege;
import org.openmrs.Role;
import org.openmrs.User;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.APIException;
import org.openmrs.api.UserService;
import org.openmrs.api.context.Context;
import org.openmrs.module.patientflags.Flag;
import org.openmrs.module.patientflags.api.FlagService;
import org.openmrs.module.patientflags.evaluator.SQLFlagEvaluator;
import org.openmrs.test.BaseModuleContextSensitiveTest;

public class FlagGapLookupContextTest extends BaseModuleContextSensitiveTest {
	
	private static final int PATIENT = 7;
	
	private static final String WEIGHT = "c607c80f-1ea9-4da3-bb88-6276ce8868dd";
	
	private static final String CD4 = "a09ab2c5-878e-4905-b25d-5784167d0216";
	
	private static final String ENCOUNTER_3 = "6519d653-393b-4118-9c83-a3715b82d4ac";
	
	private static final String ENCOUNTER_5 = "e403fafb-e5e4-42d0-9d11-4f52e89d148c";
	
	private static final String BASIC_FORM = "d9218f76-6c39-45f4-8efa-4c5c6c199f50";
	
	private static final String PASSWORD = "Gapreader123";
	
	private final FlagGapLookup lookup = new FlagGapLookup();
	
	private Flag flag;
	
	@Before
	public void setUp() {
		flag = new Flag();
		flag.setUuid(UUID.randomUUID().toString());
		flag.setName("outcome missing");
		flag.setCriteria("select p.patient_id from patient p where p.patient_id = " + PATIENT);
		flag.setEvaluator(SQLFlagEvaluator.class.getName());
		flag.setMessage("outcome missing");
		flag.setEnabled(Boolean.TRUE);
		Context.getService(FlagService.class).saveFlag(flag);
	}
	
	@Test
	public void returnsOneGapPerRowWithTheEncountersFormAndQuestion() {
		configure("select e.uuid, case e.encounter_id when 3 then '" + WEIGHT + "' else '" + CD4 + "' end"
		        + " from encounter e where e.patient_id = :patientId and e.encounter_id in (3, 5)"
		        + " order by e.encounter_id");
		
		List<FlagGap> gaps = lookup.find(patient(), flag);
		
		assertEquals(2, gaps.size());
		assertEquals(ENCOUNTER_3, gaps.get(0).getEncounter().getUuid());
		assertEquals(BASIC_FORM, gaps.get(0).getEncounter().getForm().getUuid());
		assertEquals(WEIGHT, gaps.get(0).getQuestion().getUuid());
		assertEquals(ENCOUNTER_5, gaps.get(1).getEncounter().getUuid());
		assertEquals(CD4, gaps.get(1).getQuestion().getUuid());
	}
	
	@Test
	public void returnsNullWhenTheFlagHasNoQuery() {
		assertNull(lookup.find(patient(), flag));
	}
	
	@Test
	public void dropsARowWhoseEncounterBelongsToAnotherPatient() {
		// ignores the patient beyond the token, so it returns patient 2's encounter 6 as well
		configure("select e.uuid, '" + WEIGHT + "' from encounter e where :patientId = :patientId"
		        + " and e.encounter_id in (3, 6) order by e.encounter_id");
		
		assertEquals(ENCOUNTER_3, onlyEncounter(lookup.find(patient(), flag)));
	}
	
	@Test
	public void dropsAVoidedEncounter() {
		configure("select e.uuid, '" + WEIGHT + "' from encounter e where e.patient_id = :patientId"
		        + " and e.encounter_id in (3, 5) order by e.encounter_id");
		Encounter five = Context.getEncounterService().getEncounterByUuid(ENCOUNTER_5);
		Context.getEncounterService().voidEncounter(five, "test");
		
		assertEquals(ENCOUNTER_3, onlyEncounter(lookup.find(patient(), flag)));
	}
	
	@Test
	public void dropsARowWhoseQuestionIsNotAConcept() {
		configure("select e.uuid, case e.encounter_id when 3 then '" + WEIGHT + "' else 'not a concept' end"
		        + " from encounter e where e.patient_id = :patientId and e.encounter_id in (3, 5)"
		        + " order by e.encounter_id");
		
		assertEquals(ENCOUNTER_3, onlyEncounter(lookup.find(patient(), flag)));
	}
	
	@Test
	public void warnsOfAnEncounterCellThatIsNotAnEncounterUuid() {
		configure("select e.encounter_id, '" + WEIGHT + "' from encounter e where e.patient_id = :patientId");
		List<FlagGap> gaps = new ArrayList<FlagGap>();
		
		List<LogEvent> warnings = warningsWhileFinding(gaps);
		
		assertTrue(gaps.isEmpty());
		assertTrue(!warnings.isEmpty());
		String warning = warnings.get(0).getMessage().getFormattedMessage();
		assertTrue(warning, warning.contains("not an encounter uuid"));
		assertTrue(warning, warning.contains("column 1"));
		assertTrue(warning, warning.contains("Integer"));
	}
	
	@Test
	public void keepsAnEncounterCellThatIsNotAnEncounterUuidOutOfTheLog() {
		configure("select u.password, '" + WEIGHT + "' from users u where :patientId > 0 and u.password is not null");
		List<FlagGap> gaps = new ArrayList<FlagGap>();
		
		List<LogEvent> warnings = warningsWhileFinding(gaps);
		
		assertTrue(!warnings.isEmpty());
		assertNoPasswordIn(warnings);
	}
	
	@Test
	public void keepsAQuestionCellThatIsNotAConceptUuidOutOfTheLog() {
		configure("select e.uuid, u.password from encounter e, users u where e.patient_id = :patientId"
		        + " and e.encounter_id = 3 and u.password is not null");
		List<FlagGap> gaps = new ArrayList<FlagGap>();
		
		List<LogEvent> warnings = warningsWhileFinding(gaps);
		
		assertTrue(!warnings.isEmpty());
		assertNoPasswordIn(warnings);
	}
	
	@Test
	public void warnsOfAQuestionCellThatIsNotAConceptUuid() {
		configure("select e.uuid, 5089 from encounter e where e.patient_id = :patientId and e.encounter_id = 3");
		List<FlagGap> gaps = new ArrayList<FlagGap>();
		
		List<LogEvent> warnings = warningsWhileFinding(gaps);
		
		assertTrue(gaps.isEmpty());
		assertEquals(1, warnings.size());
		String warning = warnings.get(0).getMessage().getFormattedMessage();
		assertTrue(warning, warning.contains("not a concept uuid"));
		assertTrue(warning, warning.contains("column 2"));
		assertTrue(warning, warning.contains("Integer"));
	}
	
	@Test
	public void doesNotWarnOfAnEncounterItFiltersOut() {
		configure("select e.uuid, '" + WEIGHT + "' from encounter e where :patientId = :patientId"
		        + " and e.encounter_id in (3, 5, 6) order by e.encounter_id");
		Encounter five = Context.getEncounterService().getEncounterByUuid(ENCOUNTER_5);
		Context.getEncounterService().voidEncounter(five, "test");
		List<FlagGap> gaps = new ArrayList<FlagGap>();
		
		List<LogEvent> warnings = warningsWhileFinding(gaps);
		
		assertEquals(ENCOUNTER_3, onlyEncounter(gaps));
		assertTrue(warnings.isEmpty());
	}
	
	@Test(expected = APIException.class)
	public void refusesAQueryThatDoesNotNameThePatient() {
		configure("select e.uuid, '" + WEIGHT + "' from encounter e");
		
		lookup.find(patient(), flag);
	}
	
	@Test
	public void skipsARowWithoutAnEncounterOrAQuestion() {
		configure("select case when e.encounter_id = 4 then null else e.uuid end,"
		        + " case when e.encounter_id = 5 then null else '" + WEIGHT + "' end from encounter e"
		        + " where e.patient_id = :patientId and e.encounter_id in (3, 4, 5) order by e.encounter_id");
		
		assertEquals(ENCOUNTER_3, onlyEncounter(lookup.find(patient(), flag)));
	}
	
	@Test(expected = APIException.class)
	public void refusesAQueryThatDoesNotReturnTwoColumns() {
		configure("select e.uuid from encounter e where e.patient_id = :patientId");
		
		lookup.find(patient(), flag);
	}
	
	@Test
	public void findsTheFlagByUuid() {
		assertEquals(flag.getFlagId(), lookup.getFlag(flag.getUuid()).getFlagId());
		assertNull(lookup.getFlag(UUID.randomUUID().toString()));
	}
	
	@Test
	public void aUserWhoCanViewPatientFlagsGetsTheGaps() {
		configure(
		    "select e.uuid, '" + WEIGHT + "' from encounter e where e.patient_id = :patientId" + " and e.encounter_id = 3");
		Patient patient = patient();
		
		authenticateWith("View Patient Flags", "Get Patients", "Get Encounters", "Get Concepts");
		
		assertEquals(flag.getFlagId(), lookup.getFlag(flag.getUuid()).getFlagId());
		assertEquals(ENCOUNTER_3, onlyEncounter(lookup.find(patient, flag)));
	}
	
	@Test
	public void leavesOutAnEncounterTheUserMayNotView() {
		configure("select e.uuid, '" + WEIGHT + "' from encounter e where e.patient_id = :patientId"
		        + " and e.encounter_id in (3, 5) order by e.encounter_id");
		Patient patient = patient();
		// encounter 3 is of type 2, encounter 5 of type 1
		EncounterType restricted = Context.getEncounterService().getEncounterType(2);
		restricted.setViewPrivilege(savedPrivilege("View Restricted Encounters"));
		Context.getEncounterService().saveEncounterType(restricted);
		
		authenticateWith("View Patient Flags", "Get Patients", "Get Encounters", "Get Concepts");
		
		assertEquals(ENCOUNTER_5, onlyEncounter(lookup.find(patient, flag)));
	}
	
	@Test
	public void aUserWhoCannotViewPatientFlagsIsRefused() {
		configure("select e.uuid, '" + WEIGHT + "' from encounter e where e.patient_id = :patientId");
		Patient patient = patient();
		
		authenticateWith("Get Patients", "Get Encounters", "Get Concepts");
		
		try {
			lookup.getFlag(flag.getUuid());
			fail("expected reading the flag to require View Patient Flags");
		}
		catch (APIAuthenticationException expected) {}
		try {
			lookup.find(patient, flag);
			fail("expected the lookup to require View Patient Flags");
		}
		catch (APIAuthenticationException expected) {}
	}
	
	private void assertNoPasswordIn(List<LogEvent> warnings) {
		List<List<Object>> passwords = Context.getAdministrationService()
		        .executeSQL("select password from users where password is not null", true);
		assertTrue(!passwords.isEmpty());
		for (LogEvent warning : warnings) {
			String message = warning.getMessage().getFormattedMessage();
			for (List<Object> password : passwords) {
				assertTrue(message, !message.contains(password.get(0).toString()));
			}
		}
	}
	
	private Patient patient() {
		return Context.getPatientService().getPatient(PATIENT);
	}
	
	private void configure(String sql) {
		Context.getAdministrationService()
		        .saveGlobalProperty(new GlobalProperty(FlagGapLookup.GAP_QUERY_PREFIX + flag.getUuid(), sql));
	}
	
	private List<LogEvent> warningsWhileFinding(List<FlagGap> gaps) {
		final List<LogEvent> events = new ArrayList<LogEvent>();
		AbstractAppender appender = new AbstractAppender("capture", null, null, true, Property.EMPTY_ARRAY) {
			
			@Override
			public void append(LogEvent event) {
				if (event.getLevel().isMoreSpecificThan(Level.WARN)) {
					events.add(event.toImmutable());
				}
			}
		};
		appender.start();
		Logger logger = (Logger) LogManager.getLogger(FlagGapLookup.class);
		Level level = logger.getLevel();
		logger.addAppender(appender);
		// The test log4j2.xml turns every logger off.
		logger.setLevel(Level.WARN);
		try {
			gaps.addAll(lookup.find(patient(), flag));
		}
		finally {
			logger.removeAppender(appender);
			logger.setLevel(level);
		}
		return events;
	}
	
	private String onlyEncounter(List<FlagGap> gaps) {
		List<String> uuids = new ArrayList<String>();
		for (FlagGap gap : gaps) {
			uuids.add(gap.getEncounter().getUuid());
		}
		assertEquals(1, uuids.size());
		return uuids.get(0);
	}
	
	private Privilege savedPrivilege(String name) {
		UserService users = Context.getUserService();
		Privilege privilege = users.getPrivilege(name);
		return privilege != null ? privilege : users.savePrivilege(new Privilege(name));
	}
	
	private void authenticateWith(String... privileges) {
		UserService users = Context.getUserService();
		Role role = new Role("gap reader " + UUID.randomUUID());
		for (String name : privileges) {
			role.addPrivilege(savedPrivilege(name));
		}
		users.saveRole(role);
		
		Person person = new Person();
		person.addName(new PersonName("Gap", null, "Reader"));
		person.setGender("F");
		User user = new User(person);
		user.setUsername("gapreader");
		user.addRole(role);
		users.createUser(user, PASSWORD);
		
		Context.logout();
		Context.authenticate("gapreader", PASSWORD);
	}
}
