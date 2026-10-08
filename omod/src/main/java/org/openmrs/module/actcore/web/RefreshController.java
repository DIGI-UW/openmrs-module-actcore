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
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.Daemon;
import org.openmrs.module.DaemonToken;
import org.openmrs.module.actcore.ActCoreActivator;
import org.openmrs.module.actcore.task.PatientFlagRefreshTask;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.openmrs.module.webservices.rest.web.v1_0.controller.BaseRestController;
import org.openmrs.util.PrivilegeConstants;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Runs the patient flag refresh, adherence first, on demand, and says when it last finished.
 */
@Controller
@RequestMapping("/rest/" + RestConstants.VERSION_1 + "/actcore")
public class RefreshController extends BaseRestController {
	
	/** The distribution creates it; a user without it, other than a superuser, is refused. */
	public static final String PRIVILEGE = "Task: act.refreshFlags";
	
	@RequestMapping(value = "/refresh", method = RequestMethod.GET)
	@ResponseBody
	public SimpleObject getStatus() {
		requirePrivilege();
		return status();
	}
	
	/**
	 * Runs as the scheduler's daemon user, as the nightly run does: the refresh reads and writes far
	 * more than the caller's own privileges allow, and on their request it would fail flag by flag.
	 */
	@RequestMapping(value = "/refresh", method = RequestMethod.POST)
	@ResponseBody
	public SimpleObject refresh() {
		requirePrivilege();
		DaemonToken token = ActCoreActivator.getDaemonToken();
		if (token == null) {
			throw new APIException("ACT Core has not started, so it cannot run the refresh");
		}
		final PatientFlagRefreshTask.Result[] ran = new PatientFlagRefreshTask.Result[1];
		final RuntimeException[] failure = new RuntimeException[1];
		Daemon.runInDaemonThreadAndWait(new Runnable() {
			
			@Override
			public void run() {
				try {
					ran[0] = new PatientFlagRefreshTask().runIfIdle();
				}
				catch (RuntimeException e) {
					failure[0] = e;
				}
			}
		}, token);
		if (failure[0] != null) {
			throw new APIException("The patient flag refresh failed", failure[0]);
		}
		SimpleObject response = status().add("refreshed", ran[0] != null);
		if (ran[0] != null) {
			response.add("flagsFailed", ran[0].flagsFailed).add("listsFailed", ran[0].listsFailed).add("adherenceFailed",
			    ran[0].adherenceFailed);
		}
		return response;
	}
	
	private static SimpleObject status() {
		String lastFinished;
		try {
			Context.addProxyPrivilege(PrivilegeConstants.GET_GLOBAL_PROPERTIES);
			lastFinished = Context.getAdministrationService()
			        .getGlobalProperty(PatientFlagRefreshTask.LAST_FINISHED_PROPERTY);
		}
		finally {
			Context.removeProxyPrivilege(PrivilegeConstants.GET_GLOBAL_PROPERTIES);
		}
		return new SimpleObject().add("lastRefreshed", StringUtils.defaultIfBlank(lastFinished, null)).add("running",
		    PatientFlagRefreshTask.isRunning());
	}
	
	private static void requirePrivilege() {
		if (!Context.hasPrivilege(PRIVILEGE)) {
			throw new APIAuthenticationException("Privilege required: " + PRIVILEGE);
		}
	}
}
