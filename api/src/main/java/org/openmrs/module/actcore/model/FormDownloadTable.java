/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.model;

import java.util.List;

/**
 * A download of saved forms as a CSV's header row and rows, with whether it gave identifiers,
 * whether it is of research data only, and the labels of the fields withheld.
 *
 * @see org.openmrs.module.actcore.api.FormDownloadService
 */
public class FormDownloadTable {
	
	private final List<String> headers;
	
	private final List<List<String>> rows;
	
	private final boolean identifiers;
	
	private final boolean researchOnly;
	
	private final List<String> withheld;
	
	public FormDownloadTable(List<String> headers, List<List<String>> rows, boolean identifiers, boolean researchOnly,
	    List<String> withheld) {
		this.headers = headers;
		this.rows = rows;
		this.identifiers = identifiers;
		this.researchOnly = researchOnly;
		this.withheld = withheld;
	}
	
	/** @return the CSV's column headers, in order */
	public List<String> getHeaders() {
		return headers;
	}
	
	/** @return the CSV's rows, each a cell for each header, empty where there is no value */
	public List<List<String>> getRows() {
		return rows;
	}
	
	/** @return whether the download gave a patient field or a question that identifies the patient */
	public boolean isIdentifiers() {
		return identifiers;
	}
	
	/** @return whether the download kept only the patients who opted in to research */
	public boolean isResearchOnly() {
		return researchOnly;
	}
	
	/**
	 * @return the labels of the patient fields and then the questions asked for that were left out
	 *         because they identify the patient; a row per patient prefixes each question's label with
	 *         its form's name
	 */
	public List<String> getWithheld() {
		return withheld;
	}
}
