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

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.RequestContext;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.openmrs.module.webservices.rest.web.RestUtil;
import org.openmrs.module.webservices.rest.web.api.RestService;
import org.openmrs.module.webservices.rest.web.representation.Representation;
import org.openmrs.module.webservices.rest.web.resource.api.Retrievable;
import org.openmrs.module.webservices.rest.web.response.IllegalRequestException;
import org.openmrs.module.webservices.rest.web.response.ObjectNotFoundException;
import org.openmrs.module.webservices.rest.web.response.UnknownResourceException;
import org.openmrs.module.webservices.rest.web.v1_0.controller.BaseRestController;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * The rows of an ACT list, for a user who holds that list's privilege but may not run reports.
 * Which report each list reads is fixed here, so no setting can open another report this way.
 */
@Controller
@RequestMapping("/rest/" + RestConstants.VERSION_1 + "/actcore")
public class ActListController extends BaseRestController {
	
	private static final String REGISTRY = "f1a2b3c4-d5e6-7890-abcd-ef1234567890";
	
	private static final String WAITING_LIST = "5b0f1c2e-9d3a-4c1b-8f6e-2a7d9e4b3c10";
	
	private static final Map<String, ActList> LISTS = new HashMap<String, ActList>();
	
	static {
		list("registry", REGISTRY, "App: act.registry");
		list("registryWaitingList", WAITING_LIST, "App: act.registry");
		list("careCascade", "9c6751ae-65fc-5f25-9aa6-8c65cb1dff68", "App: act.registry");
		list("worklists", REGISTRY, "App: act.worklists");
		list("waitingList", WAITING_LIST, "App: act.waitingList");
		list("screenPositive", "e3b8f7a2-6c41-4d9e-8a57-1f0c2d4b9e63", "App: act.screenPositive");
	}
	
	/** What reporting needs to look up and evaluate a definition, and reportingrest to represent it. */
	private static final List<String> PROXY_PRIVILEGES = Arrays.asList("View Reports", "Run Reports", "Get Users");
	
	private static final String REPORT_DATA_RESOURCE = RestConstants.VERSION_1 + "/reportingrest/reportdata";
	
	@RequestMapping(value = "/list/{list}", method = RequestMethod.GET)
	@ResponseBody
	public SimpleObject getList(@PathVariable("list") String name, HttpServletRequest request,
	        HttpServletResponse response) {
		ActList list = LISTS.get(name);
		if (list == null) {
			throw new ObjectNotFoundException();
		}
		// Thrown here rather than by requirePrivilege, whose exception the REST layer answers with a 500.
		if (!Context.hasPrivilege(list.privilege)) {
			throw new APIAuthenticationException("Privilege required: " + list.privilege);
		}
		// reportingrest would evaluate a cohort named here as the report's starting cohort, under the proxies.
		for (Object parameter : request.getParameterMap().keySet()) {
			if (parameter.equals("cohort") || ((String) parameter).startsWith("cohort.")) {
				throw new IllegalRequestException("An ACT list does not take a cohort");
			}
		}
		
		RequestContext context = RestUtil.getRequestContext(request, response, Representation.DEFAULT);
		for (String privilege : PROXY_PRIVILEGES) {
			Context.addProxyPrivilege(privilege);
		}
		try {
			SimpleObject data = (SimpleObject) reportData().retrieve(list.report, context);
			return new SimpleObject().add("uuid", data.get("uuid")).add("dataSets", data.get("dataSets"));
		}
		finally {
			for (String privilege : PROXY_PRIVILEGES) {
				Context.removeProxyPrivilege(privilege);
			}
		}
	}
	
	private static Retrievable reportData() {
		try {
			return (Retrievable) Context.getService(RestService.class).getResourceByName(REPORT_DATA_RESOURCE);
		}
		catch (UnknownResourceException e) {
			throw new APIException("ACT lists are read through reportingrest, which is not installed", e);
		}
	}
	
	private static void list(String name, String report, String privilege) {
		LISTS.put(name, new ActList(report, privilege));
	}
	
	private static class ActList {
		
		private final String report;
		
		private final String privilege;
		
		ActList(String report, String privilege) {
			this.report = report;
			this.privilege = privilege;
		}
	}
}
