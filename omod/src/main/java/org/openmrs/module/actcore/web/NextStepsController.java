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

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.openmrs.Patient;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.context.Context;
import org.openmrs.module.actcore.nextsteps.NextStep;
import org.openmrs.messagesource.MessageSourceService;
import org.openmrs.module.actcore.nextsteps.NextSteps;
import org.openmrs.module.actcore.nextsteps.StepReason;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.openmrs.module.webservices.rest.web.response.IllegalRequestException;
import org.openmrs.module.webservices.rest.web.response.ObjectNotFoundException;
import org.openmrs.util.PrivilegeConstants;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/** Serves the next steps the chart suggests for a patient. */
@Controller
@RequestMapping("/rest/" + RestConstants.VERSION_1 + "/actcore")
public class NextStepsController extends ActCoreRestController {
	
	/** The prefix of a reason's message code; the rest is {@link StepReason#getCode()}. */
	static final String REASON_PREFIX = "actcore.nextStep.reason.";
	
	/**
	 * The format of a date in a reason's text: dd-MMM-yyyy with English month names whatever the user's
	 * locale, as ACT 2.0 wrote it.
	 */
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);
	
	/**
	 * Gets the patient's next steps, as {@link NextSteps#of(Patient, LocalDate)} gives them as of
	 * today.
	 *
	 * @param patientUuid the patient's uuid
	 * @return the steps as {@code steps}, each with its key, form, title, reason, referral type and
	 *         whether it is new, done, on hold or an alert. The title and reason are written in the
	 *         user's locale, falling back to English; {@code titleCode} and {@code reasonCode} name
	 *         their messages, and {@code reasonArgs} holds the values the reason fills in, a date as an
	 *         ISO yyyy-MM-dd string and a count as a number, so that a client can write the text itself
	 * @throws IllegalRequestException when no patient is given
	 * @throws ObjectNotFoundException when no patient has the uuid
	 * @throws APIAuthenticationException when the user lacks Get Patients or Get Observations
	 */
	@RequestMapping(value = "/nextsteps", method = RequestMethod.GET)
	@ResponseBody
	public SimpleObject getNextSteps(@RequestParam(value = "patient", required = false) String patientUuid) {
		if (StringUtils.isBlank(patientUuid)) {
			throw new IllegalRequestException("The patient is required");
		}
		Patient patient = Context.getPatientService().getPatientByUuid(patientUuid);
		if (patient == null) {
			throw new ObjectNotFoundException();
		}
		// Thrown here: the REST layer answers requirePrivilege's exception with a 500.
		if (!Context.hasPrivilege(PrivilegeConstants.GET_OBS)) {
			throw new APIAuthenticationException("Privilege required: " + PrivilegeConstants.GET_OBS);
		}
		
		MessageSourceService messages = Context.getMessageSourceService();
		Locale locale = Context.getLocale();
		List<SimpleObject> steps = new ArrayList<SimpleObject>();
		for (NextStep step : NextSteps.of(patient, LocalDate.now())) {
			StepReason reason = step.getReason();
			String reasonCode = REASON_PREFIX + reason.getCode();
			steps.add(new SimpleObject().add("key", step.getKey()).add("form", step.getForm())
			        .add("title", messages.getMessage(step.getTitleCode(), null, locale))
			        .add("titleCode", step.getTitleCode())
			        .add("reason", messages.getMessage(reasonCode, shown(reason.getArgs()), locale))
			        .add("reasonCode", reasonCode).add("reasonArgs", sent(reason.getArgs())).add("isNew", step.isNew())
			        .add("done", step.isDone()).add("onHold", step.isOnHold()).add("referralType", step.getReferralType())
			        .add("alert", step.isAlert()));
		}
		return new SimpleObject().add("steps", steps);
	}
	
	/**
	 * Formats a reason's values for its text in the user's locale: a date as dd-MMM-yyyy with English
	 * month names whatever the locale, as ACT 2.0 wrote it, and anything else as written.
	 *
	 * @param args the reason's values
	 * @return the values as text, in order
	 */
	private static Object[] shown(List<Object> args) {
		Object[] shown = new Object[args.size()];
		for (int i = 0; i < shown.length; i++) {
			Object arg = args.get(i);
			shown[i] = arg instanceof LocalDate ? DAY.format((LocalDate) arg) : String.valueOf(arg);
		}
		return shown;
	}
	
	/**
	 * Turns a reason's values into what the response sends: a date as an ISO yyyy-MM-dd string, so the
	 * app can show it in the user's own format, and a number as a number.
	 *
	 * @param args the reason's values
	 * @return the values to send, in order
	 */
	private static List<Object> sent(List<Object> args) {
		List<Object> sent = new ArrayList<Object>();
		for (Object arg : args) {
			sent.add(arg instanceof LocalDate ? arg.toString() : arg);
		}
		return sent;
	}
}
