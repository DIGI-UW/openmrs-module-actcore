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

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openmrs.Patient;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.api.impl.BaseOpenmrsService;
import org.openmrs.module.actcore.ActCoreConstants;
import org.openmrs.module.actcore.ActCorePrivileges;
import org.openmrs.module.actcore.api.RaisedFlagService;
import org.openmrs.module.actcore.api.db.RaisedFlagDAO;
import org.openmrs.module.actcore.model.ChartFlag;
import org.openmrs.module.actcore.model.DismissalOutcome;
import org.openmrs.module.actcore.model.FlagDismissal;
import org.openmrs.module.actcore.model.RaisedFlag;
import org.openmrs.module.actcore.tasks.FlagListSync;
import org.openmrs.module.actcore.tasks.PatientFlagRefreshTask;
import org.openmrs.module.actcore.utils.Transactions;
import org.openmrs.module.patientflags.Flag;
import org.openmrs.module.patientflags.PatientFlag;
import org.openmrs.module.patientflags.PatientFlagsConstants;
import org.openmrs.module.patientflags.Tag;
import org.openmrs.module.patientflags.api.FlagService;
import org.openmrs.util.PrivilegeConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/** Default implementation of {@link org.openmrs.module.actcore.api.RaisedFlagService}. */
public class RaisedFlagServiceImpl extends BaseOpenmrsService implements RaisedFlagService {
	
	private static final Logger log = LoggerFactory.getLogger(RaisedFlagServiceImpl.class);
	
	// What evaluating the flag's criteria and updating the patient's place on its list need.
	private static final String[] DISMISS_PRIVILEGES = { PatientFlagsConstants.PRIV_TEST_FLAGS,
	        PrivilegeConstants.SQL_LEVEL_ACCESS, PrivilegeConstants.GET_PATIENTS, PrivilegeConstants.GET_GLOBAL_PROPERTIES,
	        ActCoreConstants.COHORT_VIEW_COHORTS, ActCoreConstants.COHORT_EDIT_COHORTS };
	
	private RaisedFlagDAO dao;
	
