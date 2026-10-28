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

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.openmrs.module.reporting.config.ReportDescriptor;
import org.openmrs.module.reporting.config.ReportLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The privilege each ACT report needs, by the report's uuid. A descriptor names it under
 * {@code config:
 * privilege:}, which the reporting module's loader passes over.
 */
public final class ReportPrivileges {
	
	/** The key under a descriptor's {@code config} holding its privilege. */
	public static final String CONFIG_KEY = "privilege";
	
	/**
	 * Held by no job role, so a report whose descriptor names no privilege is for administrators only.
	 */
	public static final String UNASSIGNED = "Task: act.report.unassigned";
	
	private static final Logger log = LoggerFactory.getLogger(ReportPrivileges.class);
	
	private static final Map<String, String> BY_UUID = new ConcurrentHashMap<String, String>();
	
	private ReportPrivileges() {
	}
	
	/** The privilege a report needs, or null when it is not an ACT report. */
	public static String of(String reportUuid) {
		return reportUuid == null ? null : BY_UUID.get(reportUuid);
	}
	
	static void register(String reportUuid, String privilege) {
		BY_UUID.put(reportUuid, privilege);
	}
	
	static void clear() {
		BY_UUID.clear();
	}
	
	/** The privilege a descriptor names, {@value #UNASSIGNED} when it names none. */
	static String privilegeOf(ReportDescriptor descriptor) {
		Object named = descriptor.getConfig() == null ? null : descriptor.getConfig().get(CONFIG_KEY);
		return named == null || String.valueOf(named).trim().isEmpty() ? UNASSIGNED : String.valueOf(named).trim();
	}
	
	/**
	 * Fills the map from the descriptors on disk, whatever
	 * reporting.loadReportsFromConfigurationAtStartup says; a failure is logged and keeps what was
	 * loaded.
	 * 
	 * @return how many ACT reports are known
	 */
	public static int load() {
		try {
			List<ReportDescriptor> descriptors = ReportLoader.loadReportDescriptors();
			for (ReportDescriptor descriptor : descriptors) {
				String privilege = privilegeOf(descriptor);
				if (UNASSIGNED.equals(privilege)) {
					log.error("Report '{}' names no privilege under config.privilege, so only administrators may run it",
					    descriptor.getName());
				}
				register(descriptor.getUuid(), privilege);
			}
			if (descriptors.isEmpty()) {
				log.error("No report descriptors were found, so no ACT report is protected by a per-report privilege");
			}
		}
		catch (Exception e) {
			log.error("Could not read the report descriptors, so ACT reports are not protected by a per-report privilege",
			    e);
		}
		return BY_UUID.size();
	}
}
