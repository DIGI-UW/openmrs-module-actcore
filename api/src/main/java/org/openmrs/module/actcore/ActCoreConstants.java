/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore;

/**
 * The names that ACT Core shares with its config.xml, its database and the modules it depends on:
 * the global properties it declares, the one core keeps of whether it is started, its user
 * property, its tables, and the privileges of other modules that it checks or proxies, and the page
 * sizes of its logs. Each is spelled here once, and the code refers to it through these constants.
 * The config.xml describes each global property. ACT Core's own privileges are in
 * {@link ActCorePrivileges}.
 */
public final class ActCoreConstants {
	
	public static final String GP_REGISTRY_PROGRAM = "actcore.registryProgram";
	
	public static final String GP_STUDY_TEMPLATE_PROGRAM = "actcore.studyTemplateProgram";
	
	public static final String GP_STUDIES_CONSENT_ATTRIBUTE_TYPE = "actcore.studies.consentAttributeType";
	
	public static final String GP_STUDIES_OPT_IN_ATTRIBUTE_TYPE = "actcore.studies.optInAttributeType";
	
	public static final String GP_STUDIES_YES_CONCEPT = "actcore.studies.yesConcept";
	
	public static final String GP_STUDIES_PARTICIPANT_ID_ATTRIBUTE_TYPE = "actcore.studies.participantIdAttributeType";
	
	public static final String GP_REGISTRY_DEATH_STATE_CONCEPT = "actcore.registry.deathStateConcept";
	
	public static final String GP_STUDIES_DEATH_STATE_CONCEPT = "actcore.studies.deathStateConcept";
	
	public static final String GP_USERS_SITE_ADMINISTRATOR_ROLES = "actcore.users.siteAdministratorRoles";
	
	public static final String GP_USERS_INSTANCE_ADMINISTRATOR_ROLES = "actcore.users.instanceAdministratorRoles";
	
	public static final String GP_USERS_GLOBAL_ADMINISTRATOR_ROLES = "actcore.users.globalAdministratorRoles";
	
	public static final String GP_LIST_FLAG_TAG = "actcore.listFlagTag";
	
	public static final String GP_DISMISS_FLAG_TAG = "actcore.dismissFlagTag";
	
	public static final String GP_LIST_COHORT_TYPE = "actcore.listCohortType";
	
	public static final String GP_REFRESH_LAST_FINISHED = "actcore.refresh.lastFinished";
	
	public static final String GP_ADHERENCE_PRESCRIPTION_CONCEPT = "actcore.adherence.prescriptionConcept";
	
	public static final String GP_ADHERENCE_INJECTION_INTERVALS = "actcore.adherence.injectionIntervals";
	
	public static final String GP_ADHERENCE_NO_PROPHYLAXIS_ANSWERS = "actcore.adherence.noProphylaxisAnswers";
	
	public static final String GP_ADHERENCE_DATE_STARTED_CONCEPT = "actcore.adherence.dateStartedConcept";
	
	public static final String GP_ADHERENCE_DATE_STOPPED_CONCEPT = "actcore.adherence.dateStoppedConcept";
	
	public static final String GP_ADHERENCE_INJECTION_DATE_CONCEPT = "actcore.adherence.injectionDateConcept";
	
	public static final String GP_ADHERENCE_ESTIMATE_CONCEPT = "actcore.adherence.estimateConcept";
	
	public static final String GP_ADHERENCE_ESTIMATE_DATE_CONCEPT = "actcore.adherence.estimateDateConcept";
	
	public static final String GP_ADHERENCE_ORAL_FIRST_DUE_DAYS = "actcore.adherence.oralFirstDueDays";
	
	public static final String GP_ADHERENCE_PRESCRIPTION_DURATION_CONCEPT = "actcore.adherence.prescriptionDurationConcept";
	
	public static final String GP_ADHERENCE_PRESCRIPTION_DURATIONS = "actcore.adherence.prescriptionDurations";
	
	public static final String GP_ADHERENCE_CONSULTATION_DATE_CONCEPT = "actcore.adherence.consultationDateConcept";
	
	public static final String GP_ADHERENCE_DATA_ENTRY_CONCEPT = "actcore.adherence.dataEntryConcept";
	
	public static final String GP_ADHERENCE_DATA_ENTRY_INTERVALS = "actcore.adherence.dataEntryIntervals";
	
	public static final String GP_NEXT_STEPS_ENCOUNTER_TYPES = "actcore.nextSteps.encounterTypes";
	
	public static final String GP_NEXT_STEPS_FLAGS = "actcore.nextSteps.flags";
	
	public static final String GP_NEXT_STEPS_DOSE_WITHIN_DAYS = "actcore.nextSteps.doseWithinDays";
	
	public static final String GP_NEXT_STEPS_ECHO_MONTHS = "actcore.nextSteps.echoMonths";
	
	public static final String GP_NEXT_STEPS_ADHERENCE_BELOW = "actcore.nextSteps.adherenceBelow";
	
	public static final String GP_NEXT_STEPS_DOSE_AFTER_EARLIER_STEPS = "actcore.nextSteps.doseAfterEarlierSteps";
	
