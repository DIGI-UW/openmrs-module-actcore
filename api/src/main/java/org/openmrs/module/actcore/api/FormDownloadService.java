/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.api;

import java.time.LocalDate;
import java.util.List;

import org.openmrs.annotation.Authorized;
import org.openmrs.api.OpenmrsService;
import org.openmrs.module.actcore.ActCoreConstants;
import org.openmrs.module.actcore.ActCorePrivileges;
import org.openmrs.module.actcore.model.FormDownloadChoice;
import org.openmrs.module.actcore.model.FormDownloadFilters;
import org.openmrs.module.actcore.model.FormDownloadTable;

/**
 * The service behind ACT 2.0's Download, which exports saved forms as CSV. It decides what a
 * download may give from the user's privileges, builds the table and records the download in the
 * download log before handing it over. A table has either a row for each save of one form, or a row
 * for each patient across several forms. Each row starts with the patient's ACT ID, followed by the
 * patient fields chosen and then the forms' columns. A download gives the patient fields and
 * questions that identify a patient only to a user who holds both
 * {@link ActCorePrivileges#DOWNLOAD_DATA} and {@link ActCorePrivileges#DOWNLOAD_IDENTIFIERS}, and a
 * download by a user who holds only {@link ActCorePrivileges#DOWNLOAD_RESEARCH_DATA} is of the
 * patients who opted in to research and never gives them.
 */
public interface FormDownloadService extends OpenmrsService {
	
	/**
	 * Builds a download of the forms chosen and records it in the download log, as
	 * {@link DownloadLogService#recordDownload(Integer, String)} does, before returning it, so that no
	 * download goes unrecorded. A download of several forms always has a row per patient.
	 *
	 * @param choices the forms and the questions chosen of each; at least one, and no form twice
	 * @param rowPerPatient whether to give a row per patient for one form rather than a row per save
	 * @param filters the status and clinics to keep patients by
	 * @param from the first day, or null for no start
	 * @param to the last day, or null for no end
	 * @param patientFields the keys of the {@link ActCoreConstants#DOWNLOAD_PATIENT_FIELDS} to give, or
	 *            null for sex and age, and every field when the download may give identifiers
	 * @return the headers and rows, whether identifiers were given, whether it is research only, and
	 *         the labels of the fields withheld
	 * @throws org.openmrs.api.APIAuthenticationException when the user holds neither
	 *             {@link ActCorePrivileges#DOWNLOAD_DATA} nor
	 *             {@link ActCorePrivileges#DOWNLOAD_RESEARCH_DATA}; lacks Get Forms, Get Concepts, Get
	 *             Encounters or Get Observations, which the forms and their answers are read with; or,
	 *             with a status filter, lacks Get Patient Programs
	 * @throws org.openmrs.api.ValidationException when no form is chosen, a form is chosen twice, or
	 *             the record of the download would be longer than
	 *             {@link ActCoreConstants#MAX_DOWNLOAD_DETAIL_BYTES} bytes of UTF-8
	 * @throws org.openmrs.api.APIException when the record of the download cannot be written as JSON
	 */
	@Authorized({ ActCorePrivileges.DOWNLOAD_DATA, ActCorePrivileges.DOWNLOAD_RESEARCH_DATA })
	FormDownloadTable download(List<FormDownloadChoice> choices, boolean rowPerPatient, FormDownloadFilters filters,
	        LocalDate from, LocalDate to, List<String> patientFields);
}
