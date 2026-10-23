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
	
	private final boolean onHold;
	
	private final String referralType;
	
	public NextStep(String key, String form, String title, String reason, boolean isNew, boolean done, boolean onHold) {
		this(key, form, title, reason, isNew, done, onHold, null);
	}
	
	public NextStep(String key, String form, String title, String reason, boolean isNew, boolean done, boolean onHold,
	    String referralType) {
		this.key = key;
		this.form = form;
		this.title = title;
		this.reason = reason;
		this.isNew = isNew;
		this.done = done;
		this.onHold = onHold;
		this.referralType = referralType;
	}
	
	public String getKey() {
		return key;
	}
	
	/**
	 * The form's uuid, or null for a step no form records, such as Refer to clinician, or one on hold.
	 */
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
	
	/**
	 * Whether the step is listed only to say why it waits, such as a BPG held until a consultation
	 * reviews an anaphylaxis: it has no form to record.
	 */
	public boolean isOnHold() {
		return onHold;
	}
	
	/**
	 * The uuid of the encounter type that records the referral, for a Refer to clinician step; null for
	 * any other step, or when no type is mapped to the referral.
	 */
	public String getReferralType() {
		return referralType;
	}
}
