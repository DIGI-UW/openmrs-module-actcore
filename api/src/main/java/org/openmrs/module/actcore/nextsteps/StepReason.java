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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Why a next step is suggested, as a message code and the values that fill it in, so that the text
 * can be shown in the user's language. The code names a message
 * {@code actcore.nextStep.reason.<code>} in the module's messages.properties.
 */
public final class StepReason {
	
	/**
	 * The code of the message, such as {@code overdue}, without the {@code actcore.nextStep.reason.}
	 * prefix.
	 */
	private final String code;
	
	/**
	 * The values the message fills in, in order: a {@link java.time.LocalDate} for a date, or a number.
	 */
	private final List<Object> args;
	
	private StepReason(String code, Object... args) {
		this.code = code;
		this.args = Collections.unmodifiableList(Arrays.asList(args));
	}
	
	/**
	 * Creates the reason for a message code.
	 *
	 * @param code the code of the message, without the {@code actcore.nextStep.reason.} prefix
	 * @param args the values the message fills in, in order
	 * @return the reason
	 */
	public static StepReason of(String code, Object... args) {
		return new StepReason(code, args);
	}
	
	/**
	 * Gets the code of the message, without the {@code actcore.nextStep.reason.} prefix.
	 *
	 * @return the code
	 */
	public String getCode() {
		return code;
	}
	
	/**
	 * Gets the values the message fills in, in order: a {@link java.time.LocalDate} for a date, or a
	 * number.
	 *
	 * @return the values, an empty list when the message takes none
	 */
	public List<Object> getArgs() {
		return args;
	}
	
	/**
	 * @see java.lang.Object#equals(java.lang.Object)
	 */
	@Override
	public boolean equals(Object other) {
		return other instanceof StepReason && code.equals(((StepReason) other).code)
		        && args.equals(((StepReason) other).args);
	}
	
	/**
	 * @see java.lang.Object#hashCode()
	 */
	@Override
	public int hashCode() {
		return 31 * code.hashCode() + args.hashCode();
	}
	
	/**
	 * @see java.lang.Object#toString()
	 */
	@Override
	public String toString() {
		return code + args;
	}
}
