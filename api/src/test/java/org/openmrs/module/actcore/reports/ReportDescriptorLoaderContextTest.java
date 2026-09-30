/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.reports;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.File;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.openmrs.GlobalProperty;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.ActCoreActivator;
import org.openmrs.module.reporting.ReportingConstants;
import org.openmrs.module.reporting.report.definition.ReportDefinition;
import org.openmrs.module.reporting.report.definition.service.ReportDefinitionService;
import org.openmrs.test.BaseModuleContextSensitiveTest;
import org.openmrs.util.OpenmrsUtil;

/**
 * The property decides whether descriptors load; when it is true, those in the configuration are
 * saved.
 */
public class ReportDescriptorLoaderContextTest extends BaseModuleContextSensitiveTest {
	
	private static final String REPORT_UUID = "5b0c7a1e-3f4d-4a6b-9c8d-2e1f0a9b8c7d";
	
	@Rule
	public TemporaryFolder dataDirectory = new TemporaryFolder();
	
	private void loadAtStartup(String value) {
		Context.getAdministrationService().saveGlobalProperty(
		    new GlobalProperty(ReportingConstants.GLOBAL_PROPERTY_LOAD_REPORTS_FROM_CONFIGURATION_AT_STARTUP, value));
	}
	
	/** One descriptor, as the distribution's configuration/reports/reportdescriptors holds them. */
	private void writeDescriptor() throws Exception {
		OpenmrsUtil.setApplicationDataDirectory(dataDirectory.getRoot().getAbsolutePath());
		File dir = new File(dataDirectory.getRoot(), "configuration/reports/reportdescriptors/test");
		new File(dir, "sql").mkdirs();
		Files.write(new File(dir, "sql/patients.sql").toPath(), "select patient_id from patient".getBytes("UTF-8"));
		Files.write(new File(dir, "patients.yml").toPath(),
		    ("key: actcoreTestReport\n" + "uuid: \"" + REPORT_UUID + "\"\n" + "name: \"ACT Core test report\"\n"
		            + "datasets:\n" + "  - key: patients\n" + "    type: sql\n" + "    config: \"sql/patients.sql\"\n")
		            .getBytes("UTF-8"));
	}
	
	private ReportDefinition savedReport() {
		return Context.getService(ReportDefinitionService.class).getDefinitionByUuid(REPORT_UUID);
	}
	
	@Test
	public void loadsTheConfiguredDescriptorsWhenThePropertyIsTrue() throws Exception {
		writeDescriptor();
		loadAtStartup("true");
		
		assertEquals(ReportDescriptorLoader.Outcome.LOADED, ReportDescriptorLoader.load());
		
		assertNotNull(savedReport());
		assertEquals("ACT Core test report", savedReport().getName());
	}
	
	@Test
	public void skipsWhenThePropertyIsFalse() throws Exception {
		writeDescriptor();
		loadAtStartup("false");
		
		assertEquals(ReportDescriptorLoader.Outcome.SKIPPED, ReportDescriptorLoader.load());
		
		assertNull(savedReport());
	}
	
	@Test
	public void skipsWhenThePropertyIsUnset() throws Exception {
		writeDescriptor();
		
		assertEquals(ReportDescriptorLoader.Outcome.SKIPPED, ReportDescriptorLoader.load());
		
		assertNull(savedReport());
	}
	
	@Test
	public void theModuleLoadsThemAsItStarts() throws Exception {
		writeDescriptor();
		loadAtStartup("true");
		
		new ActCoreActivator().started();
		
		assertNotNull(savedReport());
		assertNotNull(Context.getSchedulerService().getTaskByName(ActCoreActivator.REFRESH_TASK_NAME));
		assertNotNull(Context.getSchedulerService().getTaskByName(ActCoreActivator.ADHERENCE_TASK_NAME));
	}
}
