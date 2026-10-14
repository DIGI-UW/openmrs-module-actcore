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
import javax.xml.xpath.XPathFactory;

import org.junit.Test;
import org.openmrs.api.EncounterService;
import org.openmrs.module.actcore.adherence.RefreshAdherenceOnSave;

public class AdviceRegistrationTest {
	
	@Test
	public void configRegistersTheAdherenceRefreshOnEncounterService() throws Exception {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
		
		String point = XPathFactory.newInstance().newXPath().evaluate(
		    "//advice[normalize-space(class)='" + RefreshAdherenceOnSave.class.getName() + "']/point",
		    factory.newDocumentBuilder().parse(new File("target/classes/config.xml")));
		
		assertEquals(EncounterService.class.getName(), point.trim());
	}
}
