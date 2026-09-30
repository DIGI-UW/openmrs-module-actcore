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

import org.openmrs.module.reporting.ReportingConstants;
import org.openmrs.module.reporting.config.ReportLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads the report descriptors that the reporting module skips at its own startup.
 * <p>
 * The reporting module declares reporting.loadReportsFromConfigurationAtStartup with a default of
 * false and reads it while starting, which is before Initializer applies the distribution's value,
 * so on a fresh database the descriptors are never loaded. This module requires reporting and is
 * aware of Initializer, so it starts after both, when the property and the configuration files are
 * in place. Remove this once the reporting module loads them itself
 * (mherman22/openmrs-module-reporting#1).
 */
public class ReportDescriptorLoader {
	
	public enum Outcome {
		LOADED,
		SKIPPED,
		FAILED
	}
	
	private static final Logger log = LoggerFactory.getLogger(ReportDescriptorLoader.class);
	
	private ReportDescriptorLoader() {
	}
	
	/**
	 * Loads the descriptors unless the property says not to. A failure is logged, so the module still
	 * starts.
	 */
	public static Outcome load() {
		try {
			if (!ReportingConstants.GLOBAL_PROPERTY_LOAD_REPORTS_FROM_CONFIGURATION_AT_STARTUP()) {
				log.info("Skipping report descriptors: reporting.loadReportsFromConfigurationAtStartup is false");
				return Outcome.SKIPPED;
			}
			log.info("Loading report descriptors from {}", ReportLoader.getReportingDescriptorsConfigurationDir());
			ReportLoader.loadReportsFromConfig();
			log.info("Loaded report descriptors");
			return Outcome.LOADED;
		}
		catch (Exception e) {
			log.error("Failed to load report descriptors", e);
			return Outcome.FAILED;
		}
	}
}
