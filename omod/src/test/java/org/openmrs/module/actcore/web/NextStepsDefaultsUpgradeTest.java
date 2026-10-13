/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.web;

import static org.junit.Assert.assertEquals;

import java.io.File;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathFactory;

import org.junit.Test;
import org.w3c.dom.Document;

public class NextStepsDefaultsUpgradeTest {
	
	private static final String CHANGESET = "//changeSet[@id='actcore-2026-10-13-next-steps-patient-information']/update";
	
	private static final String PATIENT_INFO = ",patientInfo:ffdc883f-f99f-496a-83f9-2c3c581e1af0";
	
	private final XPath xpath = XPathFactory.newInstance().newXPath();
	
	@Test
	public void upgradeTurnsTheEarlierEncounterTypesDefaultIntoTheCurrentOne() throws Exception {
		Document liquibase = parse("target/classes/liquibase.xml");
		String current = xpath
		        .evaluate("//globalProperty[normalize-space(property)='actcore.nextSteps.encounterTypes']/defaultValue",
		            parse("target/classes/config.xml"))
		        .trim();
		
		assertEquals(current, xpath.evaluate(CHANGESET + "/column[@name='property_value']/@value", liquibase));
		assertEquals(
		    "property = 'actcore.nextSteps.encounterTypes' AND property_value = '" + current.replace(PATIENT_INFO, "") + "'",
		    xpath.evaluate(CHANGESET + "/where", liquibase).trim());
	}
	
	private static Document parse(String path) throws Exception {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
		return factory.newDocumentBuilder().parse(new File(path));
	}
}
