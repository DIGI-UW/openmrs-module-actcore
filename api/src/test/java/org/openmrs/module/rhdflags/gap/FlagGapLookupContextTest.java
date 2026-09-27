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
import java.util.Arrays;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.Before;
import org.junit.Test;
import org.openmrs.CohortMembership;
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
import org.openmrs.api.UserService;
import org.openmrs.api.context.Context;
import org.openmrs.module.patientflags.Flag;
import org.openmrs.module.patientflags.api.FlagService;
import org.openmrs.module.patientflags.evaluator.GroovyFlagEvaluator;
import org.openmrs.module.patientflags.evaluator.SQLFlagEvaluator;
import org.openmrs.test.BaseModuleContextSensitiveTest;

public class FlagGapLookupContextTest extends BaseModuleContextSensitiveTest {
	
	private static final int PATIENT = 7;
	
	private static final String WEIGHT = "c607c80f-1ea9-4da3-bb88-6276ce8868dd";
	
	private static final String CD4 = "a09ab2c5-878e-4905-b25d-5784167d0216";
	
	private static final String ENCOUNTER_3 = "6519d653-393b-4118-9c83-a3715b82d4ac";
	
	private static final String ENCOUNTER_4 = "eec646cb-c847-45a7-98bc-91c8c4f70add";
	
	private static final String ENCOUNTER_5 = "e403fafb-e5e4-42d0-9d11-4f52e89d148c";
	
	private static final String BASIC_FORM = "d9218f76-6c39-45f4-8efa-4c5c6c199f50";
	
	private static final String PASSWORD = "Gapreader123";
	
	private final FlagGapLookup lookup = new FlagGapLookup();
	
	private Flag flag;
	
	private List<FlagGap> found;
	
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
		configure("select e.patient_id, e.uuid, case e.encounter_id when 3 then '" + WEIGHT + "' else '" + CD4 + "' end"
		        + " from encounter e where e.encounter_id in (3, 5)");
		
		List<FlagGap> gaps = lookup.find(patient(), flag);
		
