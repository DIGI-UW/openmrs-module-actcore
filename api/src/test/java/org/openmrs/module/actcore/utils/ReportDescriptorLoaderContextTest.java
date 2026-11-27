/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

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

/**
 * The property decides whether descriptors load; when it is true, those in the configuration are
 * saved.
 */
public class ReportDescriptorLoaderContextTest extends BaseModuleContextSensitiveTest {
	
	@Rule
	public TemporaryFolder dataDirectory = new TemporaryFolder();
	
	private void loadAtStartup(String value) {
		Context.getAdministrationService().saveGlobalProperty(
		    new GlobalProperty(ReportingConstants.GLOBAL_PROPERTY_LOAD_REPORTS_FROM_CONFIGURATION_AT_STARTUP, value));
	}
	
	private void writeDescriptor() throws Exception {
		ReportDescriptors.write(dataDirectory.getRoot());
	}
	
	private ReportDefinition savedReport() {
		return Context.getService(ReportDefinitionService.class).getDefinitionByUuid(ReportDescriptors.UUID);
	}
	
	@Test
	public void loadsTheConfiguredDescriptorsWhenThePropertyIsTrue() throws Exception {
		writeDescriptor();
		loadAtStartup("true");
		
		assertEquals(ReportDescriptorLoader.Outcome.LOADED, ReportDescriptorLoader.load());
		
		assertNotNull(savedReport());
		assertEquals(ReportDescriptors.NAME, savedReport().getName());
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
