/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.rhdflags.web;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.Module;
import org.openmrs.module.ModuleFactory;
import org.openmrs.module.ModuleFileParser;
import org.openmrs.util.CycleException;

public class ModuleStartupOrderTest {
	
	private static final String INITIALIZER_PACKAGE = "org.openmrs.module.initializer";
	
	private static final String[] INITIALIZER_AWARE_OF = { "org.openmrs.module.patientflags", "org.openmrs.module.cohort",
	        "org.openmrs.module.emrapi", "org.openmrs.module.fhir2", "org.openmrs.module.idgen",
	        "org.openmrs.module.metadatamapping", "org.openmrs.module.addresshierarchy", "org.openmrs.module.legacyui",
	        "org.openmrs.module.openconceptlab", "org.openmrs.module.queue", "org.openmrs.module.billing",
	        "org.openmrs.module.tasks", "org.bahmni.module.appointments" };
	
	private Module rhdflags;
	
	private Module initializer;
	
	private final List<Module> loaded = new ArrayList<Module>();
	
	// Restored after each test, since ModuleFactory's map is static.
	private final Map<String, Module> replaced = new HashMap<String, Module>();
	
	@Before
	public void parseTheBuiltModuleConfig() throws Exception {
		File omod = File.createTempFile("rhdflags", ".omod");
		omod.deleteOnExit();
		try (JarOutputStream jar = new JarOutputStream(new FileOutputStream(omod))) {
			jar.putNextEntry(new ZipEntry("config.xml"));
			jar.write(Files.readAllBytes(new File("target/classes/config.xml").toPath()));
			jar.closeEntry();
		}
		rhdflags = new ModuleFileParser(Context.getMessageSourceService()).parse(omod);
		// Without these the order test can pass without rhdflags' aware_of_module, by iteration order.
		initializer = standIn(INITIALIZER_PACKAGE, INITIALIZER_AWARE_OF);
		load(rhdflags);
		load(initializer);
		for (String other : INITIALIZER_AWARE_OF) {
			load(standIn(other));
		}
	}
	
	@After
	public void unloadThem() {
		Map<String, Module> map = ModuleFactory.getLoadedModulesMap();
		for (Module module : loaded) {
			map.remove(module.getModuleId());
		}
		map.putAll(replaced);
	}
	
	private static Module standIn(String packageName, String... awareOf) {
		Module module = new Module(packageName, packageName.substring(packageName.lastIndexOf('.') + 1), packageName, "", "",
		        "1.0.0");
		module.setRequiredModulesMap(new HashMap<String, String>());
		Map<String, String> aware = new HashMap<String, String>();
		for (String each : awareOf) {
			aware.put(each, null);
		}
		module.setAwareOfModulesMap(aware);
		return module;
	}
	
	private void load(Module module) {
		Module previous = ModuleFactory.getLoadedModulesMap().put(module.getModuleId(), module);
		if (previous != null) {
			// Keyed by the module's own id string, since the map is weakly keyed.
			replaced.put(previous.getModuleId(), previous);
		}
		loaded.add(module);
	}
	
	@Test
	public void startsAfterInitializer() throws Exception {
		List<Module> order = ModuleFactory.getModulesInStartupOrder(loaded);
		
		assertTrue("startup order " + order, order.indexOf(initializer) < order.indexOf(rhdflags));
	}
	
	/**
	 * With Initializer stopped, rhdflags' edge from it stays in core's sort, and core then starts only
	 * the partial order, which leaves rhdflags out whatever order the sort iterates in.
	 */
	@Test
	public void isLeftOutWhenInitializerIsStopped() {
		List<Module> shouldStart = new ArrayList<Module>(loaded);
		shouldStart.remove(initializer);
		try {
			ModuleFactory.getModulesInStartupOrder(shouldStart);
			fail("sorted without an edge from Initializer");
		}
		catch (CycleException e) {
			assertFalse(((List<?>) e.getExtraData()).contains(rhdflags));
		}
	}
	
	@Test
	public void doesNotRequireInitializer() {
		assertFalse(rhdflags.getRequiredModules().contains(INITIALIZER_PACKAGE));
	}
}
