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

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Renders a next step's title and reason as English text from the module's messages.properties, so
 * that the api tests can keep asserting the text users read. The api tests cannot load that file
 * through core's message source: it ships at the root of the .omod, and putting it on the api's
 * test classpath would hide core's own messages.properties from the api tests.
 */
final class NextStepText {
	
	private static final String FILE = "../omod/src/main/resources/messages.properties";
	
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);
	
	private static Properties messages;
	
	private NextStepText() {
	}
	
	/**
	 * Gets the step's title in English.
	 *
	 * @param step the step
	 * @return the title, as the REST response sends it to an English-speaking user
	 */
	static String title(NextStep step) {
		return message(step.getTitleCode());
	}
	
	/**
	 * Gets a reason in English, with dates written dd-MMM-yyyy as NextStepsController writes them.
	 *
	 * @param reason the reason
	 * @return the reason's text
	 */
	static String text(StepReason reason) {
		List<Object> args = reason.getArgs();
		String pattern = message("actcore.nextStep.reason." + reason.getCode());
		if (args.isEmpty()) {
			return pattern;
		}
		Object[] shown = new Object[args.size()];
		for (int i = 0; i < shown.length; i++) {
			Object arg = args.get(i);
			shown[i] = arg instanceof LocalDate ? DAY.format((LocalDate) arg) : String.valueOf(arg);
		}
		return new MessageFormat(pattern, Locale.ENGLISH).format(shown);
	}
	
	private static String message(String code) {
		if (messages == null) {
			Properties loaded = new Properties();
			try (Reader in = new InputStreamReader(new FileInputStream(FILE), StandardCharsets.UTF_8)) {
				loaded.load(in);
			}
			catch (IOException e) {
				throw new IllegalStateException("Cannot read " + FILE, e);
			}
			messages = loaded;
		}
		String message = messages.getProperty(code);
		if (message == null) {
			throw new AssertionError("No English message for " + code);
		}
		return message;
	}
}
