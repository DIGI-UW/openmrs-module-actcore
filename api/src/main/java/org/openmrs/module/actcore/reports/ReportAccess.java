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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.apache.commons.lang3.StringUtils;
import org.openmrs.User;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.Daemon;
import org.openmrs.module.actcore.clinics.UserClinics;
import org.openmrs.module.reporting.dataset.definition.service.DataSetDefinitionService;
import org.openmrs.module.reporting.evaluation.EvaluationContext;
import org.openmrs.module.reporting.evaluation.parameter.Mapped;
import org.openmrs.module.reporting.evaluation.parameter.Parameterizable;
import org.openmrs.module.reporting.report.ReportRequest;
import org.openmrs.module.reporting.report.definition.ReportDefinition;
import org.openmrs.module.reporting.report.definition.service.ReportDefinitionService;
import org.openmrs.module.reporting.report.service.ReportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;

/**
 * Holds an ACT report to its own {@code Task: act.report.*} privilege, whatever View Reports and
 * Run Reports a user holds. It runs ahead of core's authorization, which lets a holder of the
 * privilege through.
 */
public class ReportAccess implements MethodInterceptor {
	
	/** Matches no location uuid, and is not empty, which a report reads as every clinic. */
	public static final String NO_CLINIC = "none";
	
	private static final String VIEW_REPORTS = "View Reports";
	
	private static final String RUN_REPORTS = "Run Reports";
	
	private static final String GET_PATIENTS = "Get Patients";
	
	/** A stored definition names its creator, which reporting resolves through the user service. */
	private static final String GET_USERS = "Get Users";
	
	private static final String CLINICS = "clinics";
	
	private static final Logger log = LoggerFactory.getLogger(ReportAccess.class);
	
	/** Set while this advice looks a report up itself, which comes back through the advised service. */
	private static final ThreadLocal<Boolean> LOOKING_UP = new ThreadLocal<Boolean>();
	
	private static final Class<?>[] SERVICES = { ReportDefinitionService.class, DataSetDefinitionService.class,
	        ReportService.class };
	
	/**
	 * Puts the advice in front of each reporting service the module guards, once. A rebuilt service has
	 * none, so this runs at every context refresh.
	 *
	 * @return how many services it put the advice in front of
	 */
	public static int install() {
		int installed = 0;
		for (Class<?> service : SERVICES) {
			Object proxy = Context.getService(service);
			if (!(proxy instanceof Advised)) {
				log.error("The {} is not an advised proxy, so ACT reports are not protected by a per-report privilege",
				    service.getSimpleName());
				continue;
			}
			Advised advised = (Advised) proxy;
			if (!inFront(advised)) {
				removeFrom(advised);
				advised.addAdvice(0, new ReportAccess());
				installed++;
			}
		}
		return installed;
	}
	
	/** Takes the advice out again. */
	public static void uninstall() {
		for (Class<?> service : SERVICES) {
			Object proxy = Context.getService(service);
			if (proxy instanceof Advised) {
				removeFrom((Advised) proxy);
			}
		}
	}
	
	/** Whether every guarded service has the advice first, ahead of core's authorization. */
	public static boolean isInstalledInFront() {
		for (Class<?> service : SERVICES) {
			Object proxy = Context.getService(service);
			if (!(proxy instanceof Advised) || !inFront((Advised) proxy)) {
				return false;
			}
		}
		return true;
	}
	
	private static boolean inFront(Advised advised) {
		Advisor[] advisors = advised.getAdvisors();
		return advisors.length > 0 && advisors[0].getAdvice() instanceof ReportAccess;
	}
	
	private static void removeFrom(Advised advised) {
		for (Advisor advisor : advised.getAdvisors()) {
			if (advisor.getAdvice() instanceof ReportAccess) {
				advised.removeAdvisor(advisor);
			}
		}
	}
	
	@Override
	public Object invoke(MethodInvocation invocation) throws Throwable {
		User me = Context.getAuthenticatedUser();
		if (Boolean.TRUE.equals(LOOKING_UP.get()) || Daemon.isDaemonThread() || me == null || me.isSuperUser()) {
			return invocation.proceed();
		}
		Object service = invocation.getThis();
		if (service instanceof ReportDefinitionService) {
			return reportDefinitions(invocation, me);
		}
		if (service instanceof DataSetDefinitionService) {
			return dataSets(invocation, me);
		}
		if (service instanceof ReportService) {
			return reportRequests(invocation, me);
		}
		return invocation.proceed();
	}
	
	private Object reportDefinitions(MethodInvocation invocation, User me) throws Throwable {
		String method = invocation.getMethod().getName();
		Object[] args = invocation.getArguments();
		if (("getDefinitionByUuid".equals(method) || "getDefinition".equals(method)) && args.length > 0) {
			boolean mayView = Context.hasPrivilege(VIEW_REPORTS);
			Object found = proceedAs(invocation, VIEW_REPORTS, GET_USERS);
			if (found instanceof ReportDefinition) {
				requireAccess((ReportDefinition) found, mayView);
			}
			return found;
		}
		if (("getAllDefinitions".equals(method) || "getDefinitions".equals(method))) {
			boolean mayView = Context.hasPrivilege(VIEW_REPORTS);
			Object found = proceedAs(invocation, VIEW_REPORTS, GET_USERS);
			return found instanceof List ? visible((List<?>) found, mayView) : found;
		}
		if ("evaluate".equals(method) && args.length >= 2 && isReportDefinition(args[0])) {
			return evaluate(invocation);
		}
		return invocation.proceed();
	}
	
