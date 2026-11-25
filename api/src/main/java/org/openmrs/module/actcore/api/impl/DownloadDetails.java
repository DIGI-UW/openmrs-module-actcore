/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.api.impl;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The shape of a download's detail, which the download log holds a detail to both when it records
 * one and when it lists one. Every detail recorded since 1.0.0 is a JSON object that has
 * {@code fields} and {@code withheld}, each a list of labels; {@code startDate} and
 * {@code endDate}, each a string or null; {@code rows}, a number; and {@code identifiers} and
 * {@code researchOnly}, each true or false. A detail may also have {@code list}, a string;
 * {@code forms}, a list of objects; {@code rowPerPatient}, true or false; {@code filters}, an
 * object; and {@code patient}, a list of strings. It may have other keys as well.
 */
final class DownloadDetails {
	
	private DownloadDetails() {
	}
	
	/**
	 * Reads a recorded detail.
	 *
	 * @param json the detail as recorded
	 * @return the detail, or null when it is not a JSON object of the shape a download records
	 */
	static Map<?, ?> read(String json) {
		Map<?, ?> detail;
		try {
			detail = new ObjectMapper().readValue(json, Map.class);
		}
		catch (IOException e) {
			return null;
		}
		return detail != null && hasShape(detail) ? detail : null;
	}
	
	private static boolean hasShape(Map<?, ?> detail) {
		return strings(detail.get("fields")) && strings(detail.get("withheld")) && stringOrNull(detail, "startDate")
		        && stringOrNull(detail, "endDate") && detail.get("rows") instanceof Number
		        && detail.get("identifiers") instanceof Boolean && detail.get("researchOnly") instanceof Boolean
		        && (!detail.containsKey("list") || detail.get("list") instanceof String)
		        && (!detail.containsKey("forms") || objects(detail.get("forms")))
		        && (!detail.containsKey("rowPerPatient") || detail.get("rowPerPatient") instanceof Boolean)
		        && (!detail.containsKey("filters") || detail.get("filters") instanceof Map)
		        && (!detail.containsKey("patient") || strings(detail.get("patient")));
	}
	
	private static boolean stringOrNull(Map<?, ?> detail, String key) {
		return detail.containsKey(key) && (detail.get(key) == null || detail.get(key) instanceof String);
	}
	
	private static boolean strings(Object value) {
		return value instanceof List && ((List<?>) value).stream().allMatch(item -> item instanceof String);
	}
	
	private static boolean objects(Object value) {
		return value instanceof List && ((List<?>) value).stream().allMatch(item -> item instanceof Map);
	}
}
