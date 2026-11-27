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

/**
 * One field that a save changed, as an activity log entry's expansion lists it. It gives the
 * field's name, its value before the save and its value after the save.
 */
public class ActivityField {
	
	/** The field's name as a reader would read it, such as "Visit date". */
	private final String name;
	
	/** The value before the save, or null when there was none. */
	private final String previous;
	
	/** The value after the save, or null when there is none. */
	private final String current;
	
	/**
	 * Creates a field.
	 *
	 * @param name the field's name
	 * @param previous the value before the save, or null
	 * @param current the value after the save, or null
	 */
	public ActivityField(String name, String previous, String current) {
		this.name = name;
		this.previous = previous;
		this.current = current;
	}
	
	public String getName() {
		return name;
	}
	
	public String getPrevious() {
		return previous;
	}
	
	public String getCurrent() {
		return current;
	}
}
