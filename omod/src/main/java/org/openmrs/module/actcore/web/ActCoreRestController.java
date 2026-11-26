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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor;

/**
 * The base class of the module's REST controllers. It answers a request parameter that is missing
 * or of the wrong type with 400, a request body of a content type the endpoint does not read with
 * 415, a request that accepts neither JSON nor XML with 406 before the endpoint runs, and a
 * privilege that {@link org.openmrs.api.context.Context#requirePrivilege(String)} refuses with 401
 * or 403, as for an {@link APIAuthenticationException}. BaseRestController would answer each of
 * these with 500. Each handler that writes a body writes it in the JSON or XML the request accepts,
 * and in JSON when it accepts neither: Spring could not write the body in a type such a request
 * accepts, and the request would end in the web application's 500 page instead. The handlers write
 * their bodies themselves and give Spring nothing to write, so that whichever exception resolver
 * runs them finishes the answer. A resolver with only a JSON converter, as the metadataexport
 * module registers ahead of webservices.rest's, cannot write an XML body: it logs a warning with a
 * stack trace and leaves the exception to the next resolver, which runs the handler again. It also
 * holds the request helpers the controllers share, which find a request's patient and location,
 * read its dates and months, and describe a user.
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
	
	/** The type of an error body, from which Spring chooses the converters that can write one. */
	private static final MethodParameter ERROR_BODY = new MethodParameter(
	        ReflectionUtils.findMethod(ActCoreRestController.class, "error", String.class), -1);
	
	/** The JSON converter webservices.rest's exception resolver writes with. */
	@Autowired
	@Qualifier("jsonHttpMessageConverter")
	private HttpMessageConverter<?> jsonConverter;
	
	/** The XML converter webservices.rest's exception resolver writes with. */
	@Autowired
	@Qualifier("xmlMarshallingHttpMessageConverter")
	private HttpMessageConverter<?> xmlConverter;
	
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
	 * @param response the response, whose status is set to 400 and which gets an {@code error} holding
	 *            the {@code message}, without a stack trace
	 */
	@ExceptionHandler({ MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class })
	public void badParameterHandler(Exception e, HttpServletRequest request, HttpServletResponse response) {
		response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
		String message;
		if (e instanceof MethodArgumentTypeMismatchException) {
			MethodArgumentTypeMismatchException mismatch = (MethodArgumentTypeMismatchException) e;
			message = mismatch.getName() + " cannot be " + mismatch.getValue();
		} else {
			message = ((MissingServletRequestParameterException) e).getParameterName() + " is required";
		}
		// No stack trace or code: the caller sent the request wrong, and the server has nothing to show.
		writeError(error(message), request, response);
	}
	
	/**
	 * Answers a request body of a content type the endpoint does not read with 415, in the JSON or XML
	 * the request accepts and in JSON when it accepts neither, and names JSON in the {@code Accept}
	 * header as the type the module's endpoints read.
	 *
	 * @param e the HttpMediaTypeNotSupportedException
	 * @param request the request
	 * @param response the response, whose status is set to 415 and which gets an {@code error} holding
	 *            the {@code message}, without a stack trace
	 */
	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	public void unsupportedMediaTypeHandler(HttpMediaTypeNotSupportedException e, HttpServletRequest request,
	        HttpServletResponse response) {
		response.setStatus(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
		response.setHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
		writeError(error(e.getMessage()), request, response);
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
	 * @param response the response, whose status is set to 401 or 403 and which gets an {@code error}
	 *            holding the {@code message} and the exception's detail
	 * @throws Exception if BaseRestController's handler fails
	 */
	@ExceptionHandler(ContextAuthenticationException.class)
	public void privilegeRequiredHandler(ContextAuthenticationException e, HttpServletRequest request,
	        HttpServletResponse response) throws Exception {
		apiAuthenticationExceptionHandler(e, request, response);
	}
	
	/**
	 * Answers as BaseRestController does, but writes the body itself, in JSON when the request accepts
	 * neither JSON nor XML, and returns null.
	 *
	 * @see BaseRestController#apiAuthenticationExceptionHandler(Exception, HttpServletRequest,
	 *      HttpServletResponse)
	 */
	@Override
	@ExceptionHandler(APIAuthenticationException.class)
	public SimpleObject apiAuthenticationExceptionHandler(Exception ex, HttpServletRequest request,
	        HttpServletResponse response) throws Exception {
		writeError(super.apiAuthenticationExceptionHandler(ex, request, response), request, response);
		return null;
	}
	
	/**
	 * Answers as BaseRestController does, but writes the body itself, in JSON when the request accepts
	 * neither JSON nor XML, and returns null.
	 *
	 * @see BaseRestController#validationExceptionHandler(ValidationException, HttpServletRequest,
	 *      HttpServletResponse)
	 */
	@Override
	@ExceptionHandler(ValidationException.class)
	public SimpleObject validationExceptionHandler(ValidationException validationException, HttpServletRequest request,
	        HttpServletResponse response) {
		writeError(super.validationExceptionHandler(validationException, request, response), request, response);
		return null;
	}
	
	/**
	 * Answers as BaseRestController does, but writes the body itself, in JSON when the request accepts
	 * neither JSON nor XML, and returns null.
	 *
	 * @see BaseRestController#conversionExceptionHandler(ConversionException, HttpServletRequest,
	 *      HttpServletResponse)
	 */
	@Override
	@ExceptionHandler(ConversionException.class)
	public SimpleObject conversionExceptionHandler(ConversionException conversionException, HttpServletRequest request,
	        HttpServletResponse response) {
		writeError(super.conversionExceptionHandler(conversionException, request, response), request, response);
		return null;
	}
	
	/**
	 * Answers as BaseRestController does, but writes the body itself, in JSON when the request accepts
	 * neither JSON nor XML, and returns null.
	 *
	 * @see BaseRestController#httpMessageNotReadableExceptionHandler(HttpMessageNotReadableException,
	 *      HttpServletRequest, HttpServletResponse)
	 */
	@Override
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public SimpleObject httpMessageNotReadableExceptionHandler(
	        HttpMessageNotReadableException httpMessageNotReadableException, HttpServletRequest request,
	        HttpServletResponse response) {
		writeError(super.httpMessageNotReadableExceptionHandler(httpMessageNotReadableException, request, response), request,
		    response);
		return null;
	}
	
	/**
	 * Answers as BaseRestController does, but writes the body itself, in JSON when the request accepts
	 * neither JSON nor XML, and returns null.
	 *
	 * @see BaseRestController#handleException(Exception, HttpServletRequest, HttpServletResponse)
	 */
	@Override
	@ExceptionHandler(Exception.class)
	public SimpleObject handleException(Exception ex, HttpServletRequest request, HttpServletResponse response)
	        throws Exception {
		SimpleObject body = super.handleException(ex, request, response);
		// Null when BaseRestController handed the exception to apiAuthenticationExceptionHandler, which wrote it.
		if (body != null) {
			writeError(body, request, response);
		}
		return null;
	}
	
	/**
	 * Builds an error body holding a message.
	 *
	 * @param message the message
	 * @return an {@code error} holding the {@code message}
	 */
	static SimpleObject error(String message) {
		return new SimpleObject().add("error", new SimpleObject().add("message", message));
	}
	
	/**
	 * Writes an error body in the JSON or XML the request accepts, choosing between them as Spring
	 * does, with the converters webservices.rest's exception resolver writes with. When the request
	 * accepts neither, it writes JSON, which Spring would refuse to.
	 *
	 * @param body the error body
	 * @param request the request
	 * @param response the response, whose status the handler has set
	 * @throws HttpMessageNotWritableException when the body cannot be written
	 */
	void writeError(SimpleObject body, HttpServletRequest request, HttpServletResponse response) {
		if (!acceptsJsonOrXml(request)) {
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		}
		try {
			new RequestResponseBodyMethodProcessor(Arrays.asList(jsonConverter, xmlConverter)).handleReturnValue(body,
			    ERROR_BODY, new ModelAndViewContainer(), new ServletWebRequest(request, response));
		}
		catch (RuntimeException e) {
			throw e;
		}
		catch (Exception e) {
			throw new HttpMessageNotWritableException("Could not write the error body", e);
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
