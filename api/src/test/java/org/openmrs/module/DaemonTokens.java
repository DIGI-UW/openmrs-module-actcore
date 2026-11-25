/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module;

/**
 * Gives an activator its daemon token as core does on module start, which a context test skips. In
 * this package because ModuleFactory.passDaemonToken is package-private.
 */
public class DaemonTokens {
	
	public static void pass(String moduleId, ModuleActivator activator) {
		Module module = new Module(moduleId);
		module.setModuleId(moduleId);
		module.setModuleActivator(activator);
		ModuleFactory.passDaemonToken(module);
	}
}
