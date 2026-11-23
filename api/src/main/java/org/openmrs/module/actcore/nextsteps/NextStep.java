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
 * One step the chart suggests for a patient at this visit, such as a form to record. It says which
 * form to open, why the step is suggested, and whether it is new, already done, on hold or a
 * patient safety alert.
 */
public class NextStep {
	
	private final String key;
	
	private final String form;
	
	private final StepReason reason;
	
	private final boolean isNew;
	
	private final boolean done;
	
	private final boolean onHold;
	
	private final String referralType;
	
	private final boolean alert;
	
	/**
	 * Creates a step.
	 *
	 * @param key the step's key, such as {@code bpg}, which also names its title message
	 *            {@code actcore.nextStep.title.<key>}
	 * @param form the uuid of the form that records the step, or null when no form records it
	 * @param reason why the step is suggested
	 * @param isNew whether a finding still to be acted on raised the step
	 * @param done whether the step was done at this visit
	 * @param onHold whether the step is listed only to say why it waits
	 * @param referralType the uuid of the encounter type that records a referral, or null
	 * @param alert whether the step is a patient safety alert
	 */
	public NextStep(String key, String form, StepReason reason, boolean isNew, boolean done, boolean onHold,
	    String referralType, boolean alert) {
		this.key = key;
		this.form = form;
		this.reason = reason;
		this.isNew = isNew;
		this.done = done;
		this.onHold = onHold;
		this.referralType = referralType;
		this.alert = alert;
	}
	
	public String getKey() {
		return key;
	}
	
	/**
	 * Gets the uuid of the form that records the step. It is null for a step that no form records, such
	 * as Refer to clinician, and for a step on hold.
	 */
	public String getForm() {
		return form;
	}
	
	/**
	 * Gets the code of the step's title message, {@code actcore.nextStep.title.<key>}.
	 *
	 * @return the title's message code
	 */
	public String getTitleCode() {
		return "actcore.nextStep.title." + key;
	}
	
	/**
	 * Gets why the step is suggested, as a message code and the values that fill it in.
	 *
	 * @return the reason
	 */
	public StepReason getReason() {
		return reason;
	}
	
	/**
	 * Tells whether a finding that is still to be acted on raised the step, such as a new echo, or an
	 * anaphylaxis or low adherence that no consultation has reviewed.
	 */
	public boolean isNew() {
		return isNew;
	}
	
	public boolean isDone() {
		return done;
	}
	
	/**
	 * Tells whether the step is listed only to say why it waits, as for a BPG dose held until a
	 * consultation reviews an anaphylaxis. A step on hold has no form to record.
	 */
	public boolean isOnHold() {
		return onHold;
	}
	
	/**
	 * Gets the uuid of the encounter type that records the referral, for a Refer to clinician step. It
	 * is null for any other step, and when no encounter type is mapped to the referral.
	 */
	public String getReferralType() {
		return referralType;
	}
	
	/**
	 * Tells whether the step is a patient safety alert. The alerts are the review of an anaphylaxis
	 * after BPG that no consultation has reviewed yet, and the BPG dose that the anaphylaxis holds
	 * back.
	 */
	public boolean isAlert() {
		return alert;
	}
}
