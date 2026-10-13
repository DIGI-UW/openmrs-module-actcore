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

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class NextStepsDefaultsUpgradeTest {
	
	private static final String ENCOUNTER_TYPES = "actcore.nextSteps.encounterTypes";
	
	@Test
	public void theUpgradeWritesTheEncounterTypesDefault() throws Exception {
		Element update = upgrade();
		
		assertEquals(defaultOf(ENCOUNTER_TYPES), columnValue(update));
	}
	
	private static Element upgrade() throws Exception {
		NodeList changeSets = parse("target/classes/liquibase.xml").getElementsByTagName("changeSet");
		for (int i = 0; i < changeSets.getLength(); i++) {
			Element changeSet = (Element) changeSets.item(i);
			if ("actcore-2026-10-13-next-steps-patient-information".equals(changeSet.getAttribute("id"))) {
				return (Element) changeSet.getElementsByTagName("update").item(0);
			}
		}
		throw new AssertionError("no changeset updates " + ENCOUNTER_TYPES);
	}
	
	private static String columnValue(Element update) {
		NodeList columns = update.getElementsByTagName("column");
		for (int i = 0; i < columns.getLength(); i++) {
			Element column = (Element) columns.item(i);
			if ("property_value".equals(column.getAttribute("name"))) {
				return column.getAttribute("value");
			}
		}
		return null;
	}
	
	private static String defaultOf(String property) throws Exception {
		NodeList properties = parse("target/classes/config.xml").getElementsByTagName("globalProperty");
		for (int i = 0; i < properties.getLength(); i++) {
			Element each = (Element) properties.item(i);
			if (property.equals(each.getElementsByTagName("property").item(0).getTextContent().trim())) {
				return each.getElementsByTagName("defaultValue").item(0).getTextContent().trim();
			}
		}
		throw new AssertionError(property + " is not in config.xml");
	}
	
	private static Document parse(String path) throws Exception {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
		return factory.newDocumentBuilder().parse(new File(path));
	}
}
