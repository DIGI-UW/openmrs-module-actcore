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

import java.io.IOException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.openmrs.Form;
import org.openmrs.api.context.Context;
import org.openmrs.api.impl.BaseOpenmrsService;
import org.openmrs.module.actcore.ActCoreConstants;
import org.openmrs.module.actcore.api.AuditLogService;
import org.openmrs.module.actcore.api.DownloadLogService;
import org.openmrs.module.actcore.api.db.DownloadLogDAO;
import org.openmrs.module.actcore.api.search.DownloadSearchCriteria;
import org.openmrs.module.actcore.model.DownloadLogItem;
import org.openmrs.module.actcore.model.DownloadLogEntry;
import org.openmrs.module.actcore.model.DownloadPage;
import org.openmrs.module.actcore.model.DownloadRecord;
import org.openmrs.module.actcore.model.LogUser;
import org.openmrs.util.PrivilegeConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Default implementation of {@link org.openmrs.module.actcore.api.DownloadLogService}. */
@Transactional
public class DownloadLogServiceImpl extends BaseOpenmrsService implements DownloadLogService {
	
	private static final Logger log = LoggerFactory.getLogger(DownloadLogServiceImpl.class);
	
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
	 * @see org.openmrs.module.actcore.api.DownloadLogService#getDownloads(DownloadSearchCriteria)
	 */
	@Override
	@Transactional(readOnly = true)
	public DownloadPage getDownloads(DownloadSearchCriteria filter) {
		DownloadPage page = new DownloadPage();
		AuditLogService logs = Context.getService(AuditLogService.class);
		Integer userId = null;
		if (StringUtils.isNotBlank(filter.username)) {
			userId = logs.getUserId(filter.username.trim());
			if (userId == null) {
				return page;
			}
		}
		String chosenForm = null;
		if (StringUtils.isNotBlank(filter.formUuid)) {
			Form form = Names.proxied(PrivilegeConstants.GET_FORMS,
			    () -> Context.getFormService().getFormByUuid(filter.formUuid.trim()));
			if (form == null) {
				return page;
			}
			chosenForm = form.getUuid();
		}
		List<DownloadLogEntry> entries = dao.getDownloads(userId,
		    filter.from == null ? null : Timestamp.valueOf(filter.from.atStartOfDay()),
		    filter.to == null ? null : Timestamp.valueOf(filter.to.plusDays(1).atStartOfDay()));
		Set<String> userUuids = new HashSet<String>();
		for (DownloadLogEntry entry : entries) {
			userUuids.add(entry.userUuid);
		}
		Map<String, LogUser> users = logs.getUsers(userUuids);
		List<DownloadLogItem> matching = new ArrayList<DownloadLogItem>();
		// Matched here rather than by the log's form_id, which a download of several forms leaves empty.
		for (DownloadLogEntry entry : entries) {
			Map<?, ?> detail = read(entry);
			boolean unreadable = detail == null && entry.detail != null;
			if (filter.identifiers != null && (unreadable || !filter.identifiers.equals(identifiersOf(detail)))) {
				continue;
			}
			if (chosenForm != null && !chosenForm.equals(entry.formUuid) && !formsOf(detail).contains(chosenForm)) {
				continue;
			}
			DownloadLogItem download = new DownloadLogItem();
			download.date = entry.date;
			download.user = users.get(entry.userUuid);
			download.formUuid = entry.formUuid;
			download.formName = Names.shown("Form", entry.formUuid, entry.formName);
			download.detail = detail;
			matching.add(download);
		}
		int start = Math.min(Math.max(filter.startIndex, 0), matching.size());
		int size = filter.limit < 1 ? ActCoreConstants.DOWNLOAD_DEFAULT_LIMIT
		        : Math.min(filter.limit, ActCoreConstants.DOWNLOAD_MAX_LIMIT);
		page.results.addAll(matching.subList(start, Math.min(start + size, matching.size())));
		page.totalCount = matching.size();
		return page;
	}
	
	private static Map<?, ?> read(DownloadLogEntry entry) {
		if (entry.detail == null) {
			return null;
		}
		try {
			return new ObjectMapper().readValue(entry.detail, Map.class);
		}
		catch (IOException e) {
			log.warn("Download {} is listed without its detail, which cannot be read as JSON", entry.uuid);
			return null;
		}
	}
	
	/**
	 * Tells what a recorded download held, in the identifiers filter's terms. It is "research" for a
	 * download of research data only, "with" for any other download that gave identifiers, and
	 * "without" for the rest, a download with no detail recorded among them.
	 */
	private static String identifiersOf(Map<?, ?> detail) {
		if (detail == null) {
			return "without";
		}
		if (Boolean.TRUE.equals(detail.get("researchOnly"))) {
			return "research";
		}
		return Boolean.TRUE.equals(detail.get("identifiers")) ? "with" : "without";
	}
	
	/**
	 * Gets the uuids of the forms a recorded download held. The list is empty for a list the app made
	 * itself, and for an entry recorded before the forms were.
	 */
	private static List<String> formsOf(Map<?, ?> detail) {
		List<String> uuids = new ArrayList<String>();
		Object forms = detail == null ? null : detail.get("forms");
		if (forms instanceof List) {
			for (Object form : (List<?>) forms) {
				if (form instanceof Map && ((Map<?, ?>) form).get("uuid") != null) {
					uuids.add(((Map<?, ?>) form).get("uuid").toString());
				}
			}
		}
		return uuids;
	}
}