	public void setDao(RaisedFlagDAO dao) {
		this.dao = dao;
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.RaisedFlagService#raise(Integer, Integer)
	 */
	@Override
	@Transactional
	public void raise(Integer flagId, Integer patientId) {
		if (dao.isRaisable(flagId, patientId)) {
			dao.saveRaisedFlag(new RaisedFlag(new RaisedFlag.Key(patientId, flagId), new Date()));
		}
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.RaisedFlagService#lower(Integer, Integer)
	 */
	@Override
	@Transactional
	public void lower(Integer flagId, Integer patientId) {
		dao.deleteRaisedFlags(flagId, patientId);
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.RaisedFlagService#lowerAll(Integer)
	 */
	@Override
	@Transactional
	public void lowerAll(Integer flagId) {
		dao.deleteRaisedFlags(flagId, null);
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.RaisedFlagService#getRaisedPatients(Integer)
	 */
	@Override
	@Transactional(readOnly = true)
	public Set<Integer> getRaisedPatients(Integer flagId) {
		return dao.getRaisedSinceByPatient(flagId).keySet();
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.RaisedFlagService#getDismissedPatients(Integer)
	 */
	@Override
	@Transactional(readOnly = true)
	public Set<Integer> getDismissedPatients(Integer flagId) {
		return dao.getDismissedPatients(flagId);
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.RaisedFlagService#isDismissed(Integer, Integer)
	 */
	@Override
	@Transactional(readOnly = true)
	public boolean isDismissed(Integer flagId, Integer patientId) {
		return dao.getDismissedFlags(patientId).contains(flagId);
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.RaisedFlagService#getDismissedFlags(Integer)
	 */
	@Override
	@Transactional(readOnly = true)
	public Set<Integer> getDismissedFlags(Integer patientId) {
		return dao.getDismissedFlags(patientId);
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.RaisedFlagService#getRaisedSinceByPatient(Integer)
	 */
	@Override
	@Transactional(readOnly = true)
	public Map<Integer, Date> getRaisedSinceByPatient(Integer flagId) {
		return dao.getRaisedSinceByPatient(flagId);
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.RaisedFlagService#getRaisedSinceByFlag(Integer)
	 */
	@Override
	@Transactional(readOnly = true)
	public Map<Integer, Date> getRaisedSinceByFlag(Integer patientId) {
		return dao.getRaisedSinceByFlag(patientId);
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.RaisedFlagService#mayDismiss(Flag)
	 */
	@Override
	@Transactional(readOnly = true)
	public boolean mayDismiss(Flag flag) {
		return Context.hasPrivilege(ActCorePrivileges.DISMISS_FLAGS)
		        && Context.hasPrivilege(PatientFlagsConstants.PRIV_VIEW_PATIENT_FLAGS) && carriesDismissTag(flag);
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.RaisedFlagService#getRaisedFlags(Patient)
	 */
	@Override
	@Transactional(readOnly = true)
	public List<ChartFlag> getRaisedFlags(Patient patient) {
		Map<Integer, Date> raisedSince = dao.getRaisedSinceByFlag(patient.getPatientId());
		List<ChartFlag> raised = new ArrayList<ChartFlag>();
		Set<Integer> seen = new HashSet<Integer>();
		for (PatientFlag row : Context.getService(FlagService.class).getPatientFlags(patient)) {
			Flag flag = row.getFlag();
			if (Boolean.TRUE.equals(row.getVoided()) || flag == null || !seen.add(flag.getFlagId())) {
				continue;
			}
			raised.add(new ChartFlag(flag, raisedSince.get(flag.getFlagId()), mayDismiss(flag)));
		}
		return raised;
	}
	
	/**
	 * @see org.openmrs.module.actcore.api.RaisedFlagService#dismiss(Patient, Flag)
	 */
	@Override
	// No @Transactional: REQUIRED undoes it on a failing sync; SUPPORTS flushes outside a tx.
	public DismissalOutcome dismiss(Patient patient, Flag flag) {
		if (!carriesDismissTag(flag)) {
			return DismissalOutcome.NOT_DISMISSIBLE;
		}
		// Not waited on: the full refresh holds it for minutes, and would undo the dismissal.
		if (!PatientFlagRefreshTask.ROWS.readLock().tryLock()) {
			return DismissalOutcome.REFRESH_RUNNING;
		}
		for (String privilege : DISMISS_PRIVILEGES) {
			Context.addProxyPrivilege(privilege);
		}
		try {
			if (Boolean.TRUE.equals(patient.getVoided()) || !Boolean.TRUE.equals(flag.getEnabled())
			        || Boolean.TRUE.equals(flag.getRetired())
			        || !Boolean.TRUE.equals(flag.eval(patient, new HashMap<Object, Object>()))) {
				return DismissalOutcome.NOT_RAISED;
			}
			Integer userId = Context.getAuthenticatedUser().getUserId();
			recordDismissal(flag.getFlagId(), patient.getPatientId(), userId);
			new FlagListSync().syncPatient(flag.getFlagId(), patient.getPatientId());
			log.debug("Flag '{}' of patient {} dismissed by user {}", flag.getName(), patient.getPatientId(), userId);
			return DismissalOutcome.DISMISSED;
		}
		finally {
			for (String privilege : DISMISS_PRIVILEGES) {
				Context.removeProxyPrivilege(privilege);
			}
			PatientFlagRefreshTask.ROWS.readLock().unlock();
		}
	}
	
	// Committed before the list sync, so a failing sync leaves the dismissal recorded.
	private void recordDismissal(Integer flagId, Integer patientId, Integer userId) {
		try {
			Transactions.inTransaction(() -> {
				raise(flagId, patientId);
				Date now = new Date();
				if (dao.dismissRaisedFlag(flagId, patientId, userId, now)) {
					dao.saveFlagDismissal(new FlagDismissal(patientId, flagId, userId, now));
				}
				return null;
			});
		}
		catch (RuntimeException e) {
			throw e;
		}
		catch (Throwable e) {
			throw new APIException("Could not record the dismissal", e);
		}
	}
	
	private static boolean carriesDismissTag(Flag flag) {
		String tag = Context.getAdministrationService().getGlobalProperty(ActCoreConstants.GP_DISMISS_FLAG_TAG);
		if (tag == null || tag.trim().isEmpty()) {
			return true;
		}
		if (flag.getTags() != null) {
			for (Tag candidate : flag.getTags()) {
				if (tag.trim().equalsIgnoreCase(candidate.getName())) {
					return true;
				}
			}
		}
		return false;
	}
}
