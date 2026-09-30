/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.task;

import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.customdatatype.datatype.FreeTextDatatype;
import org.openmrs.module.cohort.CohortAttribute;
import org.openmrs.module.cohort.CohortAttributeType;
import org.openmrs.module.cohort.CohortM;
import org.openmrs.module.cohort.CohortMember;
import org.openmrs.module.cohort.CohortType;
import org.openmrs.module.cohort.api.CohortMemberService;
import org.openmrs.module.cohort.api.CohortService;
import org.openmrs.module.cohort.api.CohortTypeService;
import org.openmrs.module.patientflags.Flag;
import org.openmrs.module.patientflags.api.FlagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mirrors each patient flag's live rows into a patient list that shares the flag's uuid and name.
 * Keying by uuid lets a list follow a rename and leaves hand-made cohorts of the same name alone.
 */
public class FlagListSync {
	
	public static final String TAG_PROPERTY = "actcore.listFlagTag";
	
	public static final String COHORT_TYPE_PROPERTY = "actcore.listCohortType";
	
	private static final String DEFAULT_COHORT_TYPE = "System List";
	
	// Marks the module's lists, so one whose flag was purged can be told from a hand-made cohort.
	private static final String MARKER_TYPE_UUID = "11b3c2f6-b196-4476-8f2e-1f1147d6db31";
	
	private static final String PURGED_REASON = "Its patient flag was purged";
	
	private static final Logger log = LoggerFactory.getLogger(FlagListSync.class);
	
	/**
	 * What a run of {@link #syncAll()} changed, so the scheduled task can say so in one line.
	 */
	public static class Result {
		
		int created;
		
		int restored;
		
		int retired;
		
		int membersAdded;
		
		int membersEnded;
		
		int failures;
	}
	
	public Result syncAll() {
		Result result = new Result();
		FlagService flagService = Context.getService(FlagService.class);
		String requiredTag = Context.getAdministrationService().getGlobalProperty(TAG_PROPERTY);
		List<Flag> flags = flagService.getAllFlags();
		Set<String> flagUuids = new HashSet<String>();
		for (Flag flag : flags) {
			flagUuids.add(flag.getUuid());
		}
		
		// Before the flags, so a new flag can take a purged flag's list name in the same run.
		CohortService cohortService = Context.getService(CohortService.class);
		Map<String, CohortM> marked = new HashMap<String, CohortM>();
		for (CohortAttribute marker : cohortService.findCohortAttributesByTypeUuid(MARKER_TYPE_UUID)) {
			marked.put(marker.getValueReference(), marker.getCohort());
			if (!flagUuids.contains(marker.getValueReference()) && !Boolean.TRUE.equals(marker.getCohort().getVoided())) {
				// Voiding the list voids its memberships too.
				cohortService.voidCohortM(marker.getCohort(), PURGED_REASON);
				result.retired++;
				log.info("Retired list '{}': its patient flag no longer exists", marker.getCohort().getName());
			}
		}
		
		for (Flag flag : flags) {
			boolean listed = Boolean.TRUE.equals(flag.getEnabled()) && !Boolean.TRUE.equals(flag.getRetired())
			        && carriesTag(flag, requiredTag);
			try {
				sync(flag, listed, marked.get(flag.getUuid()), result);
			}
			catch (Exception e) {
				result.failures++;
				log.error("Could not sync the list for flag '{}'", flag.getName(), e);
			}
		}
		return result;
	}
	
	private boolean carriesTag(Flag flag, String requiredTag) {
		if (requiredTag == null || requiredTag.trim().isEmpty()) {
			return true;
		}
		if (flag.getTags() == null) {
			return false;
		}
		for (org.openmrs.module.patientflags.Tag tag : flag.getTags()) {
			if (requiredTag.trim().equalsIgnoreCase(tag.getName())) {
				return true;
			}
		}
		return false;
	}
	
	void sync(Flag flag, boolean listed, CohortM marked, Result result) {
		CohortService cohortService = Context.getService(CohortService.class);
		
		CohortM list = cohortService.getCohortMByUuid(flag.getUuid());
		if (list == null) {
			if (!listed) {
				return;
			}
			// Unvoid rather than create: a flag recreated under its uuid, as Initializer does, finds it taken.
			if (marked != null && PURGED_REASON.equals(marked.getVoidReason())) {
				// Check before unvoiding: a rejected save leaves the list unvoided for the next commit.
				if (cohortService.getCohortM(flag.getName()) != null) {
					log.warn("List '{}' stays voided: another cohort is already called '{}'", marked.getName(),
					    flag.getName());
					return;
				}
				marked.setVoided(false);
				marked.setName(flag.getName());
				marked.setDescription(description(flag));
				list = cohortService.saveCohortM(marked);
				result.restored++;
				log.info("Restored list '{}': its patient flag exists again", flag.getName());
			} else if (listWasVoided(flag)) {
				// Don't recreate a list someone voided: its uuid still exists.
				return;
			} else {
				list = createList(cohortService, flag);
				result.created++;
			}
		} else if (!flag.getName().equals(list.getName())) {
			// Check before setName: a rejected save leaves the name dirty for the next commit to write.
			CohortM holder = cohortService.getCohortM(flag.getName());
			if (holder == null || holder.getUuid().equals(list.getUuid())) {
				log.info("Renaming list '{}' to '{}'", list.getName(), flag.getName());
				list.setName(flag.getName());
				list.setDescription(description(flag));
				cohortService.saveCohortM(list);
			} else if (inRenameCycle(cohortService, holder, list)) {
				// Without one list stepping aside, flags that swap names block each other's lists for good.
				log.info("Renaming list '{}' aside: '{}' is held by a flag renamed in the same run", list.getName(),
				    flag.getName());
				list.setName(flag.getName() + " (" + flag.getUuid() + ")");
				cohortService.saveCohortM(list);
			} else {
				log.warn("List '{}' keeps its name: another cohort is already called '{}'", list.getName(), flag.getName());
			}
		}
		
		if (marked == null) {
			mark(cohortService, list, flag);
		}
		
		setMembers(list, listed ? PatientFlagRefreshTask.flaggedMessages(flag).keySet() : Collections.<Integer> emptySet(),
		    result);
	}
	
