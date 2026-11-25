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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.openmrs.Location;
import org.openmrs.Patient;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.ValidationException;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.ContextAuthenticationException;
import org.openmrs.module.actcore.api.impl.Names;
import org.openmrs.module.actcore.model.LogUser;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.response.ConversionException;
import org.openmrs.module.webservices.rest.web.response.IllegalRequestException;
import org.openmrs.module.webservices.rest.web.response.ObjectNotFoundException;
import org.openmrs.module.webservices.rest.web.v1_0.controller.BaseRestController;
import org.openmrs.util.PrivilegeConstants;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * The base class of the module's REST controllers. It answers a request parameter that is missing
 * or of the wrong type with 400, a request body of a content type the endpoint does not read with
 * 415, a request that accepts neither JSON nor XML with 406 before the endpoint runs, and a
 * privilege that {@link org.openmrs.api.context.Context#requirePrivilege(String)} refuses with 401
 * or 403, as for an {@link APIAuthenticationException}. BaseRestController would answer each of
 * these with 500. Each handler that writes a body writes it in the JSON or XML the request accepts,
 * and in JSON when it accepts neither: Spring could not write the body in a type such a request
 * accepts, and the request would end in the web application's 500 page instead. It also holds the
 * request helpers the controllers share, which find a request's patient and location, read its
 * dates and months, and describe a user.
 */
public abstract class ActCoreRestController extends BaseRestController {
	
	/**
	 * How many months back a log reads when the request gives no start date, unless the log has a
	 * default of its own.
	 */
	static final int DEFAULT_MONTHS = 12;
	
	/** The JSON and XML types webservices.rest's message converters write a response in. */
	private static final List<MediaType> WRITTEN = Arrays.asList(MediaType.APPLICATION_JSON,
	    new MediaType("application", "*+json"), MediaType.APPLICATION_XML, MediaType.TEXT_XML,
	    new MediaType("application", "*+xml"));
	
	/**
	 * Refuses a request whose {@code Accept} admits neither JSON nor XML, or is not a list of media
	 * types, before the endpoint runs. Spring finds that it cannot write the response only after the
	 * endpoint has run, so a POST would otherwise do its work and then be answered with 406. A POST
	 * whose content type is neither JSON nor XML, or is missing, is left to the 415 or 400 with which
	 * the module's POST endpoints refuse such a body before they run, so that it is answered with that
	 * status in JSON rather than with 406.
	 *
	 * @param request the request
	 * @throws HttpMediaTypeNotAcceptableException when the request accepts neither JSON nor XML
	 */
	@ModelAttribute
	public void refuseAnAcceptWithoutJsonOrXml(HttpServletRequest request) throws HttpMediaTypeNotAcceptableException {
		if ("POST".equals(request.getMethod()) && !isJsonOrXml(request.getContentType())) {
			return;
		}
		if (!acceptsJsonOrXml(request)) {
			throw new HttpMediaTypeNotAcceptableException(WRITTEN);
		}
	}
	
	/**
	 * Answers a request parameter that is missing or of the wrong type with 400 and a message naming
	 * it.
	 *
	 * @param e the MethodArgumentTypeMismatchException or MissingServletRequestParameterException
	 * @param request the request
	 * @param response the response, whose status is set to 400
	 * @return an {@code error} holding the {@code message}, without a stack trace
	 */
	@ExceptionHandler({ MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class })
	@ResponseBody
	public SimpleObject badParameterHandler(Exception e, HttpServletRequest request, HttpServletResponse response) {
		response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
		jsonUnlessJsonOrXmlIsAccepted(request, response);
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
	 * Answers a request body of a content type the endpoint does not read with 415, in the JSON or XML
	 * the request accepts and in JSON when it accepts neither, and names JSON in the {@code Accept}
	 * header as the type the module's endpoints read.
	 *
	 * @param e the HttpMediaTypeNotSupportedException
	 * @param request the request
	 * @param response the response, whose status is set to 415
	 * @return an {@code error} holding the {@code message}, without a stack trace
	 */
	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	@ResponseBody
	public SimpleObject unsupportedMediaTypeHandler(HttpMediaTypeNotSupportedException e, HttpServletRequest request,
	        HttpServletResponse response) {
		response.setStatus(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
		jsonUnlessJsonOrXmlIsAccepted(request, response);
		response.setHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
		return new SimpleObject().add("error", new SimpleObject().add("message", e.getMessage()));
	}
	
	/**
	 * Answers a request that accepts no type the endpoint gives with 406 and no body, since the request
	 * accepts no type to write one in.
	 *
	 * @param response the response, whose status is set to 406
	 */
	@ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
	public void notAcceptableHandler(HttpServletResponse response) {
		response.setStatus(HttpServletResponse.SC_NOT_ACCEPTABLE);
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
	 * Answers as BaseRestController does, in JSON when the request accepts neither JSON nor XML.
	 *
	 * @see BaseRestController#apiAuthenticationExceptionHandler(Exception, HttpServletRequest,
	 *      HttpServletResponse)
	 */
	@Override
	@ExceptionHandler(APIAuthenticationException.class)
	@ResponseBody
	public SimpleObject apiAuthenticationExceptionHandler(Exception ex, HttpServletRequest request,
	        HttpServletResponse response) throws Exception {
		jsonUnlessJsonOrXmlIsAccepted(request, response);
		return super.apiAuthenticationExceptionHandler(ex, request, response);
	}
	
	/**
	 * Answers as BaseRestController does, in JSON when the request accepts neither JSON nor XML.
	 *
	 * @see BaseRestController#validationExceptionHandler(ValidationException, HttpServletRequest,
	 *      HttpServletResponse)
	 */
	@Override
	@ExceptionHandler(ValidationException.class)
	@ResponseBody
	public SimpleObject validationExceptionHandler(ValidationException validationException, HttpServletRequest request,
	        HttpServletResponse response) {
		jsonUnlessJsonOrXmlIsAccepted(request, response);
		return super.validationExceptionHandler(validationException, request, response);
	}
	
	/**
	 * Answers as BaseRestController does, in JSON when the request accepts neither JSON nor XML.
	 *
	 * @see BaseRestController#conversionExceptionHandler(ConversionException, HttpServletRequest,
	 *      HttpServletResponse)
	 */
	@Override
	@ExceptionHandler(ConversionException.class)
	@ResponseBody
	public SimpleObject conversionExceptionHandler(ConversionException conversionException, HttpServletRequest request,
	        HttpServletResponse response) {
		jsonUnlessJsonOrXmlIsAccepted(request, response);
		return super.conversionExceptionHandler(conversionException, request, response);
	}
	
	/**
	 * Answers as BaseRestController does, in JSON when the request accepts neither JSON nor XML.
	 *
	 * @see BaseRestController#httpMessageNotReadableExceptionHandler(HttpMessageNotReadableException,
	 *      HttpServletRequest, HttpServletResponse)
	 */
	@Override
	@ExceptionHandler(HttpMessageNotReadableException.class)
	@ResponseBody
	public SimpleObject httpMessageNotReadableExceptionHandler(
	        HttpMessageNotReadableException httpMessageNotReadableException, HttpServletRequest request,
	        HttpServletResponse response) {
		jsonUnlessJsonOrXmlIsAccepted(request, response);
		return super.httpMessageNotReadableExceptionHandler(httpMessageNotReadableException, request, response);
	}
	
	/**
	 * Answers as BaseRestController does, in JSON when the request accepts neither JSON nor XML.
	 *
	 * @see BaseRestController#handleException(Exception, HttpServletRequest, HttpServletResponse)
	 */
	@Override
	@ExceptionHandler(Exception.class)
	@ResponseBody
	public SimpleObject handleException(Exception ex, HttpServletRequest request, HttpServletResponse response)
	        throws Exception {
		jsonUnlessJsonOrXmlIsAccepted(request, response);
		return super.handleException(ex, request, response);
	}
	
	/**
	 * Sets the response's type to JSON when the request accepts neither JSON nor XML, so that an error
	 * body can be written. Otherwise the type is left for Spring to choose from what the request
	 * accepts.
	 *
	 * @param request the request
	 * @param response the response
	 */
	static void jsonUnlessJsonOrXmlIsAccepted(HttpServletRequest request, HttpServletResponse response) {
		if (!acceptsJsonOrXml(request)) {
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		}
	}
	
	/**
	 * Tells whether a request's {@code Accept} admits JSON or XML.
	 *
	 * @param request the request
	 * @return true when the request has no {@code Accept} or it admits JSON or XML; false when it
	 *         admits neither or is not a list of media types
	 */
	private static boolean acceptsJsonOrXml(HttpServletRequest request) {
		List<MediaType> accepted;
		try {
			accepted = MediaType.parseMediaTypes(Collections.list(request.getHeaders(HttpHeaders.ACCEPT)));
		}
		catch (InvalidMediaTypeException e) {
			return false;
		}
		if (accepted.isEmpty()) {
			return true;
		}
		for (MediaType type : accepted) {
			for (MediaType written : WRITTEN) {
				if (type.isCompatibleWith(written)) {
					return true;
				}
			}
		}
		return false;
	}
	
	/**
	 * Tells whether a content type is JSON or XML.
	 *
	 * @param contentType the content type, or null
	 * @return false when it is blank, not a media type, or neither JSON nor XML
	 */
	private static boolean isJsonOrXml(String contentType) {
		if (StringUtils.isBlank(contentType)) {
			return false;
		}
		MediaType type;
		try {
			type = MediaType.parseMediaType(contentType);
		}
		catch (InvalidMediaTypeException e) {
			return false;
		}
		for (MediaType written : WRITTEN) {
			if (written.includes(type)) {
				return true;
			}
		}
		return false;
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
