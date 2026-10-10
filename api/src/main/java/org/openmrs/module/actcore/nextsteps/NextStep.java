/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.nextsteps;

/**
 * One thing to do for a patient at this visit: the form to open, why, and whether it is already
 * done.
 */
public class NextStep {
	
	private final String key;
	
	private final String form;
	
	private final String title;
	
	private final String reason;
	
	private final boolean isNew;
	
	private final boolean done;
	
	public NextStep(String key, String form, String title, String reason, boolean isNew, boolean done) {
		this.key = key;
		this.form = form;
		this.title = title;
		this.reason = reason;
		this.isNew = isNew;
		this.done = done;
	}
	
	public String getKey() {
		return key;
	}
	
	/** The form's uuid, or null for a step no form records, such as Refer to clinician. */
	public String getForm() {
		return form;
	}
	
	public String getTitle() {
		return title;
	}
	
	public String getReason() {
		return reason;
	}
	
	/**
	 * Whether a finding still to act on raised this step: a new echo, or an unreviewed anaphylaxis or
	 * low adherence.
	 */
	public boolean isNew() {
		return isNew;
	}
	
	public boolean isDone() {
		return done;
	}
}
