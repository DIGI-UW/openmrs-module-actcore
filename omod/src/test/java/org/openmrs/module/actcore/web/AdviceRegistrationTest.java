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
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import org.junit.Test;
import org.openmrs.api.EncounterService;
import org.openmrs.api.PatientService;
import org.openmrs.api.PersonService;
import org.openmrs.api.ProgramWorkflowService;
import org.openmrs.module.actcore.adherence.RefreshAdherenceOnSave;
import org.openmrs.module.actcore.registry.OneOpenRegistryEnrolment;
import org.openmrs.module.actcore.studies.CloseStudiesOnDeath;
import org.openmrs.module.actcore.studies.StudyEnrolmentRules;
import org.openmrs.module.actcore.studies.StudyEnrolmentStatus;
import org.w3c.dom.NodeList;

public class AdviceRegistrationTest {
	
	@Test
	public void configRegistersTheAdherenceRefreshOnEncounterService() throws Exception {
		assertEquals(points(EncounterService.class), pointsOf(RefreshAdherenceOnSave.class));
	}
	
	@Test
	public void configRegistersTheStudyAdviceOnTheServicesItGuards() throws Exception {
		assertEquals(points(PatientService.class, ProgramWorkflowService.class), pointsOf(OneOpenRegistryEnrolment.class));
		assertEquals(points(ProgramWorkflowService.class), pointsOf(StudyEnrolmentStatus.class));
		assertEquals(points(ProgramWorkflowService.class), pointsOf(StudyEnrolmentRules.class));
		assertEquals(points(PatientService.class, PersonService.class), pointsOf(CloseStudiesOnDeath.class));
	}
	
	private static Set<String> points(Class<?>... services) {
		return Arrays.stream(services).map(Class::getName).collect(Collectors.toSet());
	}
	
	private static Set<String> pointsOf(Class<?> advice) throws Exception {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
		NodeList nodes = (NodeList) XPathFactory.newInstance().newXPath().evaluate(
		    "//advice[normalize-space(class)='" + advice.getName() + "']/point",
		    factory.newDocumentBuilder().parse(new File("target/classes/config.xml")), XPathConstants.NODESET);
		Set<String> points = new HashSet<>();
		for (int i = 0; i < nodes.getLength(); i++) {
			points.add(nodes.item(i).getTextContent().trim());
		}
		return points;
	}
}
