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
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import org.junit.Test;
import org.openmrs.module.actcore.ActCoreActivator;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

/**
 * Loads every class that config.xml and moduleApplicationContext.xml name, since both name classes
 * by string and a stale name fails only at module start.
 */
public class ConfigClassNamesTest {
	
	private static final Pattern CLASS_NAME = Pattern.compile("([a-z][a-z0-9_]*\\.)+[A-Z][A-Za-z0-9_$]*");
	
	@Test
	public void everyClassConfigXmlNamesLoads() throws Exception {
		List<String> names = classNames(parse(new File("target/classes/config.xml").toURI().toString()));
		
		assertTrue(names.toString(), names.contains(ActCoreActivator.class.getName()));
		assertEquals(names.toString(), new ArrayList<String>(), unloadable(names));
	}
	
	@Test
	public void everyClassModuleApplicationContextNamesLoads() throws Exception {
		List<String> names;
		try (InputStream in = getClass().getResourceAsStream("/moduleApplicationContext.xml")) {
			names = classNames(parse(in));
		}
		
		assertTrue(names.toString(), names.contains("org.openmrs.module.actcore.api.impl.ScreeningCountServiceImpl"));
		assertEquals(names.toString(), new ArrayList<String>(), unloadable(names));
	}
	
	private static List<String> unloadable(List<String> names) {
		List<String> unloadable = new ArrayList<String>();
		for (String name : names) {
			try {
				Class.forName(name, false, ConfigClassNamesTest.class.getClassLoader());
			}
			catch (ClassNotFoundException e) {
				unloadable.add(name);
			}
		}
		return unloadable;
	}
	
	/** Every class attribute and every element text that reads as a fully qualified class name. */
	private static List<String> classNames(Document document) throws Exception {
		NodeList nodes = (NodeList) XPathFactory.newInstance().newXPath().evaluate("//@class | //*[not(*)]/text()", document,
		    XPathConstants.NODESET);
		List<String> names = new ArrayList<String>();
		for (int i = 0; i < nodes.getLength(); i++) {
			String value = nodes.item(i).getNodeValue().trim();
			if (CLASS_NAME.matcher(value).matches()) {
				names.add(value);
			}
		}
		return names;
	}
	
	private static Document parse(String uri) throws Exception {
		return builder().newDocumentBuilder().parse(uri);
	}
	
	private static Document parse(InputStream in) throws Exception {
		return builder().newDocumentBuilder().parse(in);
	}
	
	private static DocumentBuilderFactory builder() throws Exception {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
		return factory;
	}
}
