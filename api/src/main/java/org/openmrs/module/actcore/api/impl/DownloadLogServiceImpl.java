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
import java.nio.charset.StandardCharsets;
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
		if (detail != null && detail.getBytes(StandardCharsets.UTF_8).length > ActCoreConstants.MAX_DOWNLOAD_DETAIL_BYTES) {
			throw Rows.refusal("download", "Too many or too long columns and filters to record");
		}
		if (detail != null && !isJsonObject(detail)) {
			throw Rows.refusal("download", "A download's detail must be a JSON object");
		}
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
			DownloadLogItem download = read(entry);
			if (filter.identifiers != null && !filter.identifiers.equals(download.identifiers)) {
				continue;
			}
			if (chosenForm != null && !(download.unreadable && entry.formUuid == null) && !chosenForm.equals(entry.formUuid)
			        && !formsOf(download.detail).contains(chosenForm)) {
				continue;
			}
			download.date = entry.date;
			download.user = users.get(entry.userUuid);
			download.formUuid = entry.formUuid;
			download.formName = Names.shown("Form", entry.formUuid, entry.formName);
			matching.add(download);
		}
		int start = Math.min(Math.max(filter.startIndex, 0), matching.size());
		int size = filter.limit < 1 ? ActCoreConstants.DOWNLOAD_DEFAULT_LIMIT
		        : Math.min(filter.limit, ActCoreConstants.DOWNLOAD_MAX_LIMIT);
		page.results.addAll(matching.subList(start, Math.min(start + size, matching.size())));
		page.totalCount = matching.size();
		return page;
	}
	
	/** Tells whether a detail reads as a JSON object the way getDownloads reads it. */
	private static boolean isJsonObject(String detail) {
		try {
			return new ObjectMapper().readValue(detail, Map.class) != null;
		}
		catch (IOException e) {
			return false;
		}
	}
	
	/**
	 * Reads a download's detail into a new item, with what the identifiers filter calls it. A detail
	 * recorded that does not read as a JSON object, the JSON null among them, is logged and left out,
	 * and the item is marked unreadable and called "with", since it may have given identifiers and an
	 * auditor must not miss it.
	 */
	private static DownloadLogItem read(DownloadLogEntry entry) {
		DownloadLogItem download = new DownloadLogItem();
		if (entry.detail != null) {
			try {
				download.detail = new ObjectMapper().readValue(entry.detail, Map.class);
			}
			catch (IOException e) {
				download.detail = null;
			}
			download.unreadable = download.detail == null;
		}
		if (download.unreadable) {
			log.warn("Download {} is listed without its detail, which cannot be read as a JSON object", entry.uuid);
		}
		download.identifiers = download.unreadable ? "with" : identifiersOf(download.detail);
		return download;
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