	private void setMembers(CohortM list, Set<Integer> flagged, Result result) {
		CohortMemberService memberService = Context.getService(CohortMemberService.class);
		Map<Integer, CohortMember> active = activeMembers(memberService, list);
		
		int added = 0;
		for (Integer patientId : flagged) {
			if (!active.containsKey(patientId)) {
				CohortMember member = new CohortMember();
				member.setCohort(list);
				member.setPatient(Context.getPatientService().getPatient(patientId));
				member.setStartDate(new Date());
				memberService.saveCohortMember(member);
				added++;
			}
		}
		
		// End-date rather than void: the cohort REST resource counts voided rows as duplicates.
		int removed = 0;
		for (Map.Entry<Integer, CohortMember> entry : active.entrySet()) {
			if (!flagged.contains(entry.getKey())) {
				CohortMember member = entry.getValue();
				member.setEndDate(new Date());
				memberService.saveCohortMember(member);
				removed++;
			}
		}
		
		result.membersAdded += added;
		result.membersEnded += removed;
		if (added > 0 || removed > 0) {
			log.info("List '{}': {} added, {} ended", list.getName(), added, removed);
		}
	}
	
	private void mark(CohortService cohortService, CohortM list, Flag flag) {
		CohortAttributeType type = cohortService.getCohortAttributeTypeByUuid(MARKER_TYPE_UUID);
		if (type == null) {
			type = new CohortAttributeType();
			type.setUuid(MARKER_TYPE_UUID);
			type.setName("Source patient flag");
			type.setDescription("The patient flag this list mirrors");
			type.setDatatypeClassname(FreeTextDatatype.class.getName());
			cohortService.saveCohortAttributeType(type);
		}
		CohortAttribute marker = new CohortAttribute();
		marker.setAttributeType(type);
		marker.setCohort(list);
		marker.setValueReferenceInternal(flag.getUuid());
		cohortService.saveCohortAttribute(marker);
	}
	
	private boolean inRenameCycle(CohortService cohortService, CohortM holder, CohortM list) {
		FlagService flagService = Context.getService(FlagService.class);
		Set<String> seen = new HashSet<String>();
		while (seen.add(holder.getUuid())) {
			Flag owner = flagService.getFlagByUuid(holder.getUuid());
			if (owner == null) {
				return false;
			}
			CohortM next = cohortService.getCohortM(owner.getName());
			if (next == null) {
				return false;
			}
			if (next.getUuid().equals(list.getUuid())) {
				return true;
			}
			holder = next;
		}
		return false;
	}
	
	private CohortM createList(CohortService cohortService, Flag flag) {
		CohortM list = new CohortM();
		list.setUuid(flag.getUuid());
		list.setName(flag.getName());
		list.setDescription(description(flag));
		list.setGroupCohort(Boolean.FALSE);
		list.setCohortType(cohortType());
		log.info("Creating list '{}'", flag.getName());
		return cohortService.saveCohortM(list);
	}
	
	private String description(Flag flag) {
		return "Patients currently flagged: " + flag.getName();
	}
	
	/**
	 * The cohort module's CohortService only finds unvoided cohorts by uuid.
	 */
	private boolean listWasVoided(Flag flag) {
		List<List<Object>> rows = Context.getAdministrationService()
		        .executeSQL("select count(*) from cohort where uuid = '" + flag.getUuid().replace("'", "''") + "'", true);
		return ((Number) rows.get(0).get(0)).intValue() > 0;
	}
	
	private CohortType cohortType() {
		String configured = Context.getAdministrationService().getGlobalProperty(COHORT_TYPE_PROPERTY);
		String wanted = (configured == null || configured.trim().isEmpty()) ? DEFAULT_COHORT_TYPE : configured.trim();
		CohortTypeService cohortTypeService = Context.getService(CohortTypeService.class);
		for (CohortType type : cohortTypeService.findAllCohortTypes()) {
			if (wanted.equalsIgnoreCase(type.getName())) {
				return type;
			}
		}
		if (cohortTypeService.getCohortTypeByName(wanted, true) != null) {
			throw new IllegalStateException("The cohort type '" + wanted + "' has been voided");
		}
		CohortType type = new CohortType();
		type.setName(wanted);
		type.setDescription("Patient lists kept in step with patient flags");
		log.info("Creating cohort type '{}'", wanted);
		return cohortTypeService.saveCohortType(type);
	}
	
	private Map<Integer, CohortMember> activeMembers(CohortMemberService memberService, CohortM list) {
		Map<Integer, CohortMember> active = new HashMap<Integer, CohortMember>();
		Collection<CohortMember> members = memberService.findCohortMembersByCohortUuid(list.getUuid());
		if (members != null) {
			for (CohortMember member : members) {
				if (Boolean.TRUE.equals(member.getVoided()) || member.getEndDate() != null) {
					continue;
				}
				Patient patient = member.getPatient();
				if (patient != null) {
					active.put(patient.getPatientId(), member);
				}
			}
		}
		return active;
	}
}