		assertEquals(2, gaps.size());
		assertEquals(ENCOUNTER_3, gaps.get(0).getEncounter().getUuid());
		assertEquals(BASIC_FORM, gaps.get(0).getEncounter().getForm().getUuid());
		assertEquals(WEIGHT, gaps.get(0).getQuestion().getUuid());
		assertEquals(ENCOUNTER_5, gaps.get(1).getEncounter().getUuid());
		assertEquals(CD4, gaps.get(1).getQuestion().getUuid());
	}
	
	@Test
	public void thePatientsAFlagRaisesAreTheOnesWithGaps() {
		configure("select e.patient_id, e.uuid, '" + WEIGHT + "' from encounter e where e.voided = false"
		        + " and e.encounter_id in (3, 6)");
		Set<Integer> raised = new HashSet<Integer>();
		for (CohortMembership membership : Context.getService(FlagService.class)
		        .getFlaggedPatients(flag, new HashMap<Object, Object>()).getMemberships()) {
			raised.add(membership.getPatientId());
		}
		
		Set<Integer> withGaps = new HashSet<Integer>();
		for (Integer patientId : Arrays.asList(2, 6, 7, 8)) {
			List<FlagGap> gaps = lookup.find(Context.getPatientService().getPatient(patientId), flag);
			if (gaps != null && !gaps.isEmpty()) {
				withGaps.add(patientId);
			} else {
				assertNull(gaps);
			}
		}
		
		assertEquals(new HashSet<Integer>(Arrays.asList(2, 7)), raised);
		assertEquals(raised, withGaps);
	}
	
	@Test
	public void sortsTheGapsByEncounterDate() {
		configure("select e.patient_id, e.uuid, '" + WEIGHT + "' from encounter e where e.encounter_id in (3, 4, 5)");
		Encounter three = Context.getEncounterService().getEncounterByUuid(ENCOUNTER_3);
		three.setEncounterDatetime(new GregorianCalendar(2009, Calendar.JANUARY, 1).getTime());
		Context.getEncounterService().saveEncounter(three);
		
		assertEquals(Arrays.asList(ENCOUNTER_4, ENCOUNTER_5, ENCOUNTER_3), encounterUuids(lookup.find(patient(), flag)));
	}
	
	@Test
	public void returnsNullForCriteriaWithoutGapColumns() {
		List<LogEvent> warnings = warningsWhileFinding();
		
		assertNull(found);
		assertTrue(warnings.isEmpty());
	}
	
	@Test
	public void rewritesCriteriaWrittenInCapitalsOverSeveralLinesWithATrailingSemicolon() {
		configure("SELECT e.patient_id, e.uuid, '" + WEIGHT + "' FROM encounter e\nWHERE e.encounter_id = 3;");
		
		assertEquals(ENCOUNTER_3, onlyEncounter(lookup.find(patient(), flag)));
	}
	
	@Test
	public void rewritesCriteriaAsPatientflagsDoesWhenWhereIsOnlyInANameOrASubquery() {
		assertEquals("fails",
		    outcomeAgreedWithPatientflags("select e.patient_id, e.uuid, '" + WEIGHT + "' as nowhere from encounter e"));
		assertEquals("true", outcomeAgreedWithPatientflags("select e.patient_id, e.uuid, '" + WEIGHT + "' from encounter e"
		        + " join (select encounter_id from encounter where voided = false) v on v.encounter_id = e.encounter_id"));
	}
	
	@Test
	public void returnsNoGapsRatherThanNullWhenEveryRowIsLeftOut() {
		configure("select e.patient_id, e.uuid, '" + WEIGHT + "' from encounter e where e.encounter_id = 3");
		Encounter three = Context.getEncounterService().getEncounterByUuid(ENCOUNTER_3);
		Context.getEncounterService().voidEncounter(three, "test");
		
		assertEquals(new ArrayList<FlagGap>(), lookup.find(patient(), flag));
	}
	
	@Test
	public void returnsNullForAVoidedPatient() {
		configure("select e.patient_id, e.uuid, '" + WEIGHT + "' from encounter e where e.encounter_id = 3");
		Patient patient = patient();
		Context.getPatientService().voidPatient(patient, "test");
		
		assertNull(lookup.find(patient, flag));
	}
	
	@Test
	public void returnsNullForAPatientTheCriteriaDoNotMatch() {
		configure("select e.patient_id, e.uuid, '" + WEIGHT + "' from encounter e where e.encounter_id = 6");
		
		assertNull(lookup.find(patient(), flag));
	}
	
	@Test
	public void ignoresAGapQueryGlobalProperty() {
		Context.getAdministrationService().saveGlobalProperty(new GlobalProperty("rhdflags.gapQuery." + flag.getUuid(),
		        "select e.uuid, '" + WEIGHT + "' from encounter e where e.patient_id = :patientId"));
		
		assertNull(lookup.find(patient(), flag));
	}
	
	@Test
	public void returnsNullForAFlagThatIsNotEvaluatedBySql() {
		configure("select e.patient_id, e.uuid, '" + WEIGHT + "' from encounter e where e.encounter_id = 3");
		flag.setEvaluator(GroovyFlagEvaluator.class.getName());
		
		assertNull(lookup.find(patient(), flag));
	}
	
	@Test
	public void returnsNullForCriteriaWithoutAPatientColumn() {
		configure("select patient_id, 'x', 'y' from patient where patient_id = " + PATIENT);
		
		assertNull(lookup.find(patient(), flag));
	}
	
	@Test
	public void warnsOfCriteriaThatReturnTwoColumns() {
		configure("select e.patient_id, e.uuid from encounter e where e.encounter_id = 3");
		List<LogEvent> warnings = warningsWhileFinding();
		
		assertNull(found);
		assertEquals(1, warnings.size());
		String warning = warnings.get(0).getMessage().getFormattedMessage();
		assertTrue(warning, warning.contains("2 columns"));
	}
	
	@Test
	public void dropsARowWhoseEncounterBelongsToAnotherPatient() {
		// the join ignores the patient, so it returns patient 2's encounter 6 as well
		configure("select p.patient_id, e.uuid, '" + WEIGHT + "' from patient p join encounter e"
		        + " on e.encounter_id in (3, 6) where p.patient_id > 0");
		
		assertEquals(ENCOUNTER_3, onlyEncounter(lookup.find(patient(), flag)));
	}
	
	@Test
	public void dropsAVoidedEncounter() {
		configure("select e.patient_id, e.uuid, '" + WEIGHT + "' from encounter e where e.encounter_id in (3, 5)");
		Encounter five = Context.getEncounterService().getEncounterByUuid(ENCOUNTER_5);
		Context.getEncounterService().voidEncounter(five, "test");
		
		assertEquals(ENCOUNTER_3, onlyEncounter(lookup.find(patient(), flag)));
	}
	
	@Test
	public void dropsARowWhoseQuestionIsNotAConcept() {
		configure("select e.patient_id, e.uuid, case e.encounter_id when 3 then '" + WEIGHT + "' else 'not a concept' end"
		        + " from encounter e where e.encounter_id in (3, 5)");
		
		assertEquals(ENCOUNTER_3, onlyEncounter(lookup.find(patient(), flag)));
	}
	
	@Test
	public void warnsOfAnEncounterCellThatIsNotAnEncounterUuid() {
		configure("select e.patient_id, e.encounter_id, '" + WEIGHT + "' from encounter e");
		List<LogEvent> warnings = warningsWhileFinding();
		
		assertEquals(new ArrayList<FlagGap>(), found);
		assertTrue(!warnings.isEmpty());
		String warning = warnings.get(0).getMessage().getFormattedMessage();
		assertTrue(warning, warning.contains("not an encounter uuid"));
		assertTrue(warning, warning.contains("column 2"));
		assertTrue(warning, warning.contains("Integer"));
	}
	
	@Test
	public void keepsAnEncounterCellThatIsNotAnEncounterUuidOutOfTheLog() {
		configure("select p.patient_id, u.password, '" + WEIGHT + "' from patient p, users u where u.password is not null");
		List<LogEvent> warnings = warningsWhileFinding();
		
		assertTrue(!warnings.isEmpty());
		assertNoPasswordIn(warnings);
	}
	
	@Test
	public void keepsAQuestionCellThatIsNotAConceptUuidOutOfTheLog() {
		configure("select e.patient_id, e.uuid, u.password from encounter e, users u where e.encounter_id = 3"
		        + " and u.password is not null");
		List<LogEvent> warnings = warningsWhileFinding();
		
		assertTrue(!warnings.isEmpty());
		assertNoPasswordIn(warnings);
	}
	
	@Test
	public void warnsOfAQuestionCellThatIsNotAConceptUuid() {
		configure("select e.patient_id, e.uuid, 5089 from encounter e where e.encounter_id = 3");
		List<LogEvent> warnings = warningsWhileFinding();
		
		assertEquals(new ArrayList<FlagGap>(), found);
		assertEquals(1, warnings.size());
		String warning = warnings.get(0).getMessage().getFormattedMessage();
		assertTrue(warning, warning.contains("not a concept uuid"));
		assertTrue(warning, warning.contains("column 3"));
		assertTrue(warning, warning.contains("Integer"));
	}
	
	@Test
	public void doesNotWarnOfAnEncounterItFiltersOut() {
		configure("select p.patient_id, e.uuid, '" + WEIGHT + "' from patient p join encounter e"
		        + " on e.encounter_id in (3, 5, 6) where p.patient_id > 0");
		Encounter five = Context.getEncounterService().getEncounterByUuid(ENCOUNTER_5);
		Context.getEncounterService().voidEncounter(five, "test");
		List<LogEvent> warnings = warningsWhileFinding();
		
		assertEquals(ENCOUNTER_3, onlyEncounter(found));
		assertTrue(warnings.isEmpty());
	}
	
	@Test
	public void skipsARowWithoutAnEncounterOrAQuestion() {
		configure("select e.patient_id, case when e.encounter_id = 4 then null else e.uuid end,"
		        + " case when e.encounter_id = 5 then null else '" + WEIGHT + "' end from encounter e"
		        + " where e.encounter_id in (3, 4, 5)");
		
		assertEquals(ENCOUNTER_3, onlyEncounter(lookup.find(patient(), flag)));
	}
	
	@Test
	public void findsTheFlagByUuid() {
		assertEquals(flag.getFlagId(), lookup.getFlag(flag.getUuid()).getFlagId());
		assertNull(lookup.getFlag(UUID.randomUUID().toString()));
	}
	
	@Test
	public void aUserWhoCanViewPatientFlagsGetsTheGaps() {
		configure("select e.patient_id, e.uuid, '" + WEIGHT + "' from encounter e where e.encounter_id = 3");
		Patient patient = patient();
		
		authenticateWith("View Patient Flags", "Get Patients", "Get Encounters", "Get Concepts");
		
		assertEquals(flag.getFlagId(), lookup.getFlag(flag.getUuid()).getFlagId());
		assertEquals(ENCOUNTER_3, onlyEncounter(lookup.find(patient, flag)));
	}
	
	@Test
	public void leavesOutAnEncounterTheUserMayNotView() {
		configure("select e.patient_id, e.uuid, '" + WEIGHT + "' from encounter e where e.encounter_id in (3, 5)");
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
		configure("select e.patient_id, e.uuid, '" + WEIGHT + "' from encounter e");
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
	
	private String outcomeAgreedWithPatientflags(String criteria) {
		configure(criteria);
		String evaluated;
		try {
			evaluated = String.valueOf(new SQLFlagEvaluator().eval(flag, patient(), null));
		}
		catch (Exception e) {
			evaluated = "fails";
		}
		String looked;
		try {
			List<FlagGap> gaps = lookup.find(patient(), flag);
			looked = String.valueOf(gaps != null && !gaps.isEmpty());
		}
		catch (Exception e) {
			looked = "fails";
		}
		assertEquals(criteria, evaluated, looked);
		return looked;
	}
	
	private Patient patient() {
		return Context.getPatientService().getPatient(PATIENT);
	}
	
	private void configure(String criteria) {
		flag.setCriteria(criteria);
		Context.getService(FlagService.class).saveFlag(flag);
	}
	
	private List<LogEvent> warningsWhileFinding() {
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
			found = lookup.find(patient(), flag);
		}
		finally {
			logger.removeAppender(appender);
			logger.setLevel(level);
		}
		return events;
	}
	
	private String onlyEncounter(List<FlagGap> gaps) {
		List<String> uuids = encounterUuids(gaps);
		assertEquals(1, uuids.size());
		return uuids.get(0);
	}
	
	private List<String> encounterUuids(List<FlagGap> gaps) {
		List<String> uuids = new ArrayList<String>();
		for (FlagGap gap : gaps) {
			uuids.add(gap.getEncounter().getUuid());
		}
		return uuids;
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
