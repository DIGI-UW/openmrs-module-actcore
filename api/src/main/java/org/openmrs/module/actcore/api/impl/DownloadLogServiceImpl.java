/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.api.impl;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;

import org.openmrs.api.context.Context;
import org.openmrs.api.impl.BaseOpenmrsService;
import org.openmrs.module.actcore.api.DownloadLogService;
import org.openmrs.module.actcore.api.db.DownloadLogDAO;
import org.openmrs.module.actcore.download.DownloadLog;
import org.openmrs.module.actcore.download.DownloadRecord;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Default implementation of {@link org.openmrs.module.actcore.api.DownloadLogService}. */
@Transactional
public class DownloadLogServiceImpl extends BaseOpenmrsService implements DownloadLogService {
	
	private DownloadLogDAO dao;
	
	public void setDao(DownloadLogDAO dao) {
		this.dao = dao;
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.DownloadLogService#recordDownload(Integer, String)
	 */
	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void recordDownload(Integer formId, String detail) {
		int userId = Context.getAuthenticatedUser().getUserId();
		dao.saveDownloadRecord(new DownloadRecord(userId, formId, detail, new Date()));
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.DownloadLogService#getDownloads(Integer, LocalDate,
	 *      LocalDate)
	 */
	@Override
	@Transactional(readOnly = true)
	public List<DownloadLog.Entry> getDownloads(Integer userId, LocalDate from, LocalDate to) {
		return dao.getDownloads(userId, from == null ? null : Timestamp.valueOf(from.atStartOfDay()),
		    to == null ? null : Timestamp.valueOf(to.plusDays(1).atStartOfDay()));
	}
}
