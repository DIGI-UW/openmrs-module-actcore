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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;

import org.junit.Test;
import org.openmrs.Privilege;
import org.openmrs.api.context.Context;
import org.openmrs.module.Module;
import org.openmrs.module.ModuleFactory;
import org.openmrs.module.ModuleFileParser;
import org.openmrs.module.actcore.ActCorePrivileges;
import org.openmrs.web.test.BaseModuleWebContextSensitiveTest;

public class ModulePrivilegesTest extends BaseModuleWebContextSensitiveTest {
	
	private static final Pattern NAME = Pattern.compile("\"((?:Task|App): act\\.[A-Za-z.]+)\"");
	
	@Test
	public void declaresEachPrivilegeItNamesWithADescriptionCoreAccepts() throws Exception {
		Module module = builtModule();
		Set<String> declared = new TreeSet<String>();
		for (Privilege privilege : module.getPrivileges()) {
			declared.add(privilege.getPrivilege());
			// Longer is refused by the privilege validator, and core's start loop only logs that.
			assertTrue(privilege.getPrivilege(), privilege.getDescription().length() <= 250);
		}
		
		assertEquals(named(), declared);
	}
	
	@Test
	public void createsEachPrivilegeWhenTheModuleStarts() throws Exception {
		Module module = builtModule();
		ModuleFactory.getStartedModulesMap().put(module.getModuleId(), module);
		try {
			Context.checkCoreDataset();
		}
		finally {
			ModuleFactory.getStartedModulesMap().remove(module.getModuleId());
		}
		
		for (String name : named()) {
			assertNotNull(name, Context.getUserService().getPrivilege(name));
		}
	}
	
	@Test
	public void spellsEachPrivilegeNameOnlyInActCorePrivileges() throws IOException {
		List<String> read = new ArrayList<String>();
		List<String> elsewhere = new ArrayList<String>();
		for (Path root : new Path[] { new File("../api/src/main/java").toPath(), new File("src/main/java").toPath() }) {
			try (Stream<Path> files = Files.walk(root)) {
				for (Path file : files.filter(f -> f.toString().endsWith(".java")).collect(Collectors.toList())) {
					read.add(file.getFileName().toString());
					if (file.getFileName().toString().equals("ActCorePrivileges.java")) {
						continue;
					}
					Matcher name = NAME.matcher(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
					while (name.find()) {
						elsewhere.add(file.getFileName() + ": " + name.group(1));
					}
				}
			}
		}
		
		// Both trees, or a walk of the wrong root would pass by reading nothing.
		assertTrue(read.contains("ActCorePrivileges.java"));
		assertTrue(read.contains("DownloadController.java"));
		assertEquals(new ArrayList<String>(), elsewhere);
	}
	
	private static Set<String> named() throws IllegalAccessException {
		Set<String> named = new TreeSet<String>();
		for (Field field : ActCorePrivileges.class.getFields()) {
			if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
				named.add((String) field.get(null));
			}
		}
		assertTrue(named.size() > 0);
		return named;
	}
	
	private static Module builtModule() throws IOException {
		File omod = File.createTempFile("actcore", ".omod");
		omod.deleteOnExit();
		try (JarOutputStream jar = new JarOutputStream(new FileOutputStream(omod))) {
			jar.putNextEntry(new ZipEntry("config.xml"));
			jar.write(Files.readAllBytes(new File("target/classes/config.xml").toPath()));
			jar.closeEntry();
		}
		return new ModuleFileParser(Context.getMessageSourceService()).parse(omod);
	}
}
