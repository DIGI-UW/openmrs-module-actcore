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
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Arrays;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathFactory;

import org.junit.Test;
import org.w3c.dom.Document;

public class NextStepsDefaultsUpgradeTest {
	
	private static final String CHANGESET = "//changeSet[@id='actcore-2026-10-13-next-steps-patient-information']/update";
	
	private static final String PATIENT_INFO = "patientInfo:ffdc883f-f99f-496a-83f9-2c3c581e1af0";
	
	// The value config.xml shipped before this changeset; never update it to a later default.
	private static final String EARLIER_DEFAULT = "bpg:04cf03db-3b8e-5020-84b0-50b06338767a,oral:55271793-ef37-58da-9d86-1d9092a5a809,"
	        + "consult:c2503561-c00d-5460-8157-43d594472b4a,echo:730f5ec2-7102-55d0-8602-2d792844f245,"
	        + "procedures:c9b87090-8768-50be-987e-8ca0a8983429,inr:b4bb88a3-9a04-5142-85bd-bb63c270f632";
	
	private static final String REFER_CHANGESET = "//changeSet[@id='actcore-2026-11-02-next-steps-refer']/update";
	
	private static final String REFER = "refer:f423ba04-0f7a-414e-98c7-6b4d5ba0d93a";
	
	// The value config.xml shipped before the refer changeset; never update it to a later default.
	private static final String DEFAULT_BEFORE_REFER = EARLIER_DEFAULT + "," + PATIENT_INFO
	        + ",pregnancy:ce6b111e-9beb-5a91-91ad-ec5043976fd5";
	
	private final XPath xpath = XPathFactory.newInstance().newXPath();
	
	@Test
	public void upgradeAddsReferOnlyToTheDefaultsBeforeIt() throws Exception {
		Document liquibase = parse("target/classes/liquibase.xml");
		
		assertEquals(DEFAULT_BEFORE_REFER + "," + REFER,
		    xpath.evaluate(REFER_CHANGESET + "[1]/column[@name='property_value']/@value", liquibase));
		assertEquals("property = 'actcore.nextSteps.encounterTypes' AND property_value = '" + DEFAULT_BEFORE_REFER + "'",
		    xpath.evaluate(REFER_CHANGESET + "[1]/where", liquibase).trim());
		// An install upgraded by the patientInfo changeset before pregnancy joined the default.
		String beforePregnancy = EARLIER_DEFAULT + "," + PATIENT_INFO;
		assertEquals(DEFAULT_BEFORE_REFER + "," + REFER,
		    xpath.evaluate(REFER_CHANGESET + "[2]/column[@name='property_value']/@value", liquibase));
		assertEquals("property = 'actcore.nextSteps.encounterTypes' AND property_value = '" + beforePregnancy + "'",
		    xpath.evaluate(REFER_CHANGESET + "[2]/where", liquibase).trim());
		assertEquals("2", xpath.evaluate("count(" + REFER_CHANGESET + ")", liquibase));
	}
	
	@Test
	public void encounterTypesDefaultIncludesRefer() throws Exception {
		assertEquals(DEFAULT_BEFORE_REFER + "," + REFER, currentDefault());
	}
	
	@Test
	public void upgradeAddsPatientInformationOnlyToTheEarlierEncounterTypesDefault() throws Exception {
		Document liquibase = parse("target/classes/liquibase.xml");
		
		assertEquals(EARLIER_DEFAULT + "," + PATIENT_INFO,
		    xpath.evaluate(CHANGESET + "/column[@name='property_value']/@value", liquibase));
		assertEquals("property = 'actcore.nextSteps.encounterTypes' AND property_value = '" + EARLIER_DEFAULT + "'",
		    xpath.evaluate(CHANGESET + "/where", liquibase).trim());
	}
	
	@Test
	public void encounterTypesDefaultIncludesPatientInformation() throws Exception {
		assertTrue(Arrays.asList(currentDefault().split(",")).contains(PATIENT_INFO));
	}
	
	private String currentDefault() throws Exception {
		return xpath.evaluate("//globalProperty[normalize-space(property)='actcore.nextSteps.encounterTypes']/defaultValue",
		    parse("target/classes/config.xml")).trim();
	}
	
	private static Document parse(String path) throws Exception {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
		return factory.newDocumentBuilder().parse(new File(path));
	}
}
