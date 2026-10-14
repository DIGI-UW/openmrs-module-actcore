/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.Before;
import org.junit.Test;
import org.openmrs.Concept;
import org.openmrs.ConceptName;
import org.openmrs.GlobalProperty;
import org.openmrs.Person;
import org.openmrs.PersonName;
import org.openmrs.Privilege;
import org.openmrs.Program;
import org.openmrs.ProgramWorkflow;
import org.openmrs.ProgramWorkflowState;
import org.openmrs.Role;
import org.openmrs.User;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.UserService;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.studies.Studies;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.util.PrivilegeConstants;
import org.openmrs.web.test.BaseModuleWebContextSensitiveTest;
import org.springframework.beans.factory.annotation.Autowired;

public class StudiesControllerTest extends BaseModuleWebContextSensitiveTest {
	
	@Autowired
	private StudiesController controller;
	
	@Before
	public void setUp() {
		Program template = new Program();
		template.setName("Research Participation");
		template.setDescription("Template");
		template.setConcept(concept("Research Participation Program"));
		ProgramWorkflow workflow = new ProgramWorkflow();
		workflow.setConcept(concept("Research Study Status"));
		ProgramWorkflowState active = new ProgramWorkflowState();
		active.setConcept(concept("Active (study status)"));
		active.setInitial(true);
		active.setTerminal(false);
		workflow.addState(active);
		template.addWorkflow(workflow);
		template.setRetired(true);
		Context.getProgramWorkflowService().saveProgram(template);
		Context.getAdministrationService()
		        .saveGlobalProperty(new GlobalProperty(Studies.TEMPLATE_PROPERTY, template.getUuid()));
	}
	
	@Test
	public void createsAndRenamesAStudyForAUserWithOnlyThePrivileges() {
		authenticateWith(StudiesController.MANAGE_PRIVILEGE, PrivilegeConstants.GET_PROGRAMS);
		
		SimpleObject created = controller.create(Collections.singletonMap("name", "ARC"));
		controller.rename((String) created.get("uuid"), Collections.singletonMap("name", "ARC 2"));
		
		List<?> results = (List<?>) controller.getStudies().get("results");
		assertEquals(1, results.size());
		SimpleObject study = (SimpleObject) results.get(0);
		assertEquals("ARC 2", study.get("name"));
		SimpleObject state = (SimpleObject) ((List<?>) study.get("states")).get(0);
		assertEquals("Active (study status)", state.get("name"));
		assertEquals(Boolean.TRUE, state.get("initial"));
	}
	
	@Test
	public void refusesToChangeStudiesWithoutTheManagePrivilege() {
		authenticateWith(PrivilegeConstants.GET_PROGRAMS);
		
		assertEquals(0, ((List<?>) controller.getStudies().get("results")).size());
		try {
			controller.create(Collections.singletonMap("name", "ARC"));
			fail("expected the study to be refused");
		}
		catch (APIAuthenticationException e) {
			assertTrue(e.getMessage().contains(StudiesController.MANAGE_PRIVILEGE));
		}
	}
	
	private static Concept concept(String name) {
		Concept concept = new Concept();
		concept.setFullySpecifiedName(new ConceptName(name, Context.getLocale()));
		concept.setConceptClass(Context.getConceptService().getConceptClass(1));
		concept.setDatatype(Context.getConceptService().getConceptDatatypeByName("N/A"));
		return Context.getConceptService().saveConcept(concept);
	}
	
	private void authenticateWith(String... privileges) {
		UserService users = Context.getUserService();
		Role role = new Role("studies caller " + UUID.randomUUID());
		for (String name : privileges) {
			Privilege privilege = users.getPrivilege(name);
			role.addPrivilege(privilege != null ? privilege : users.savePrivilege(new Privilege(name)));
		}
		users.saveRole(role);
		Person person = new Person();
		person.addName(new PersonName("Studies", null, "Caller"));
		person.setGender("F");
		User user = new User(person);
		user.setUsername("studiescaller");
		user.addRole(role);
		users.createUser(user, "Studies123");
		Context.logout();
		Context.authenticate("studiescaller", "Studies123");
	}
}
