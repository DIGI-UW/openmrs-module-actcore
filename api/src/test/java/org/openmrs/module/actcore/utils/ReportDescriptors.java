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

import java.io.File;
import java.nio.file.Files;

import org.openmrs.util.OpenmrsUtil;

/**
 * Writes one report descriptor as the distribution's configuration/reports/reportdescriptors holds
 * them.
 */
public final class ReportDescriptors {
	
	public static final String UUID = "5b0c7a1e-3f4d-4a6b-9c8d-2e1f0a9b8c7d";
	
	public static final String NAME = "ACT Core test report";
	
	private ReportDescriptors() {
	}
	
	/**
	 * Makes the directory the application data directory and writes the descriptor into its
	 * configuration.
	 *
	 * @param dataDirectory the directory to use as the application data directory
	 */
	public static void write(File dataDirectory) throws Exception {
		OpenmrsUtil.setApplicationDataDirectory(dataDirectory.getAbsolutePath());
		File dir = new File(dataDirectory, "configuration/reports/reportdescriptors/test");
		new File(dir, "sql").mkdirs();
		Files.write(new File(dir, "sql/patients.sql").toPath(), "select patient_id from patient".getBytes("UTF-8"));
		String descriptor = "key: actcoreTestReport\n" + "uuid: \"" + UUID + "\"\n" + "name: \"" + NAME + "\"\n"
		        + "datasets:\n" + "  - key: patients\n" + "    type: sql\n" + "    config: \"sql/patients.sql\"\n";
		Files.write(new File(dir, "patients.yml").toPath(), descriptor.getBytes("UTF-8"));
	}
}
