# OpenMRS ACT Core module

[![Build with Maven](https://github.com/DIGI-UW/openmrs-module-actcore/actions/workflows/build.yml/badge.svg)](https://github.com/DIGI-UW/openmrs-module-actcore/actions/workflows/build.yml)

The server side of ACT 3.0, the OpenMRS 3 edition of the ACT RHD registry. It re-evaluates patient
flags every day, keeps a patient list per flag, tells a client which form and question are missing
behind a flag, computes each patient's prophylaxis adherence and next due date, and loads the
distribution's report descriptors at startup.

It replaces the ACT 2.0 Critical Data Flags screen, and was called rhdflags until it took over the
reportdescriptorloader module too. The flag code has nothing RHD-specific in it: the flags
themselves are configuration in the distribution. The adherence calculation is ACT 2.0's secondary
prophylaxis rule; the concepts it reads are global properties, defaulting to the ACT forms'.

## Why it exists

patientflags evaluates a flag when the flag is saved and when a patient's clinical data is saved.
A flag that becomes true only because time passed, such as an injection now overdue or a patient
not seen for 210 days, never fires until someone happens to touch that patient.

patientflags has its own `PatientFlagTask`, but it cannot be registered with the scheduler, and its
admin rebuild page cannot be scripted from platform 2.6.0.

## What it does

- **Daily refresh.** The task **RHD Patient Flag Refresh** re-evaluates every enabled flag and
  writes only the rows that changed. It first recomputes the prophylaxis adherence below, as a flag
  may read it (the distribution's RHD prophylaxis overdue flag reads the next due date).
- **Refresh on demand.** `POST /ws/rest/v1/actcore/refresh` runs that refresh now, as the scheduler's
  daemon user, and answers once it has finished with `flagsFailed`, `listsFailed` and
  `adherenceFailed`, or with `refreshed` false when another run was already going. `GET` says when
  it last finished (`actcore.refresh.lastFinished`, written even by a run in which some flags failed)
  and whether one is running. ACT's admin page calls it.
- **A list per flag.** Each flag is mirrored into a patient list of the same name. ACT home's
  worklist tiles count them, and the registry's RHD flag filter lists their patients: the worklists
  that replace the ACT 2.0 Critical Data Flags screen.
- **Gap look-up.** `GET /ws/rest/v1/actcore/gap?patient=<uuid>&flag=<uuid>` lists, for a flag that
  stands for missing data, each encounter and question still missing an answer. The ACT frontend
  app (`openmrs-esm-act-app`) shows these in its Missing data workspace, with Open form.
- **Prophylaxis adherence.** The task **RHD Prophylaxis Adherence Refresh** recomputes, every day,
  each patient's adherence and next due date into the table `actcore_prophylaxis_adherence`, which
  the registry and care cascade reports read.
- **Prophylaxis summary.** `GET /ws/rest/v1/actcore/prophylaxis?patient=<uuid>` gives a patient's
  regimen, last dose, next due date, status (`overdue`, `dueToday`, `dueSoon`, `ok` or `none`, or null
  for an oral regimen whose supply end is unknown) and how many of the last six months' injections were
  on time. It replays the patient's saved forms when asked, as the nightly refresh does, so a dose
  saved today counts at once.
- **Next steps.** `GET /ws/rest/v1/actcore/nextsteps?patient=<uuid>` lists what to do for a patient
  at this visit: each step's `key`, `form`, `title`, `reason`, `isNew` and `done`, from the rules
  below. The ACT frontend app shows them on the patient summary.
- **Report descriptors at startup.** The reporting module reads
  `reporting.loadReportsFromConfigurationAtStartup` while it starts, before Initializer sets the
  distribution's value, so on a fresh database it loads no descriptors. This module requires reporting
  and is aware of Initializer, so it starts after both, and loads the descriptors in
  `configuration/reports/reportdescriptors` itself when that property is true. It logs whether it
  loaded, skipped or failed. Remove this once the reporting module does it
  ([mherman22/openmrs-module-reporting#1](https://github.com/mherman22/openmrs-module-reporting/issues/1)).

### Next steps

The v2 prototype's rules, each over the patient's saved forms, ACT Core's dose status and their flags:

| Step | When |
| --- | --- |
| Give BPG injection, or Record oral prophylaxis visit (by the regimen in force) | the dose is overdue, due today or due within 2 days |
| Consultation visit | the patient has the RHD prophylaxis not prescribed flag |
| Echocardiogram | the latest echo before this visit is over 12 months old, or there is none; an echo is dated by its Date of Echocardiogram, else by its encounter |
| Procedures and outcomes | the patient has the RHD 30-day follow-up due flag |
| INR review | the patient has the RHD INR review due flag; the reason gives the latest Next INR Date |
| Consultation visit, new | an echo was saved this visit |
| Consultation visit, new | the latest BPG form reporting anaphylaxis, even with a later BPG form, has no consultation saved after it before this visit; the BPG step is on hold until the consultation is done, also after a dose given since |
| Consultation visit, new | on an oral regimen, the latest estimate is below 80%, with no consultation saved after it before this visit |

- **One step per form.** A later rule's reason replaces an earlier one's, except that a new
  consultation, or referral, gives the first unreviewed trigger's reason, else the first trigger's.
- **Review.** An echo saved this visit, an anaphylaxis or a low estimate (the trigger) is reviewed
  by a consultation saved after it, by encounter date and then by which was saved first. A trigger
  reviewed outside this visit raises no step. A trigger reviewed this visit still lists its step, done
  unless another trigger is unreviewed; an anaphylaxis reviewed this visit lifts the BPG hold.
- **Done.** A step is done once an encounter of its form's encounter type is in the patient's active
  visit; for BPG, once that visit records a Date of Injection, so a withheld injection stays to do; for
  a new consultation or referral, once this visit reviewed every trigger behind it. A form saved this
  visit that no rule asked for, and an echo saved this visit, is listed done as "Entered this visit".
  A done step is not new.
- **Who sees what.** A step is offered only to a user with Add Encounters and its encounter type's edit
  privilege, as the frontend's forms list decides. A user who may not record the consultation gets
  Refer to clinician for the anaphylaxis and adherence rules, and nothing for the other consultation
  rules. The flag rules need View Patient Flags.
- **Order.** Steps to do come first, new ones first among them.

### Gap look-up

A SQL flag supports the look-up when its criteria return one row per gap: the patient, the
encounter uuid and the question uuid. patientflags raises the flag from the first column, so one
query both raises the flag and lists its gaps.

    SELECT DISTINCT e.patient_id, e.uuid, q.uuid FROM encounter e
      JOIN concept q ON q.uuid = '<Perfusion Issues uuid>'
    WHERE e.voided = 0 AND ...
      AND NOT EXISTS (SELECT 1 FROM obs o WHERE o.encounter_id = e.encounter_id
                      AND o.concept_id = q.concept_id AND o.voided = 0)

The response:

    {"configured": true,
     "results": [{"encounter": "<uuid>", "encounterDatetime": "2026-03-14T09:00:00.000+0000",
                  "form": {"uuid": "<uuid>", "display": "Procedures and Outcomes"},
                  "concept": {"uuid": "<uuid>", "display": "Perfusion Issues"}}]}

Rules for such criteria, because the module narrows them to one patient the way patientflags does
(it appends `and <alias>.patient_id = <id>` to the first `<alias>.patient_id`):

- the first `<alias>.patient_id` must be the row's patient;
- nothing may follow the conditions: no `GROUP BY`, `HAVING`, `ORDER BY`, `LIMIT` or `UNION`;
- a top-level `OR` must be in parentheses;
- the message should not use `${n}` placeholders, since they would read the first gap's columns.

`"configured": false` means the criteria returned no gap columns for this patient, for example a
flag that is not a gap flag, or one the patient does not match now. Rows naming another patient's
encounter, a voided encounter or something that is not a concept are dropped. Suspicious shapes are
logged as warnings that name the column and type, never the value. Days pending are counted from
`encounterDatetime`.

### Prophylaxis adherence

A port of ACT 2.0's adherence (is4r-rhd-cdk `query_handlers/adherence_utils.py`). Its calculation,
`calculate_adherence_and_injection_date`, is ported step for step:

- It reads the prophylaxis prescribed on the latest consultation that records any, by its Date of
  Consultation Visit (else its encounter's date), leaving out prescriptions with a date stopped, then
  every injection date and every oral adherence form.
- Over at most the last 365 days, each injection regimen's window counts the days its injections
  came later than the regimen's interval (Q14, Q21, Q28) allows. Adherence is 1 minus the days late
  over the days prescribed.
- An oral regimen instead takes the clinician's latest estimate in its window.
- The next due date runs from the latest injection by the latest regimen's interval, or on an oral
  regimen from the latest estimate by its prescription duration.

ACT 2.0 kept the result on the patient's record and recomputed it only at certain times: whenever a
consultation, BPG delivery or oral adherence form was saved, and each night for a patient whose stored
regimen was an injection. The nightly run skipped a patient whose injections are entered in batches
(every 3, 6 or 12 months, as the latest consultation records it; blank means continuous) until that
long had passed since the later of their latest consultation and last injection. `AdherenceReplay` replays those times day by day, taking each
record to have been saved on the date it records, and each row is the calculation as of the last day
ACT 2.0 would have run it. So a regimen that starts after the consultation recording it counts from
the first run on or after its start date, as it did in ACT 2.0.

A regimen with no injection, or no estimate, recorded by then gets no adherence. Here this differs
from ACT 2.0, which left the previous regimen's values on the record: under the new regimen's name
they would be wrong. An injection regimen with no injection still gets a next due date, the first
injection's, one interval after the regimen starts, as the chart shows it: the registry shows its
status, and the due list lists the patient from that day. ACT 2.0 kept the previous regimen's due
date, if there was one. An oral regimen with no estimate gets no due date.

Two rare forms also differ: an oral adherence form with neither an estimate nor a prescription
duration is not read at all, and a prescription with a start date but no regimen is taken as oral.
ACT 2.0 counted the first as a save and treated the second as no regimen.

A prescription answered None (`actcore.adherence.noProphylaxisAnswers`) is no course at all, so a patient whose latest
consultation prescribes None has no regimen: no row in the table, and the chart's status is none. ACT 2.0 took None
as an oral regimen; the registry already shows such a patient as No prescription, and the chart now agrees.

It also differs in not asking whether the patient is still active. ACT 2.0's nightly run covered only
active patients, so a patient who left the registry kept the adherence of their last save; here their
adherence goes on changing. The care cascade counts only open RHD Registry enrolments, so it is not
affected; the registry's adherence column for a completed enrolment is.

Each row holds the patient, the latest regimen and its injection interval (0 for an oral regimen),
adherence as a fraction, the last injection or estimate, the next due date and when it was computed.
Each run replaces every row in one transaction, so a failed run leaves the previous rows in place.

The same run fills `actcore_injection_timing`: one row per BPG injection given while a course was in
force, with whether it was on time, timed as the chart's injection history times it (no later than the
previous injection, or the course's start, plus the course's interval). Reports count on-time injections
over a period from it, so they agree with the chart. An injection given with no course in force is left
out, as the chart leaves it untimed.

`AdherenceCalculationParityTest` holds the port to ACT 2.0 itself. Its fixture,
`api/src/test/resources/adherence-parity.json`, is what ACT 2.0's own function returns, with today
pinned, for every call in its `test_adherence.py`, for prescriptions either side of the 365-day
cutoff, and for 400 random histories, recorded by running that function unchanged from the ACT 2.0
source. It is fixed data: the build and tests need no Python. `AdherenceReplayOracleTest` holds the
replay to ACT 2.0's record-keeping written out as ACT 2.0 ran it, a stored record updated on each save
and on each nightly run it allowed, over 3000 random histories in which regimens start before and after
the consultations that record them.


| Decision | Why |
| --- | --- |
| A separate module, not a patched patientflags | Works with stock patientflags 3.0.10, so the distribution needs no fork. Fixes for the two underlying patientflags defects (a schedulable task, reconcile instead of rebuild) are drafted but not yet proposed upstream; if they land, this module keeps the lists and sheds most of its reconciliation. |
| Every ACT 2.0 rule is an ordinary SQL patient flag | Rules change by editing configuration, not by releasing code, and they show wherever O3 shows flags. |
| Nothing RHD-specific in the flag code | Flags, tags, priorities and messages live in the distribution's Initializer files. |
| The module registers its own scheduled task | Initializer has no domain for scheduler tasks. The first run is five minutes after the module first starts, then daily; the scheduler owns the interval after that. |
| The module starts after Initializer (`aware_of_module`) | On a fresh database the first run then finds the flags Initializer created (#7). |
| Write only what changed | Rows and memberships are reconciled, not rebuilt, so a refresh does not churn the database. |
| `date_created` is not used as "flag raised on" | patientflags deletes and re-inserts a patient's rows on every clinical save, so that date resets. Days pending come from the encounter date instead. |
| Lists are built from live flag rows | The list shows exactly what the chart shows. Voided flag rows are ignored (#1). |
| A list is tied to its flag by uuid, not by name | A cohort attribute (`Source patient flag`) holds the flag's uuid. A renamed flag renames its list, and a hand-made cohort with the same name is never touched. |
| Leaving a list ends the membership, it does not void it | The cohort REST resource counts voided memberships when it rejects duplicates. |
| A disabled or untagged flag keeps an empty list; a deleted flag's list is voided | The list is there if the flag comes back; a flag recreated under the same uuid, as Initializer does, gets its list back (#3). |
| Gaps come from the flag's own criteria (#13, #14) | A second gap query per flag drifted from its flag twice. One query cannot disagree with itself. |
| A refresh asked for during another is skipped (#9) | Two runs at once would race on the same rows and lists. |
| A failing flag message does not stop the flag clearing (#9) | One bad flag should not leave stale rows for everyone. |
| Logging goes through core's `log4j2.xml` | No second logging system; one summary line per run at the default level. |
| The distribution creates View Patient Flags | patientflags checks this privilege but does not create it; the look-up requires it too. |
| Adherence is ported from ACT 2.0 unchanged | ACT 3.0 had no definition of "adherent" of its own; parity with ACT 2.0 keeps the registry's numbers comparable across the migration, quirks included. |
| Adherence is kept in a table, not written as obs | It is computed, not recorded by a clinician, and goes stale between runs; as obs it would show in the chart as if someone had recorded it. The reports join the table. |
| Adherence is recomputed daily and on demand, replaying ACT 2.0's saves and nightly runs | A BPG delivery shows in the registry after the next run; run the task to see it at once. The replay needs no state of its own, so a run gives the same rows whatever ran before it. |
| The latest consultation that records a prescription, not the latest consultation | ACT 2.0 read the latest consultation, by its date, and the first of a day. Here a consultation that leaves the prescriptions out does not drop the regimen the one before it recorded. |

How the ACT distribution uses the flags, for context: risk flags (overdue prophylaxis, lost to
follow-up) have priority `RHD High` and show red; missing data flags have priority
`RHD Data Quality` and show orange. The tag `RHD` opens Clinical forms from the chart and the tag
`Critical data` opens the Missing data workspace. Flags cannot be deleted from the chart.

## Requirements

OpenMRS platform 2.4.0 or later, patientflags 3.0.10, cohort 3.7.3, webservices.rest 2.40.0, reporting 2.1.0.

## Installing and running

Put the omod in the modules directory (or mount it in a distribution) and restart. The module
registers its tasks on first start; there is nothing else to set up. On every start it also corrects a task
saved under a class that has since moved, as the rhdflags tasks were when the module became actcore, and
reschedules it, so an upgraded database keeps refreshing.

To run the refresh now, a user with `Task: act.refreshFlags` can use ACT's admin page, which calls
`POST /ws/rest/v1/actcore/refresh`. From the command line (or `RHD Prophylaxis Adherence Refresh` for
adherence alone), with Manage Scheduler; it runs on the request thread, so use a superuser:

    curl -u admin:<password> -X POST -H 'Content-Type: application/json' \
      -d '{"action":"runtask","tasks":["RHD Patient Flag Refresh"]}' \
      http://<host>/openmrs/ws/rest/v1/taskaction

**Start** in Manage Scheduler only reschedules the task; it does not run it. If Initializer is
stopped, this module will not start on the next boot either; start Initializer first.

## Configuration

| Global property | Default | Meaning |
| --- | --- | --- |
| `actcore.listFlagTag` | empty | Only flags with this tag get a list; empty means every flag |
| `actcore.listCohortType` | `System List` | Cohort type for the lists; created if missing |
| `actcore.nextSteps.*` | the ACT forms' encounter types, flags and concepts; 2 days, 12 months, 80% | Each next step's encounter type, the flags and concepts its rules read, and the thresholds; see `config.xml` |
| `actcore.adherence.*` | the ACT forms' concepts | The concepts the adherence refresh reads, the regimen intervals and prescription durations as `uuid:days` pairs, and the regimen answers that prescribe nothing (None); see `config.xml` |

## Security

The refresh endpoint needs `Task: act.refreshFlags`, which the distribution creates; it then runs the
refresh with the daemon user's privileges, so grant it only to roles you would let recompute every
patient's flags.

Calling the next steps needs Get Observations; the module reads the patient's records on the caller's
behalf, and offers only the steps the caller may record.

Calling the gap look-up needs View Patient Flags, plus Get Patients, Get Encounters and Get
Concepts. The module runs a flag's criteria with SQL Level Access on the caller's behalf, as
patientflags does. Whoever can save a SQL flag therefore controls SQL that runs with that access, so
grant Manage Flags only to roles you would trust with SQL Level Access.

## Logging

Each run logs one line, at `warn` if part of it failed:

    Patient flag refresh finished in 0.4s: 10 flags evaluated, 3 rows raised, 1 cleared; lists
    1 created, 0 restored, 0 retired, 3 members added, 1 members ended

For per-list detail, on platform 2.4.4, 2.5.1, 2.6.0 and later, copy the platform's `log4j2.xml`
into the application data directory, add
`<Logger name="org.openmrs.module.actcore" level="info" />` to its `<Loggers>`, and restart.

## Building

    mvn clean install

The module is `omod/target/actcore-omod-*.omod`.

Each push to `main` publishes the SNAPSHOT, and the Release workflow publishes each release, to
DIGI-UW's GitHub Packages at `https://maven.pkg.github.com/digi-uw/openmrs-module-actcore`. A
release publishes as the `uwdigi` bot, which needs write access to this repository. To resolve the
artifacts from there, Maven needs a token with `read:packages`.

## License

[MPL 2.0 with the OpenMRS Healthcare Disclaimer](LICENSE).
