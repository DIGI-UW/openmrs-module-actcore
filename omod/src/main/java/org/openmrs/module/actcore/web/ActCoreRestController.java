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
import java.time.Period;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.openmrs.Location;
import org.openmrs.Patient;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.ContextAuthenticationException;
import org.openmrs.module.actcore.api.impl.Names;
import org.openmrs.module.actcore.model.LogUser;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.response.IllegalRequestException;
import org.openmrs.module.webservices.rest.web.response.ObjectNotFoundException;
import org.openmrs.module.webservices.rest.web.v1_0.controller.BaseRestController;
import org.openmrs.util.PrivilegeConstants;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * The base class of the module's REST controllers. It answers a request parameter that is missing
 * or of the wrong type with 400, a request body of a content type the endpoint does not read with
 * 415, and a privilege that {@link org.openmrs.api.context.Context#requirePrivilege(String)}
 * refuses with 401 or 403, as for an {@link APIAuthenticationException}. BaseRestController would
 * answer all three with 500. It also holds the request helpers the controllers share, which find a
 * request's patient and location, read its dates and months, and describe a user.
 */
public abstract class ActCoreRestController extends BaseRestController {
	
	/**
	 * How many months back a log reads when the request gives no start date, unless the log has a
	 * default of its own.
	 */
	static final int DEFAULT_MONTHS = 12;
	
	/**
	 * Answers a request parameter that is missing or of the wrong type with 400 and a message naming
	 * it.
	 *
	 * @param e the MethodArgumentTypeMismatchException or MissingServletRequestParameterException
	 * @param response the response, whose status is set to 400
	 * @return an {@code error} holding the {@code message}, without a stack trace
	 */
	@ExceptionHandler({ MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class })
	@ResponseBody
	public SimpleObject badParameterHandler(Exception e, HttpServletResponse response) {
		response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
		String message;
		if (e instanceof MethodArgumentTypeMismatchException) {
			MethodArgumentTypeMismatchException mismatch = (MethodArgumentTypeMismatchException) e;
			message = mismatch.getName() + " cannot be " + mismatch.getValue();
		} else {
			message = ((MissingServletRequestParameterException) e).getParameterName() + " is required";
		}
		// No stack trace or code: the caller sent the request wrong, and the server has nothing to show.
		return new SimpleObject().add("error", new SimpleObject().add("message", message));
	}
	
	/**
	 * Answers a request body of a content type the endpoint does not read with 415, in JSON whatever
	 * the request accepts, and names JSON in the {@code Accept} header as the type the module's
	 * endpoints read.
	 *
	 * @param e the HttpMediaTypeNotSupportedException
	 * @param response the response, whose status is set to 415
	 * @return an {@code error} holding the {@code message}, without a stack trace
	 */
	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	@ResponseBody
	public SimpleObject unsupportedMediaTypeHandler(HttpMediaTypeNotSupportedException e, HttpServletResponse response) {
		response.setStatus(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
		return new SimpleObject().add("error", new SimpleObject().add("message", e.getMessage()));
	}
	
	/**
	 * Answers the {@link ContextAuthenticationException} that
	 * {@link org.openmrs.api.context.Context#requirePrivilege(String)} throws as BaseRestController
	 * answers an {@link APIAuthenticationException}: 401 when no user is logged in, else 403.
	 *
	 * @param e the ContextAuthenticationException
	 * @param request the request
	 * @param response the response, whose status is set to 401 or 403
	 * @return an {@code error} holding the {@code message} and the exception's detail
	 * @throws Exception if BaseRestController's handler fails
	 */
	@ExceptionHandler(ContextAuthenticationException.class)
	@ResponseBody
	public SimpleObject privilegeRequiredHandler(ContextAuthenticationException e, HttpServletRequest request,
	        HttpServletResponse response) throws Exception {
		return apiAuthenticationExceptionHandler(e, request, response);
	}
	
	/**
	 * Gets the patient a request names.
	 *
	 * @param uuid the patient's uuid
	 * @return the patient
	 * @throws IllegalRequestException when the uuid is blank
	 * @throws ObjectNotFoundException when no patient has the uuid
	 * @throws APIAuthenticationException when the user lacks Get Patients
	 */
	static Patient patient(String uuid) {
		if (StringUtils.isBlank(uuid)) {
			throw new IllegalRequestException("The patient is required");
		}
		Patient patient = Context.getPatientService().getPatientByUuid(uuid);
		if (patient == null) {
			throw new ObjectNotFoundException();
		}
		return patient;
	}
	
	/**
	 * Gets the location a request names, which needs no privilege, as a clinic is configuration.
	 *
	 * @param uuid the location's uuid, trimmed before the look-up
	 * @return the location; null when the uuid is blank or no location has it
	 */
	static Location location(String uuid) {
		return StringUtils.isBlank(uuid) ? null
		        : Names.proxied(PrivilegeConstants.GET_LOCATIONS,
		            () -> Context.getLocationService().getLocationByUuid(uuid.trim()));
	}
	
	/**
	 * Reads a date a request gives.
	 *
	 * @param name the parameter's name, which the refusal names
	 * @param value the date as YYYY-MM-DD, or blank
	 * @param fallback the date a blank value stands for
	 * @return the date, or the fallback when the value is null or blank
	 * @throws IllegalRequestException when the value is not a date
	 */
	static LocalDate date(String name, Object value, LocalDate fallback) {
		if (value == null || StringUtils.isBlank(value.toString())) {
			return fallback;
		}
		try {
			return LocalDate.parse(value.toString().trim());
		}
		catch (DateTimeParseException e) {
			throw new IllegalRequestException(name + " must be a date as YYYY-MM-DD");
		}
	}
	
	/**
	 * Reads a month a request gives.
	 *
	 * @param name the parameter's name, which the refusal names
	 * @param value the month as YYYY-MM, or blank
	 * @param fallback the month a blank value stands for, or null when the month is required
	 * @return the month, or the fallback when the value is null or blank
	 * @throws IllegalRequestException when the value is not a month, or is blank without a fallback
	 */
	static YearMonth month(String name, String value, YearMonth fallback) {
		if (StringUtils.isBlank(value)) {
			if (fallback == null) {
				throw new IllegalRequestException(name + " must be a month as YYYY-MM");
			}
			return fallback;
		}
		try {
			return YearMonth.parse(value.trim(), DateTimeFormatter.ofPattern("uuuu-MM"));
		}
		catch (DateTimeParseException e) {
			throw new IllegalRequestException(name + " must be a month as YYYY-MM");
		}
	}
	
	/**
	 * Works out the first day a log reads: the start date given, or else the span {@code back} before
	 * the end date, or before today without one, so that a request without dates does not read the
	 * whole log.
	 *
	 * @param startDate the start date as YYYY-MM-DD, or blank
	 * @param to the end date, or null for none
	 * @param back how far before the end a blank start date reaches
	 * @return the first day to read
	 * @throws IllegalRequestException when the start date is not a date or is after the end date
	 */
	static LocalDate from(String startDate, LocalDate to, Period back) {
		LocalDate from = date("startDate", startDate, null);
		if (from == null) {
			return (to == null ? LocalDate.now() : to).minus(back);
		}
		if (to != null && from.isAfter(to)) {
			throw new IllegalRequestException("The start date is after the end date");
		}
		return from;
	}
	
	/**
	 * Describes a user as the logs give them.
	 *
	 * @param who the user, or null
	 * @return the user's uuid, username, display and role; null for a null user
	 */
	static SimpleObject who(LogUser who) {
		return who == null ? null
		        : new SimpleObject().add("uuid", who.uuid).add("username", who.username).add("display", who.display)
		                .add("role", who.role);
	}
}