	private Object evaluate(MethodInvocation invocation) throws Throwable {
		Object[] args = invocation.getArguments();
		ReportDefinition given = (ReportDefinition) definitionOf(args[0]);
		String needed = ReportPrivileges.of(given.getUuid());
		if (needed != null) {
			if (!Context.hasPrivilege(needed)) {
				throw refusal(needed);
			}
			args[0] = replaceDefinition(args[0], persisted(given.getUuid()));
		}
		return needed == null ? invocation.proceed()
		        : proceedAs(invocation, VIEW_REPORTS, RUN_REPORTS, GET_PATIENTS, GET_USERS);
	}
	
	private Object dataSets(MethodInvocation invocation, User me) throws Throwable {
		if ("evaluate".equals(invocation.getMethod().getName()) && invocation.getArguments().length >= 2) {
			limitToClinics(invocation.getArguments(), me);
		}
		return invocation.proceed();
	}
	
	private Object reportRequests(MethodInvocation invocation, User me) throws Throwable {
		String method = invocation.getMethod().getName();
		Object[] args = invocation.getArguments();
		if (("queueReport".equals(method) || "saveReportRequest".equals(method) || "runReport".equals(method))
		        && args.length == 1 && args[0] instanceof ReportRequest) {
			ReportRequest request = (ReportRequest) args[0];
			Mapped<ReportDefinition> mapped = request.getReportDefinition();
			if (mapped != null && mapped.getParameterizable() != null) {
				String needed = ReportPrivileges.of(mapped.getParameterizable().getUuid());
				if (needed != null && !Context.hasPrivilege(needed)) {
					throw refusal(needed);
				}
				if (UserClinics.isLimited(me)) {
					request.setReportDefinition(new Mapped<ReportDefinition>(mapped.getParameterizable(),
					        withClinics(mapped.getParameterMappings(), me)));
				}
			}
		}
		Object result = invocation.proceed();
		if (("getReportRequestByUuid".equals(method) || "getReportRequest".equals(method))
		        && result instanceof ReportRequest) {
			Mapped<ReportDefinition> mapped = ((ReportRequest) result).getReportDefinition();
			String needed = mapped == null || mapped.getParameterizable() == null ? null
			        : ReportPrivileges.of(mapped.getParameterizable().getUuid());
			if (needed != null && !Context.hasPrivilege(needed)) {
				throw refusal(needed);
			}
		}
		return result;
	}
	
	/** The report as it is stored, which a posted copy carrying the same uuid must not stand in for. */
	private static ReportDefinition persisted(String uuid) {
		LOOKING_UP.set(Boolean.TRUE);
		Context.addProxyPrivilege(VIEW_REPORTS);
		Context.addProxyPrivilege(GET_USERS);
		try {
			ReportDefinition found = Context.getService(ReportDefinitionService.class).getDefinitionByUuid(uuid);
			if (found == null) {
				throw new APIAuthenticationException("No stored report has the uuid " + uuid);
			}
			return found;
		}
		finally {
			Context.removeProxyPrivilege(GET_USERS);
			Context.removeProxyPrivilege(VIEW_REPORTS);
			LOOKING_UP.remove();
		}
	}
	
	private static Object proceedAs(MethodInvocation invocation, String... privileges) throws Throwable {
		for (String privilege : privileges) {
			Context.addProxyPrivilege(privilege);
		}
		try {
			return invocation.proceed();
		}
		finally {
			for (String privilege : privileges) {
				Context.removeProxyPrivilege(privilege);
			}
		}
	}
	
	private static void requireAccess(ReportDefinition report, boolean mayView) {
		String needed = ReportPrivileges.of(report.getUuid());
		if (needed == null ? !mayView : !Context.hasPrivilege(needed)) {
			throw refusal(needed == null ? VIEW_REPORTS : needed);
		}
	}
	
	private static List<Object> visible(Collection<?> reports, boolean mayView) {
		List<Object> allowed = new ArrayList<Object>();
		for (Object report : reports) {
			String needed = report instanceof ReportDefinition ? ReportPrivileges.of(((ReportDefinition) report).getUuid())
			        : null;
			if (needed == null ? mayView : Context.hasPrivilege(needed)) {
				allowed.add(report);
			}
		}
		return allowed;
	}
	
	private static boolean isReportDefinition(Object argument) {
		return definitionOf(argument) instanceof ReportDefinition;
	}
	
	private static Parameterizable definitionOf(Object argument) {
		if (argument instanceof Mapped) {
			return ((Mapped<?>) argument).getParameterizable();
		}
		return argument instanceof Parameterizable ? (Parameterizable) argument : null;
	}
	
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static Object replaceDefinition(Object argument, ReportDefinition stored) {
		if (argument instanceof Mapped) {
			return new Mapped(stored, ((Mapped<?>) argument).getParameterMappings());
		}
		return stored;
	}
	
	/**
	 * Sets a limited user's clinics on the context a dataset is evaluated with, over any the caller
	 * supplied.
	 */
	private static void limitToClinics(Object[] args, User me) {
		if (!UserClinics.isLimited(me)) {
			return;
		}
		for (Object argument : args) {
			if (argument instanceof EvaluationContext) {
				((EvaluationContext) argument).addParameterValue(CLINICS, clinicsOf(me));
			}
		}
	}
	
	private static Map<String, Object> withClinics(Map<String, Object> mappings, User me) {
		Map<String, Object> limited = new HashMap<String, Object>();
		if (mappings != null) {
			limited.putAll(mappings);
		}
		limited.put(CLINICS, clinicsOf(me));
		return limited;
	}
	
	private static String clinicsOf(User me) {
		String clinics = StringUtils.join(UserClinics.of(me), ",");
		return clinics.isEmpty() ? NO_CLINIC : clinics;
	}
	
	private static APIAuthenticationException refusal(String privilege) {
		return new APIAuthenticationException("Privileges required: " + privilege);
	}
}
