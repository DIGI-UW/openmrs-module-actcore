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

import org.openmrs.Concept;
import org.openmrs.Form;

/**
 * A form chosen for a download, with the questions chosen of it and the name that heads the form's
 * groups of columns in a row per patient.
 *
 * @see org.openmrs.module.actcore.api.FormDownloadService
 */
public class FormDownloadChoice {
	
	private final Form form;
	
	private final String name;
	
	private final List<Concept> concepts;
	
	private final List<String> labels;
	
	public FormDownloadChoice(Form form, String name, List<Concept> concepts, List<String> labels) {
		this.form = form;
		this.name = name;
		this.concepts = concepts;
		this.labels = labels;
	}
	
	/**
	 * @return the form whose saves the download gives; each of a patient's saves of the form fills one
	 *         group of the form's columns in a row per patient
	 */
	public Form getForm() {
		return form;
	}
	
	/** @return the name that prefixes the form's column headers, followed by the number of the save */
	public String getName() {
		return name;
	}
	
	/**
	 * @return the questions chosen from the form; each gives one column in every group of the form's
	 *         columns, unless it identifies the patient and the download withholds it
	 */
	public List<Concept> getConcepts() {
		return concepts;
	}
	
	/** @return each question's column label, in the order of the concepts */
	public List<String> getLabels() {
		return labels;
	}
}
