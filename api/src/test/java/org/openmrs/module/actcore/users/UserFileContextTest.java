/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.users;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.openmrs.Role;
import org.openmrs.User;
import org.openmrs.api.UserService;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.UsernamePasswordCredentials;
import org.openmrs.module.actcore.ActCoreActivator;
import org.openmrs.test.BaseModuleContextSensitiveTest;
import org.openmrs.util.OpenmrsConstants;
import org.openmrs.util.OpenmrsUtil;

public class UserFileContextTest extends BaseModuleContextSensitiveTest {
	
	private static final String HEADER = "username,given name,family name,gender,roles,password";
	
	@Rule
	public TemporaryFolder folder = new TemporaryFolder();
	
	private UserService users;
	
	@Before
	public void setUp() {
		users = Context.getUserService();
		users.saveRole(new Role("Organizational: ACT Clinician", "test"));
		users.saveRole(new Role("Application: ACT Exports", "test"));
	}
	
	@Test
	public void load_shouldDoNothingWithoutAFile() {
		UserFile.load(new File(folder.getRoot(), "missing.csv"), null);
	}
	
	@Test
	public void load_shouldCreateEachUserWithTheirRolesAndAProvider() throws IOException {
		UserFile.load(
		    file(HEADER, "act.clinician,Amina,Okello,F,Organizational: ACT Clinician|Application: ACT Exports,Clinic1234"),
		    null);
		
		User user = users.getUserByUsername("act.clinician");
		assertNotNull(user);
		assertEquals("Amina", user.getPersonName().getGivenName());
		assertEquals("Okello", user.getPersonName().getFamilyName());
		assertEquals("F", user.getPerson().getGender());
		assertEquals(2, user.getRoles().size());
		assertTrue(user.hasRole("Application: ACT Exports"));
		assertEquals(1, Context.getProviderService().getProvidersByPerson(user.getPerson()).size());
		signIn("act.clinician", "Clinic1234");
	}
	
	@Test
	public void load_shouldGiveARowWithoutAPasswordTheDefaultPassword() throws IOException {
		UserFile.load(file(HEADER, "act.clerk,Ruth,Apio,F,Organizational: ACT Clinician,"), "Default123");
		
		signIn("act.clerk", "Default123");
	}
	
	@Test
	public void load_shouldReadAFileWithoutAPasswordColumn() throws IOException {
		UserFile.load(
		    file("username,given name,family name,gender,roles", "act.clerk,Ruth,Apio,F,Organizational: ACT Clinician"),
		    "Default123");
		
		signIn("act.clerk", "Default123");
	}
	
	@Test
	public void load_shouldMakeAUserWithNoPasswordAnywhereChangeItAtNextSignIn() throws IOException {
		UserFile.load(file(HEADER, "act.clerk,Ruth,Apio,F,Organizational: ACT Clinician,"), null);
		
		User user = users.getUserByUsername("act.clerk");
		assertNotNull(user);
		assertEquals("true", user.getUserProperty(OpenmrsConstants.USER_PROPERTY_CHANGE_PASSWORD));
	}
	
	@Test
	public void load_shouldLeaveAnExistingUserAsTheAdministratorLeftIt() throws IOException {
		File file = file(HEADER, "act.clinician,Amina,Okello,F,Organizational: ACT Clinician,Clinic1234");
		UserFile.load(file, null);
		User user = users.getUserByUsername("act.clinician");
		user.getRoles().clear();
		users.saveUser(user);
		
		UserFile.load(file(HEADER, "act.clinician,Amina,Okello,F,Organizational: ACT Clinician,Another123"), null);
		
		User again = users.getUserByUsername("act.clinician");
		assertTrue(again.getRoles().isEmpty());
		assertEquals(1, Context.getProviderService().getProvidersByPerson(again.getPerson()).size());
		signIn("act.clinician", "Clinic1234");
	}
	
	@Test
	public void load_shouldSkipARowNamingARoleThatDoesNotExistAndLoadTheRest() throws IOException {
		UserFile.load(file(HEADER, "act.nobody,No,Role,M,Organizational: No Such Role,Clinic1234",
		    "act.clinician,Amina,Okello,F,Organizational: ACT Clinician,Clinic1234"), null);
		
		assertNull(users.getUserByUsername("act.nobody"));
		assertNotNull(users.getUserByUsername("act.clinician"));
	}
	
	@Test
	public void load_shouldLeaveNoPersonBehindForAPasswordThePolicyRefuses() throws IOException {
		long people = people();
		
		UserFile.load(file(HEADER, "act.clinician,Amina,Okello,F,Organizational: ACT Clinician,short"), null);
		
		assertNull(users.getUserByUsername("act.clinician"));
		assertEquals(people, people());
	}
	
	@Test
	public void load_shouldReadQuotedFields() throws IOException {
		UserFile.load(file(HEADER, "act.clinician,Amina,\"Okello, \"\"Jr\"\"\",F,Organizational: ACT Clinician,Clinic1234"),
		    null);
		
		assertEquals("Okello, \"Jr\"", users.getUserByUsername("act.clinician").getPersonName().getFamilyName());
	}
	
	@Test
	public void load_shouldReadAFileExcelSavedWithAByteOrderMark() throws IOException {
		UserFile.load(file("\uFEFF" + HEADER, "act.clinician,Amina,Okello,F,Organizational: ACT Clinician,Clinic1234"),
		    null);
		
		assertNotNull(users.getUserByUsername("act.clinician"));
	}
	
	@Test
	public void started_shouldLoadTheFileInTheApplicationDataDirectory() throws IOException {
		String original = OpenmrsUtil.getApplicationDataDirectory();
		File actcore = folder.newFolder("actcore");
		Files.write(new File(actcore, "users.csv").toPath(),
		    Arrays.asList(HEADER, "act.clinician,Amina,Okello,F,Organizational: ACT Clinician,Clinic1234"),
		    StandardCharsets.UTF_8);
		OpenmrsUtil.setApplicationDataDirectory(folder.getRoot().getAbsolutePath());
		try {
			new ActCoreActivator().started();
		}
		finally {
			OpenmrsUtil.setApplicationDataDirectory(original);
		}
		
		assertNotNull(users.getUserByUsername("act.clinician"));
	}
	
	private File file(String... lines) throws IOException {
		File file = folder.newFile();
		Files.write(file.toPath(), Arrays.asList(lines), StandardCharsets.UTF_8);
		return file;
	}
	
	private void signIn(String username, String password) {
		Context.logout();
		Context.authenticate(new UsernamePasswordCredentials(username, password));
		assertEquals(username, Context.getAuthenticatedUser().getUsername());
	}
	
	private static long people() {
		List<List<Object>> rows = Context.getAdministrationService().executeSQL("select count(*) from person", true);
		return ((Number) rows.get(0).get(0)).longValue();
	}
}
