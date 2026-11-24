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

import org.apache.commons.lang3.StringUtils;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.ContextAuthenticationException;
import org.openmrs.module.actcore.ActCoreActivator;
import org.openmrs.module.actcore.ActCoreConstants;
import org.openmrs.module.actcore.ActCorePrivileges;
import org.openmrs.module.actcore.tasks.PatientFlagRefreshTask;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.openmrs.util.PrivilegeConstants;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Runs the patient flag refresh on demand, refreshing adherence first, and says when the refresh
 * last finished and whether it is running.
 */
@Controller
@RequestMapping("/rest/" + RestConstants.VERSION_1 + "/actcore")
public class RefreshController extends ActCoreRestController {
	
	/**
	 * Gets when the refresh last finished and whether it is running now.
	 *
	 * @return {@code lastRefreshed}, an ISO-8601 instant or null before the first run finishes, and
	 *         {@code running}
	 * @throws ContextAuthenticationException when the user lacks
	 *             {@link ActCorePrivileges#REFRESH_FLAGS}
	 */
	@RequestMapping(value = "/refresh", method = RequestMethod.GET)
	@ResponseBody
	public SimpleObject getStatus() {
		Context.requirePrivilege(ActCorePrivileges.REFRESH_FLAGS);
		return status();
	}
	
	/**
	 * Runs the refresh now and waits for it, unless a run is already in progress. Runs as the
	 * scheduler's daemon user, as the nightly run does: the refresh reads and writes far more than the
	 * caller's own privileges allow, and on their request it would fail flag by flag.
	 *
	 * @return the status, as {@link #getStatus()} gives it, with {@code refreshed}, false when another
	 *         run was in progress; after a run, also how many flags and lists failed and whether the
	 *         adherence refresh failed
	 * @throws ContextAuthenticationException when the user lacks
	 *             {@link ActCorePrivileges#REFRESH_FLAGS}
	 * @throws APIException when the module has not started or the refresh throws
	 */
	// JSON only: another site's page can post a form here with the user's cookie, but not JSON.
	@RequestMapping(value = "/refresh", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE)
	@ResponseBody
	public SimpleObject refresh() {
		Context.requirePrivilege(ActCorePrivileges.REFRESH_FLAGS);
		PatientFlagRefreshTask.Result ran = ActCoreActivator.runAsDaemon("run the refresh",
		    "The patient flag refresh failed", () -> new PatientFlagRefreshTask().runIfIdle());
		SimpleObject response = status().add("refreshed", ran != null);
		if (ran != null) {
			response.add("flagsFailed", ran.flagsFailed).add("listsFailed", ran.listsFailed).add("adherenceFailed",
			    ran.adherenceFailed);
		}
		return response;
	}
	
	private static SimpleObject status() {
		String lastFinished;
		try {
			Context.addProxyPrivilege(PrivilegeConstants.GET_GLOBAL_PROPERTIES);
			lastFinished = Context.getAdministrationService().getGlobalProperty(ActCoreConstants.GP_REFRESH_LAST_FINISHED);
		}
		finally {
			Context.removeProxyPrivilege(PrivilegeConstants.GET_GLOBAL_PROPERTIES);
		}
		return new SimpleObject().add("lastRefreshed", StringUtils.defaultIfBlank(lastFinished, null)).add("running",
		    PatientFlagRefreshTask.isRunning());
	}
}
