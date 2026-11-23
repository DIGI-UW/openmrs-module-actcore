/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */

package org.openmrs.module.actcore.api.db.hibernate;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class HibernateAdherenceDAOTest {
	
	@Test
	public void sqlValue_shouldWriteABooleanAsABareLiteral() {
		// MariaDB in strict mode rejects a quoted 'true' for the timing table's tinyint column; H2 accepts it.
		assertEquals("true", HibernateAdherenceDAO.sqlValue(Boolean.TRUE));
		assertEquals("false", HibernateAdherenceDAO.sqlValue(Boolean.FALSE));
	}
}
