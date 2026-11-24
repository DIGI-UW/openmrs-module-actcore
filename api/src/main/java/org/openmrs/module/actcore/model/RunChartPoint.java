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

import java.time.LocalDate;

/** One point of a run chart metric's series. */
public class RunChartPoint {
	
	/**
	 * The day the figure is as of: the last day of the point's period, or the end of the range for the
	 * last point.
	 */
	public final LocalDate date;
	
	/**
	 * The figure charted: the count for a count, or the percent rounded to one decimal for a percent.
	 * It is null for a percent whose denominator is zero.
	 */
	public final Double value;
	
	/** The count for a count, or the numerator for a percent. */
	public final Long count;
	
	/** The denominator for a percent, or null for a count. */
	public final Long total;
	
	/**
	 * Creates a point that gives only its figure.
	 *
	 * @param date the day the figure is as of
	 * @param value the figure, or null
	 */
	public RunChartPoint(LocalDate date, Double value) {
		this(date, value, null, null);
	}
	
	/**
	 * Creates a point.
	 *
	 * @param date the day the figure is as of
	 * @param value the figure, or null
	 * @param count the count, or the numerator for a percent
	 * @param total the denominator for a percent, or null for a count
	 */
	public RunChartPoint(LocalDate date, Double value, Long count, Long total) {
		this.date = date;
		this.value = value;
		this.count = count;
		this.total = total;
	}
}
