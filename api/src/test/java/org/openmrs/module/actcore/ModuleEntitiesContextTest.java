/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.Before;
import org.junit.Test;
import org.openmrs.api.context.Context;
import org.openmrs.api.db.hibernate.DbSession;
import org.openmrs.api.db.hibernate.DbSessionFactory;
import org.openmrs.module.actcore.model.DownloadRecord;
import org.openmrs.module.actcore.model.FlagDismissal;
import org.openmrs.module.actcore.model.InjectionTiming;
import org.openmrs.module.actcore.model.ProphylaxisAdherence;
import org.openmrs.module.actcore.model.RaisedFlag;
import org.openmrs.module.actcore.model.ScreeningCount;
import org.openmrs.module.actcore.model.ScreeningCounts;
import org.openmrs.module.patientflags.Flag;
import org.openmrs.module.patientflags.api.FlagService;
import org.openmrs.module.patientflags.evaluator.SQLFlagEvaluator;
import org.openmrs.test.BaseModuleContextSensitiveTest;

/** Each entity reads back the row its table holds, as the changelog makes the table. */
public class ModuleEntitiesContextTest extends BaseModuleContextSensitiveTest {
	
	private static final int PATIENT = 7;
	
	@Before
	public void createModuleTables() {
		ModuleTables.create();
	}
	
	@Test
	public void readsAPatientsAdherence() throws Exception {
		sql("insert into " + ActCoreConstants.PROPHYLAXIS_ADHERENCE_TABLE
		        + " (patient_id, regimen_concept_id, injection_interval_days, adherence,"
		        + " last_given, next_due, date_computed) values (" + PATIENT
		        + ", null, 28, 0.75, '2026-10-01', '2026-10-29', '2026-10-02 08:30:00')");
		
		ProphylaxisAdherence adherence = (ProphylaxisAdherence) session().get(ProphylaxisAdherence.class, PATIENT);
		
		assertNull(adherence.getRegimenConceptId());
		assertEquals(Integer.valueOf(28), adherence.getInjectionIntervalDays());
		assertEquals(0.75, adherence.getAdherence(), 0);
		assertEquals("2026-10-01", day(adherence.getLastGiven()));
		assertEquals("2026-10-29", day(adherence.getNextDue()));
		assertEquals("2026-10-02 08:30:00", time(adherence.getDateComputed()));
	}
	
	@Test
	public void readsAnInjectionsTiming() throws Exception {
		sql("insert into " + ActCoreConstants.INJECTION_TIMING_TABLE + " (patient_id, injection_date, on_time) values ("
		        + PATIENT + ", '2026-10-01', true)");
		
		InjectionTiming timing = (InjectionTiming) session().get(InjectionTiming.class,
		    new InjectionTiming.Key(PATIENT, java.sql.Date.valueOf("2026-10-01")));
		
		assertTrue(timing.isOnTime());
	}
	
	@Test
	public void readsADownload() throws Exception {
		sql("insert into " + ActCoreConstants.DOWNLOAD_LOG_TABLE
		        + " (uuid, user_id, form_id, detail, date_created) values ('download-1', 1, 99,"
		        + " 'forms: 1', '2026-10-02 08:30:00')");
		
		DownloadRecord download = (DownloadRecord) single("from DownloadRecord");
		
		assertEquals("download-1", download.getUuid());
		assertEquals(Integer.valueOf(1), download.getUserId());
		assertEquals(Integer.valueOf(99), download.getFormId());
		assertEquals("forms: 1", download.getDetail());
		assertEquals("2026-10-02 08:30:00", time(download.getDateCreated()));
	}
	
	@Test
	public void readsARaisedFlagAndItsDismissal() throws Exception {
		int flagId = flag();
		sql("insert into " + ActCoreConstants.RAISED_FLAG_TABLE
		        + " (patient_id, flag_id, raised_since, dismissed_by, date_dismissed) values (" + PATIENT + ", " + flagId
		        + ", '2026-10-01 08:00:00', 1, '2026-10-02 09:00:00')");
		sql("insert into " + ActCoreConstants.FLAG_DISMISSAL_TABLE
		        + " (uuid, patient_id, flag_id, dismissed_by, date_dismissed)" + " values ('dismissal-1', " + PATIENT + ", "
		        + flagId + ", 1, '2026-10-02 09:00:00')");
		
		RaisedFlag raised = (RaisedFlag) session().get(RaisedFlag.class, new RaisedFlag.Key(PATIENT, flagId));
		FlagDismissal dismissal = (FlagDismissal) single("from FlagDismissal");
		
		assertEquals("2026-10-01 08:00:00", time(raised.getRaisedSince()));
		assertEquals(Integer.valueOf(1), raised.getDismissedBy());
		assertEquals("2026-10-02 09:00:00", time(raised.getDateDismissed()));
		assertEquals("dismissal-1", dismissal.getUuid());
		assertEquals(Integer.valueOf(PATIENT), dismissal.getPatientId());
		assertEquals(Integer.valueOf(flagId), dismissal.getFlagId());
		assertEquals(Integer.valueOf(1), dismissal.getDismissedBy());
		assertEquals("2026-10-02 09:00:00", time(dismissal.getDateDismissed()));
	}
	
