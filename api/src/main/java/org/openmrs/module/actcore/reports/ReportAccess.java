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
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.apache.commons.lang3.StringUtils;
import org.openmrs.User;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.Daemon;
import org.openmrs.module.DaemonToken;
import org.openmrs.module.actcore.ActCoreActivator;
import org.openmrs.module.actcore.clinics.UserClinics;
import org.openmrs.module.reporting.dataset.definition.service.DataSetDefinitionService;
import org.openmrs.module.reporting.definition.DefinitionSummary;
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
 * Run Reports a user holds. It runs ahead of core's authorization.
 */
public class ReportAccess implements MethodInterceptor {
	
	/** Matches no location uuid, and is not empty, which a report reads as every clinic. */
	public static final String NO_CLINIC = "none";
	
	private static final String VIEW_REPORTS = "View Reports";
	
	private static final String CLINICS = "clinics";
	
	private static final Logger log = LoggerFactory.getLogger(ReportAccess.class);
	
	private static final Class<?>[] SERVICES = { ReportDefinitionService.class, DataSetDefinitionService.class,
	        ReportService.class };
	
	/** What the advice hands to the daemon thread, and what it hands back. */
	private interface Work {
		
		Object run() throws Throwable;
	}
	
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
		if (Daemon.isDaemonThread() || me == null || me.isSuperUser()) {
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
			Object found = asDaemon(invocation::proceed);
			if (found instanceof ReportDefinition) {
				requireAccess((ReportDefinition) found, mayView);
			}
			return found;
		}
		if ("getAllDefinitions".equals(method) || "getDefinitions".equals(method)
		        || "getAllDefinitionSummaries".equals(method) || "getDefinitionsByTag".equals(method)) {
			boolean mayView = Context.hasPrivilege(VIEW_REPORTS);
			Object found = asDaemon(invocation::proceed);
			return found instanceof List ? visible((List<?>) found, mayView) : found;
		}
		if ("evaluate".equals(method) && args.length >= 2 && isReportDefinition(args[0])) {
			return evaluate(invocation, me);
		}
		return invocation.proceed();
	}
	
	private Object evaluate(MethodInvocation invocation, User me) throws Throwable {
		Object[] args = invocation.getArguments();
		String uuid = definitionOf(args[0]).getUuid();
		String needed = ReportPrivileges.of(uuid);
		if (needed == null) {
			return invocation.proceed();
		}
		if (!Context.hasPrivilege(needed)) {
			throw refusal(needed);
		}
		if (UserClinics.isLimited(me)) {
			limitToClinics(args, me);
			args[0] = limited(args[0], me);
		}
		return asDaemon(() -> {
			args[0] = replaceDefinition(args[0], persisted(uuid));
			return invocation.proceed();
		});
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
		if (args.length > 0 && args[0] instanceof ReportRequest) {
			ReportRequest request = (ReportRequest) args[0];
			String needed = privilegeOf(request);
			if (needed != null) {
				if (!Context.hasPrivilege(needed)) {
					throw refusal(needed);
				}
				if ("queueReport".equals(method) || "saveReportRequest".equals(method) || "runReport".equals(method)) {
					storeDefinition(request, me);
				}
			}
		}
		Object result = invocation.proceed();
		if (result instanceof ReportRequest) {
			String needed = privilegeOf((ReportRequest) result);
			if (needed != null && !Context.hasPrivilege(needed)) {
				throw refusal(needed);
			}
		}
		if ("getReportRequests".equals(method) && result instanceof List) {
			return visible((List<?>) result, true);
		}
		if ("getReportRequestsCount".equals(method)) {
			return (long) Context.getService(ReportService.class).getReportRequests((ReportDefinition) args[0],
			    (Date) args[1], (Date) args[2], (ReportRequest.Status[]) args[3]).size();
		}
		return result;
	}
	
	/** Puts the stored report, and a limited user's clinics, in the request that is about to run. */
	private static void storeDefinition(ReportRequest request, User me) throws Throwable {
		Mapped<ReportDefinition> mapped = request.getReportDefinition();
		Map<String, Object> mappings = mapped.getParameterMappings();
		request.setReportDefinition(
		    new Mapped<ReportDefinition>((ReportDefinition) asDaemon(() -> persisted(mapped.getParameterizable().getUuid())),
		            UserClinics.isLimited(me) ? withClinics(mappings, me) : mappings));
	}
	
	private static String privilegeOf(ReportRequest request) {
		Mapped<ReportDefinition> mapped = request.getReportDefinition();
		return mapped == null || mapped.getParameterizable() == null ? null
		        : ReportPrivileges.of(mapped.getParameterizable().getUuid());
	}
	
	/** The report as it is stored, which a posted copy carrying the same uuid must not stand in for. */
	private static ReportDefinition persisted(String uuid) {
		ReportDefinition found = Context.getService(ReportDefinitionService.class).getDefinitionByUuid(uuid);
		if (found == null) {
			throw new APIAuthenticationException("No stored report has the uuid " + uuid);
		}
		return found;
	}
	
	/**
	 * Runs the work as the daemon user, so the caller's session is given no privilege it lacks. The
	 * caller has been checked against the report's own privilege before this.
	 */
	private static Object asDaemon(Work work) throws Throwable {
		DaemonToken token = ActCoreActivator.getDaemonToken();
		if (token == null) {
			throw new APIAuthenticationException("The ACT Core module has not started, so it cannot run an ACT report");
		}
		AtomicReference<Object> result = new AtomicReference<Object>();
		AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
		Daemon.runInDaemonThreadAndWait(() -> {
			try {
				result.set(work.run());
			}
			catch (Throwable t) {
				failure.set(t);
			}
		}, token);
		if (failure.get() != null) {
			throw failure.get();
		}
		return result.get();
	}
	
	private static void requireAccess(ReportDefinition report, boolean mayView) {
		String needed = ReportPrivileges.of(report.getUuid());
		if (needed == null ? !mayView : !Context.hasPrivilege(needed)) {
			throw refusal(needed == null ? VIEW_REPORTS : needed);
		}
	}
	
	private static List<Object> visible(Collection<?> items, boolean mayView) {
		List<Object> allowed = new ArrayList<Object>();
		for (Object item : items) {
			String needed = item instanceof ReportDefinition ? ReportPrivileges.of(((ReportDefinition) item).getUuid())
			        : item instanceof DefinitionSummary ? ReportPrivileges.of(((DefinitionSummary) item).getUuid())
			                : item instanceof ReportRequest ? privilegeOf((ReportRequest) item) : null;
			if (needed == null ? mayView : Context.hasPrivilege(needed)) {
				allowed.add(item);
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
	
	/** The report with its {@code clinics} mapping set to the user's, over any the caller mapped. */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static Object limited(Object argument, User me) {
		if (argument instanceof Mapped) {
			Mapped<?> mapped = (Mapped<?>) argument;
			return new Mapped(mapped.getParameterizable(), withClinics(mapped.getParameterMappings(), me));
		}
		return argument;
	}
	
	/**
	 * Sets a limited user's clinics on the context a report or dataset is evaluated with, over any the
	 * caller supplied.
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
