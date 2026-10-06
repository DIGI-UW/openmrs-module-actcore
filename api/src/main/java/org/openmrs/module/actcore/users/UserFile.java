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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.openmrs.Person;
import org.openmrs.PersonName;
import org.openmrs.Provider;
import org.openmrs.Role;
import org.openmrs.User;
import org.openmrs.api.UserService;
import org.openmrs.api.context.Context;
import org.openmrs.util.OpenmrsConstants;
import org.openmrs.util.OpenmrsUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates the users listed in a CSV file that each server keeps in its application data directory,
 * each with a provider; Initializer creates no users. An existing username is left as it is.
 */
public final class UserFile {
	
	/** Set it from the environment as OMRS_EXTRA_ACTCORE_USERS_PASSWORD in the OpenMRS image. */
	public static final String DEFAULT_PASSWORD_PROPERTY = "actcore.users.password";
	
	private static final String[] COLUMNS = { "username", "given name", "family name", "gender", "roles", "password" };
	
	private static final SecureRandom RANDOM = new SecureRandom();
	
	private static final Logger log = LoggerFactory.getLogger(UserFile.class);
	
	private UserFile() {
	}
	
	/** The file the module loads at startup: actcore/users.csv in the application data directory. */
	public static File defaultFile() {
		return new File(new File(OpenmrsUtil.getApplicationDataDirectory(), "actcore"), "users.csv");
	}
	
	/**
	 * Creates each listed user who does not exist yet; a bad row is logged and skipped, so the module
	 * still starts.
	 */
	public static void load(File file, String defaultPassword) {
		if (!file.isFile()) {
			return;
		}
		List<String> lines;
		try {
			lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			log.error("Could not read the users file {}", file, e);
			return;
		}
		if (lines.isEmpty()) {
			return;
		}
		// Excel saves CSV UTF-8 with a byte order mark before the first header.
		int[] column = columns(split(StringUtils.removeStart(lines.get(0), "\uFEFF")), file);
		if (column == null) {
			return;
		}
		int created = 0;
		int skipped = 0;
		for (int i = 1; i < lines.size(); i++) {
			if (StringUtils.isBlank(lines.get(i))) {
				continue;
			}
			List<String> row = split(lines.get(i));
			String username = cell(row, column[0]);
			try {
				if (create(row, column, defaultPassword)) {
					created++;
				}
			}
			catch (RuntimeException e) {
				skipped++;
				log.error("Users file {}, line {}: could not create {}: {}", file, i + 1, username, e.getMessage());
			}
		}
		log.info("Users file {}: {} created, {} skipped", file, created, skipped);
	}
	
	private static boolean create(List<String> row, int[] column, String defaultPassword) {
		UserService users = Context.getUserService();
		String username = cell(row, column[0]);
		if (StringUtils.isBlank(username)) {
			throw new IllegalArgumentException("no username");
		}
		if (users.getUserByUsername(username) != null) {
			return false;
		}
		List<Role> roles = new ArrayList<Role>();
		for (String name : StringUtils.split(cell(row, column[4]), '|')) {
			Role role = users.getRole(name.trim());
			if (role == null) {
				throw new IllegalArgumentException("no role " + name.trim());
			}
			roles.add(role);
		}
		if (roles.isEmpty()) {
			throw new IllegalArgumentException("no roles");
		}
		String password = StringUtils.defaultIfBlank(cell(row, column[5]), defaultPassword);
		boolean mustChange = StringUtils.isBlank(password);
		if (mustChange) {
			password = randomPassword();
		}
		// Before the person is saved, so a password the policy refuses leaves nothing behind.
		OpenmrsUtil.validatePassword(username, password, null);
		
		Person person = new Person();
		person.setGender(StringUtils.trimToNull(cell(row, column[3])));
		person.addName(new PersonName(cell(row, column[1]), null, cell(row, column[2])));
		Context.getPersonService().savePerson(person);
		
		User user = new User(person);
		user.setUsername(username);
		for (Role role : roles) {
			user.addRole(role);
		}
		if (mustChange) {
			user.setUserProperty(OpenmrsConstants.USER_PROPERTY_CHANGE_PASSWORD, "true");
		}
		users.createUser(user, password);
		
		Provider provider = new Provider();
		provider.setPerson(person);
		provider.setIdentifier(username);
		Context.getProviderService().saveProvider(provider);
		return true;
	}
	
	/**
	 * Each column's index in the header, -1 for the optional password; null if a required one is
	 * missing.
	 */
	private static int[] columns(List<String> header, File file) {
		int[] index = new int[COLUMNS.length];
		for (int c = 0; c < COLUMNS.length; c++) {
			index[c] = -1;
			for (int h = 0; h < header.size(); h++) {
				if (COLUMNS[c].equals(header.get(h).trim().toLowerCase(Locale.ROOT))) {
					index[c] = h;
				}
			}
			if (index[c] < 0 && !"password".equals(COLUMNS[c])) {
				log.error("The users file {} has no '{}' column, so it was not loaded", file, COLUMNS[c]);
				return null;
			}
		}
		return index;
	}
	
	private static String cell(List<String> row, int index) {
		return index >= 0 && index < row.size() ? row.get(index).trim() : "";
	}
	
	/** One CSV line's fields; a quoted field may hold commas, and "" inside it is a quote. */
	static List<String> split(String line) {
		List<String> fields = new ArrayList<String>();
		StringBuilder field = new StringBuilder();
		boolean quoted = false;
		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);
			if (quoted && c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
				field.append('"');
				i++;
			} else if (c == '"') {
				quoted = !quoted;
			} else if (c == ',' && !quoted) {
				fields.add(field.toString());
				field.setLength(0);
			} else {
				field.append(c);
			}
		}
		fields.add(field.toString());
		return fields;
	}
	
	/** Meets the default password policy: long, mixed case, and with digits. */
	private static String randomPassword() {
		return "Aa1" + Long.toString(RANDOM.nextLong() & Long.MAX_VALUE, 36)
		        + Long.toString(RANDOM.nextLong() & Long.MAX_VALUE, 36);
	}
}
