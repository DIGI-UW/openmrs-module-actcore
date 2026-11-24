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

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.Test;
import org.openmrs.module.actcore.ActCoreConstants;
import org.openmrs.module.actcore.ActCorePrivileges;

public class ActCoreConstantsTest {
	
	private static final Pattern PROPERTY = Pattern.compile("<globalProperty>\\s*<property>([^<]+)</property>",
	    Pattern.MULTILINE);
	
	// Comments and character literals are matched too, so a quote inside one does not open a string.
	private static final Pattern TOKEN = Pattern
	        .compile("//[^\\n]*|/\\*.*?\\*/|'(?:[^'\\\\]|\\\\.)*'|\"(?:[^\"\\\\]|\\\\.)*\"", Pattern.DOTALL);
	
	private static final Pattern NAME = Pattern.compile("\\bactcore[._][A-Za-z._]+\\b|View Audit Logs|View Read Audit Logs"
	        + "|View Cohorts In Cohort Module|Edit Cohorts in Cohort Module");
	
	private static final Pattern ALIAS = Pattern.compile("static final String (\\w+) = "
	        + "(?:(?:org\\.openmrs\\.module\\.actcore\\.)?(ActCorePrivileges|ActCoreConstants)\\.)?(\\w+);");
	
	@Test
	public void declaresEachGlobalPropertyOfConfigXmlOnce() throws Exception {
		Set<String> declared = new TreeSet<String>();
		Matcher property = PROPERTY.matcher(
		    new String(Files.readAllBytes(new File("target/classes/config.xml").toPath()), StandardCharsets.UTF_8));
		while (property.find()) {
			declared.add(property.group(1));
		}
		
		Map<String, String> keys = new LinkedHashMap<String, String>();
		for (Field field : ActCoreConstants.class.getFields()) {
			if (Modifier.isStatic(field.getModifiers()) && field.getName().startsWith("GP_")) {
				String previous = keys.put((String) field.get(null), field.getName());
				assertEquals(field.getName() + " repeats " + previous, null, previous);
			}
		}
		
		assertTrue(declared.size() > 0);
		assertEquals(declared, new TreeSet<String>(keys.keySet()));
	}
	
	/**
	 * Reads each string literal of the main sources for an ACT Core name or one of the upstream
	 * privilege names, whether the literal is the name alone or SQL or text around it. It skips the
	 * literal passed straight to reject whatever it holds, since ACT Core's Errors codes are shared
	 * with no other class or the database. It does not see a name split across concatenated literals or
	 * one read from a resource file.
	 */
	@Test
	public void spellsEachNameOnlyInActCoreConstants() throws IOException {
		List<String> read = new ArrayList<String>();
		List<String> elsewhere = new ArrayList<String>();
		for (Path file : mainSources()) {
			read.add(file.getFileName().toString());
			if (file.getFileName().toString().equals("ActCoreConstants.java")) {
				continue;
			}
			String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
			Matcher token = TOKEN.matcher(source);
			while (token.find()) {
				if (!token.group().startsWith("\"") || source.substring(0, token.start()).endsWith("reject(")) {
					continue;
				}
				Matcher name = NAME.matcher(token.group());
				while (name.find()) {
					elsewhere.add(file.getFileName() + ": " + name.group());
				}
			}
		}
		
		// Both trees, or a walk of the wrong root would pass by reading nothing.
		assertTrue(read.contains("ActCoreConstants.java"));
		assertTrue(read.contains("FormsForPatientSearchHandler.java"));
		assertEquals(new ArrayList<String>(), elsewhere);
	}
	
	/**
	 * Finds a String constant set to a field of ActCoreConstants or ActCorePrivileges, named through
	 * its class, its fully qualified class, or a static import of either class. It does not see an
	 * alias split across lines or one assigned outside its declaration.
	 */
	@Test
	public void keepsNoConstantThatOnlyRenamesAnother() throws IOException {
		Set<String> shared = new TreeSet<String>();
		for (Class<?> type : new Class<?>[] { ActCoreConstants.class, ActCorePrivileges.class }) {
			for (Field field : type.getFields()) {
				shared.add(field.getName());
			}
		}
		
		List<String> aliases = new ArrayList<String>();
		for (Path file : mainSources()) {
			String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
			boolean imported = source.contains("import static org.openmrs.module.actcore.ActCore");
			Matcher alias = ALIAS.matcher(source);
			while (alias.find()) {
				if (alias.group(2) != null || imported && shared.contains(alias.group(3))) {
					aliases.add(file.getFileName() + ": " + alias.group(1));
				}
			}
		}
		
		assertEquals(new ArrayList<String>(), aliases);
	}
	
	private static List<Path> mainSources() throws IOException {
		List<Path> sources = new ArrayList<Path>();
		for (Path root : new Path[] { new File("../api/src/main/java").toPath(), new File("src/main/java").toPath() }) {
			try (Stream<Path> files = Files.walk(root)) {
				sources.addAll(files.filter(f -> f.toString().endsWith(".java")).collect(Collectors.toList()));
			}
		}
		return sources;
	}
}