	@Test
	public void readsEachScreeningCountFromItsOwnColumn() throws Exception {
		Map<String, Integer> written = new LinkedHashMap<String, Integer>();
		for (String column : ScreeningCounts.COLUMNS) {
			written.put(column, written.size() + 1);
		}
		StringBuilder values = new StringBuilder();
		for (Integer value : written.values()) {
			values.append(", ").append(value);
		}
		sql("insert into " + ActCoreConstants.SCREENING_COUNT_TABLE + " (location_id, month, "
		        + String.join(", ", written.keySet())
		        + ", notes, uuid, creator, date_created, voided) values (1, '2026-09-01'" + values + ", 'school term',"
		        + " 'count-1', 1, '2026-10-02 08:30:00', false)");
		
		ScreeningCount count = (ScreeningCount) single("from ScreeningCount");
		
		assertEquals(written, count.getCounts());
		assertEquals(Integer.valueOf(1), count.getLocationId());
		assertEquals("2026-09-01", day(count.getMonth()));
		assertEquals("school term", count.getNotes());
		assertEquals("count-1", count.getUuid());
		assertEquals(Integer.valueOf(1), count.getCreator().getUserId());
		assertEquals("2026-10-02 08:30:00", time(count.getDateCreated()));
		assertFalse(count.getVoided());
	}
	
	@Test
	public void writesEachScreeningCountToItsOwnColumn() throws Exception {
		Map<String, Integer> written = new LinkedHashMap<String, Integer>();
		for (String column : ScreeningCounts.COLUMNS) {
			written.put(column, written.size() + 1);
		}
		session().save(new ScreeningCount(1, java.sql.Date.valueOf("2026-09-01"), written, null));
		Context.flushSession();
		
		List<List<Object>> rows = Context.getAdministrationService().executeSQL(
		    "select " + String.join(", ", written.keySet()) + " from " + ActCoreConstants.SCREENING_COUNT_TABLE, true);
		
		assertEquals(1, rows.size());
		Map<String, Integer> stored = new LinkedHashMap<String, Integer>();
		for (String column : written.keySet()) {
			stored.put(column, ((Number) rows.get(0).get(stored.size())).intValue());
		}
		assertEquals(written, stored);
	}
	
	@Test
	public void givesEachSavedRecordAUuidAndTheScreeningCountItsCreator() throws Exception {
		DownloadRecord download = new DownloadRecord(1, null, "forms: 1", new Date());
		FlagDismissal dismissal = new FlagDismissal(PATIENT, flag(), 1, new Date());
		ScreeningCount count = new ScreeningCount(1, java.sql.Date.valueOf("2026-09-01"),
		        new LinkedHashMap<String, Integer>(zeros()), null);
		session().save(download);
		session().save(dismissal);
		session().save(count);
		Context.flushSession();
		
		assertEquals(download.getUuid(), column(ActCoreConstants.DOWNLOAD_LOG_TABLE, "uuid"));
		assertEquals(dismissal.getUuid(), column(ActCoreConstants.FLAG_DISMISSAL_TABLE, "uuid"));
		assertEquals(count.getUuid(), column(ActCoreConstants.SCREENING_COUNT_TABLE, "uuid"));
		assertEquals(Context.getAuthenticatedUser().getUserId(),
		    Integer.valueOf(((Number) column(ActCoreConstants.SCREENING_COUNT_TABLE, "creator")).intValue()));
	}
	
	private static Map<String, Integer> zeros() {
		Map<String, Integer> zeros = new LinkedHashMap<String, Integer>();
		for (String column : ScreeningCounts.COLUMNS) {
			zeros.put(column, 0);
		}
		return zeros;
	}
	
	private static Object column(String table, String column) {
		List<List<Object>> rows = Context.getAdministrationService().executeSQL("select " + column + " from " + table, true);
		assertEquals(1, rows.size());
		return rows.get(0).get(0);
	}
	
	private int flag() {
		Flag flag = new Flag();
		flag.setUuid(UUID.randomUUID().toString());
		flag.setName("entity test");
		flag.setCriteria("select p.patient_id from patient p");
		flag.setEvaluator(SQLFlagEvaluator.class.getName());
		flag.setMessage("entity test");
		flag.setEnabled(Boolean.TRUE);
		Context.getService(FlagService.class).saveFlag(flag);
		return flag.getFlagId();
	}
	
	private static void sql(String sql) {
		Context.flushSession();
		Context.getAdministrationService().executeSQL(sql, false);
	}
	
	private static Object single(String hql) {
		List<?> rows = session().createQuery(hql).list();
		assertEquals(1, rows.size());
		return rows.get(0);
	}
	
	private static DbSession session() {
		return Context.getRegisteredComponent("dbSessionFactory", DbSessionFactory.class).getCurrentSession();
	}
	
	private static String day(Date date) {
		return new SimpleDateFormat("yyyy-MM-dd").format(date);
	}
	
	private static String time(Date date) {
		return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(date);
	}
}