	public static final String GP_NEXT_STEPS_ECHO_DATE_CONCEPT = "actcore.nextSteps.echoDateConcept";
	
	public static final String GP_NEXT_STEPS_ANAPHYLAXIS_ANSWER = "actcore.nextSteps.anaphylaxisAnswer";
	
	public static final String GP_NEXT_STEPS_NEXT_INR_DATE_CONCEPT = "actcore.nextSteps.nextInrDateConcept";
	
	public static final String GP_CONSULTATION_NEXT_AMOUNT_CONCEPT = "actcore.consultation.nextAmountConcept";
	
	public static final String GP_CONSULTATION_NEXT_PERIOD_CONCEPT = "actcore.consultation.nextPeriodConcept";
	
	public static final String GP_DOWNLOAD_IDENTIFYING_IDENTIFIER_TYPES = "actcore.download.identifyingIdentifierTypes";
	
	public static final String GP_DOWNLOAD_IDENTIFYING_ATTRIBUTE_TYPES = "actcore.download.identifyingAttributeTypes";
	
	public static final String GP_DOWNLOAD_IDENTIFYING_CONCEPTS = "actcore.download.identifyingConcepts";
	
	public static final String GP_LOGS_CLINIC_ATTRIBUTE_TYPES = "actcore.logs.clinicAttributeTypes";
	
	public static final String GP_RUN_CHARTS_REPORT = "actcore.runCharts.report";
	
	public static final String GP_FORMS_FEMALE_ONLY = "actcore.forms.femaleOnly";
	
	public static final String GP_TASKS_OFF_WHILE_STOPPED = "actcore.tasksOffWhileStopped";
	
	/**
	 * The global property core keeps of whether ACT Core is started. Core sets it to false when an
	 * administrator stops the module or the module fails to start, and leaves it as it is when OpenMRS
	 * shuts down.
	 */
	public static final String MODULE_STARTED_PROPERTY = "actcore.started";
	
	/**
	 * The name of the user property that holds the user's clinics, as location uuids separated by
	 * commas.
	 */
	public static final String USER_PROPERTY_CLINICS = "actcore.clinics";
	
	/**
	 * The table holding one row per patient with a prescription: their regimen, adherence, last
	 * injection and next due date, as the latest refresh computed them.
	 */
	public static final String PROPHYLAXIS_ADHERENCE_TABLE = "actcore_prophylaxis_adherence";
	
	/**
	 * The table holding whether each injection was on time, timed as the chart times it, for reports
	 * that count on-time injections over a period.
	 */
	public static final String INJECTION_TIMING_TABLE = "actcore_injection_timing";
	
	/**
	 * The table that holds one row per flag raised for a patient, with when it was raised and any
	 * dismissal. A row is deleted once its flag lowers.
	 */
	public static final String RAISED_FLAG_TABLE = "actcore_raised_flag";
	
	/**
	 * The table that keeps each dismissal for good, since the refresh deletes a row of
	 * {@link #RAISED_FLAG_TABLE} once its flag lowers.
	 */
	public static final String FLAG_DISMISSAL_TABLE = "actcore_flag_dismissal";
	
	/** The table that holds one row per download recorded. */
	public static final String DOWNLOAD_LOG_TABLE = "actcore_download_log";
	
	/** The table that holds the screening counts, one row per saved clinic month. */
	public static final String SCREENING_COUNT_TABLE = "actcore_screening_count";
	
	/**
	 * The void reason given to a month's screening counts when a later save of the month replaces them.
	 */
	public static final String SCREENING_COUNT_REPLACED_REASON = "Replaced by a later save of the month";
	
	/** The audit log module's privilege for reading its audit logs, which the activity log needs. */
	public static final String AUDITLOGWEB_VIEW_AUDIT_LOGS = "View Audit Logs";
	
	/**
	 * The audit log module's privilege for reading its read audit, which the read log and the list of
	 * downloads need, since a download is a read of patients' data.
	 */
	public static final String AUDITLOGWEB_VIEW_READ_AUDIT_LOGS = "View Read Audit Logs";
	
	/** The cohort module's privilege for reading cohorts, which the patient lists of flags need. */
	public static final String COHORT_VIEW_COHORTS = "View Cohorts In Cohort Module";
	
	/** The cohort module's privilege for changing cohorts, which the patient lists of flags need. */
	public static final String COHORT_EDIT_COHORTS = "Edit Cohorts in Cohort Module";
	
	/**
	 * The number of entries an activity or read log page holds when the request does not say how many
	 * it wants.
	 */
	public static final int LOG_DEFAULT_LIMIT = 20;
	
	/** The most entries an activity or read log page holds, whatever the request asks for. */
	public static final int LOG_MAX_LIMIT = 100;
	
	/**
	 * The number of downloads a download log page holds when the filter does not say how many it wants.
	 */
	public static final int DOWNLOAD_DEFAULT_LIMIT = 100;
	
	/** The most downloads a download log page holds, whatever the filter asks for. */
	public static final int DOWNLOAD_MAX_LIMIT = 500;
	
	private ActCoreConstants() {
	}
}
