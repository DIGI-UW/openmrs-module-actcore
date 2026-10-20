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
- **Refresh on save.** Once an encounter of a patient's is saved, voided, unvoided or purged, their
  adherence row is rewritten and their flags and lists re-evaluated; once a patient or their person
  is saved, voided or unvoided, their flags and lists are re-evaluated, as flags such as RHD death
  not recorded on patient and RHD consent date missing read the person. Both run after the commit,
  so a failure cannot roll the save back, and once per patient however many saves the transaction
  holds.
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
- **Dismissing a flag.** `POST /ws/rest/v1/actcore/raisedflags/dismiss` with a `patient` and a `flag`
  uuid dismisses a flag the patient matches now, as ACT 2.0's Critical Data Flags screen let a user
  remove a row. The flag is left out of the patient's flags, so the chart's banner, and off its list,
  until ACT Core finds that the patient no longer matches it; if they match it again later, it shows
  again. `GET /ws/rest/v1/actcore/raisedflags?patient=<uuid>` lists the patient's flags with
  `raisedSince`, the date ACT Core first found each raised, and whether the user may dismiss it. A
  patient joins a flag's list on that date, so the worklists' time on the list counts from it too.
- **Prophylaxis adherence.** The task **RHD Prophylaxis Adherence Refresh** recomputes, every day,
  each patient's adherence and next due date into the table `actcore_prophylaxis_adherence`, which
  the registry and care cascade reports read.
- **Prophylaxis summary.** `GET /ws/rest/v1/actcore/prophylaxis?patient=<uuid>` gives a patient's
  regimen, last dose, next due date, status (`overdue`, `dueToday`, `dueSoon`, `ok` or `none`, or null
  for an oral regimen whose supply end is unknown) and how many of the last six months' injections were
  on time. It replays the patient's saved forms when asked, as the nightly refresh does, so a dose
  saved today counts at once.
- **Next cardiology visit.** `GET /ws/rest/v1/actcore/cardiology?patient=<uuid>` gives `nextDue`,
  the date of the patient's next cardiology review: the latest RHD Consultation Visit's Next
  Consultation Time Amount after its encounter's date, in the Time Period recorded on the same
  encounter, as the registry report's `next_consultation_date` and the RHD cardiology follow-up due
  flag read it. It is null for a patient not enrolled in the registry now or who has died, or with no
  review date recorded. The ACT frontend app colours the patient banner's cardiology visit pill by it.
- **Next steps.** `GET /ws/rest/v1/actcore/nextsteps?patient=<uuid>` lists what to do for a patient
  at this visit: each step's `key`, `form`, `title`, `reason`, `isNew` and `done`, from the rules
  below. The ACT frontend app shows them on the patient summary.
- **Research studies.** `GET /ws/rest/v1/actcore/studies` lists the studies with their Study status
  states. A study is a program with a workflow on the same concept as the template program's
  (`actcore.studyTemplateProgram`). `POST` with a `name` of at most 50 characters creates one with a
  copy of that workflow, and `POST /ws/rest/v1/actcore/studies/<uuid>` renames it and its concept.
- **Study enrolments.** Whichever page enrols, a new study enrolment is refused unless the patient gave
  registry consent, would like to be considered for research and has a Study ID, and an open one is
  refused for a patient who has died. One saved without a status starts in the study's initial one.
  Saving a patient as dead, or merging another patient into them, ends their open study enrolments in
  Inactive - Death on their death date, or on the enrolment's latest status date when that is later.
  As for the RHD Registry, an enrolment may not overlap another in the same study while either is
  open, and merging two patients folds such a pair into one.
- **Download.** ACT 2.0's Download page: `POST /ws/rest/v1/actcore/download` with a `form` uuid, its
  `fields` (each a `concept` uuid and a `label`) and an optional `startDate` and `endDate`
  (`yyyy-MM-dd`, both inclusive, by the encounter's date) answers `headers` and `rows`: one row per saved
  form, with the ACT ID, sex, age at the form's date, its date and location, and each field's answers,
  repeated answers joined by `; `. Only a user who may download identifiers also gets the given and
  family names, birth date, identifying ID numbers, phones and the fields that identify the patient
  (`actcore.download.identifyingConcepts`); for anyone else those fields are left out and their labels
  listed in `withheld`. A research download, for a user who may download only research data, holds
  only patients whose research opt-in (`actcore.studies.optInAttributeType`) is Yes, and never
  identifiers; with the opt-in or its Yes unset, it holds no one. Every download is recorded in the
  table `actcore_download_log` before its rows are sent, and fails if it cannot be: who, when, the form,
  the fields given and withheld, the dates, the number of rows and whether it had identifiers or only
  research participants. `GET /ws/rest/v1/actcore/download?limit=<n>` lists the latest, newest first:
  100 by default, at most 500. The audit log module (auditlogweb) records the patients a user opens and
  the forms they save, but has no way for another module to record an event of its own, so downloads
  are kept here.
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
| Patient Information | the patient has no Patient Information form, so no diagnosis is recorded |
| Consultation visit, or Refer to clinician | the patient has the RHD prophylaxis not prescribed flag; done once a consultation is saved this visit |
| Echocardiogram | the latest echo before this visit is over 12 months old, or there is none; an echo is dated by its Date of Echocardiogram, else by its encounter |
| Procedures and outcomes | the patient has the RHD 30-day follow-up due flag |
| INR review | the patient has the RHD INR review due flag; the reason gives the latest Next INR Date |
| Consultation visit, new | an echo was saved this visit |
| Consultation visit, new | the latest BPG form reporting anaphylaxis, even with a later BPG form, has no consultation saved after it before this visit; the BPG step is on hold until the consultation is done, also after a dose given since |
| Consultation visit, new | on an oral regimen, the latest estimate is below 80%, with no consultation saved after it before this visit; it suggests switching to BPG unless a BPG form reported anaphylaxis or a BPG prescription records it, such as its Reason Stopped, when it asks a clinician to review the plan |

- **One step per form.** A later rule's reason replaces an earlier one's, except that a new
  consultation, or referral, gives the latest unreviewed trigger's reason in rule order, else the
  latest trigger's.
- **This visit.** The encounters in the patient's active visit dated today. The ACT app never ends a
  visit, so one left open holds forms from earlier days, and those count as before this visit: an echo
  from an earlier day raises no review, and a consultation from one reviews its triggers outside this
  visit, so they raise no step.
- **Review.** An echo saved this visit, an anaphylaxis or a low estimate (the trigger) is reviewed
  by a consultation saved after it, by encounter date and then by which was saved first. A trigger
  reviewed outside this visit raises no step. A trigger reviewed this visit still lists its step, done
  unless another trigger is unreviewed; an anaphylaxis reviewed this visit lifts the BPG hold.
- **Done.** A step is done once an encounter of its form's encounter type is saved this visit; for BPG,
  once this visit records a Date of Injection of today, so a withheld injection, or an earlier dose
  recorded today, stays to do; for a new consultation or referral, once this visit reviewed every
  trigger behind it. A form saved this visit that no rule asked for, and an echo saved this visit, is
  listed done as "Entered this visit". A done step is not new.
- **Who sees what.** A step is offered only to a user with Add Encounters and its encounter type's edit
  privilege, as the frontend's forms list decides. A user who may not record the consultation gets
  Refer to clinician for the not prescribed, anaphylaxis and adherence rules, and nothing for a new
  echo; their held BPG step says a clinician must review before the next dose, rather than to complete
  the consultation. The flag rules need View Patient Flags.
- **The dose waits.** While the patient's Patient Information step, consultation or Refer to clinician
  is to do and listed for the signed-in user, the dose step is left out; a dose recorded this visit is
  still listed done. A step the user is not listed does not hold the dose back, so a community
  clinician, who may not record Patient Information, still gets an overdue dose. A BPG step on hold
  is not left out: it offers no dose, so it stays to say why. An oral supply waits only for Patient
  Information, since a consultation or referral to do, such as a low adherence review, is no reason
  to leave the patient without medicine. `actcore.nextSteps.doseAfterEarlierSteps` set to false lists
  the dose anyway.
- **On hold.** A BPG step on hold has `onHold` true and no form, so it offers nothing to record; the
  withheld injection can still be recorded from the forms list.
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
`calculate_adherence_and_injection_date`, is ported step for step but for how injection adherence
counts days:

- It reads the prophylaxis prescribed on the latest consultation that records any, by its Date of
  Consultation Visit (else its encounter's date), leaving out prescriptions with a date stopped, then
  every injection date and every oral adherence form, by its Date of Adherence Estimate (else its
  encounter's date), so a visit entered later from paper keeps its own date.
- Injection adherence is the days covered by prescribed treatment over the days of prescribed
  treatment, as the program defines it in [AR-102](https://uwdigi.atlassian.net/browse/AR-102),
  over at most the last 365 days. An injection covers the interval's (Q14, Q21, Q28)
  days starting on its own, so with Q28 an injection on day 0 covers days 0 to 27 and a next
  dose on day 31 leaves 3 days late. Counting starts at the regimen's start, which covers as an
  injection would, so a first injection is due one interval after it, as the chart times it. An
  injection before the 365 days still covers the days after it. A change of injection regimen
  carries the last injection's cover on, judging the next dose by the new interval; after an oral
  regimen, counting starts again at the injection regimen's start. A dose due today is not yet
  late.
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
date, if there was one. An oral regimen with no oral adherence form since it started gets its first
visit's due date, `actcore.adherence.oralFirstDueDays` after the start (0 is the start date itself),
so a patient prescribed oral prophylaxis who never comes for a supply is due, then overdue, rather
than invisible; left empty, as ACT 2.0 behaved, such a regimen gets no due date.

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

`AdherenceCalculationParityTest` holds the port to ACT 2.0 itself, the next due date to ACT 2.0's and
injection adherence to the days covered rule written out day by day. Its fixture,
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
| `date_created` is not used as "flag raised on" | patientflags deletes and re-inserts a patient's rows on every clinical save, so that date resets. `actcore_raised_flag` keeps the date ACT Core's evaluation first found the flag raised, until it finds it cleared (#206). The gap look-up's days pending still come from the encounter date. |
| A dismissal is kept by ACT Core and applied when flags are read, not by voiding the row (#206) | patientflags deletes the patient's live rows and writes new ones on its own thread on every encounter save, after or before ACT Core's pass. Advice on `FlagService.getPatientFlags` leaves dismissed flags out whatever rows that thread wrote, and the list sync leaves dismissed patients off. Only ACT Core's evaluation lifts a dismissal, once the patient no longer matches. |
| Lists are built from live flag rows | The list shows exactly what the chart shows. Voided flag rows are ignored (#1). |
| A list is tied to its flag by uuid, not by name | A cohort attribute (`Source patient flag`) holds the flag's uuid. A renamed flag renames its list, and a hand-made cohort with the same name is never touched. |
| Leaving a list ends the membership, it does not void it | The cohort REST resource counts voided memberships when it rejects duplicates. |
| A disabled or untagged flag keeps an empty list; a deleted flag's list is voided | The list is there if the flag comes back; a flag recreated under the same uuid, as Initializer does, gets its list back (#3). |
| Gaps come from the flag's own criteria (#13, #14) | A second gap query per flag drifted from its flag twice. One query cannot disagree with itself. |
| A refresh asked for during another is skipped (#9) | Two runs at once would race on the same rows and lists. |
| A patient saved during a full adherence refresh is recomputed, then their flags, as it finishes (#94) | It reads every patient before it rewrites the table, so a row saved in between would be put back as it was. |
| Two rows of one flag for a patient are rewritten as one (#94) | patientflags evaluates a saved patient on a thread of its own, which can insert beside a refresh. |
| A failing flag message does not stop the flag clearing (#9) | One bad flag should not leave stale rows for everyone. |
| Logging goes through core's `log4j2.xml` | No second logging system; one summary line per run at the default level. |
| The distribution creates View Patient Flags | patientflags checks this privilege but does not create it; the look-up requires it too. |
| Adherence is ported from ACT 2.0 but for counting injection adherence as days covered | ACT 3.0 had no definition of "adherent" of its own, so the rest keeps ACT 2.0's. Injection adherence follows the definition the program asked for in [AR-102](https://uwdigi.atlassian.net/browse/AR-102): days covered over days prescribed. Like ACT 2.0 it judges the first injection from the regimen's start, as the chart and `actcore_injection_timing` do. ACT 2.0 also restarted at each change of injection regimen and at the 365-day cutoff, so a late dose just after either went uncounted. |
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
| `actcore.dismissFlagTag` | `Critical data` | Only flags with this tag can be dismissed; empty means every flag |
| `actcore.nextSteps.*` | the ACT forms' encounter types, flags and concepts; 2 days, 12 months, 80% | Each next step's encounter type, the flags and concepts its rules read, and the thresholds; see `config.xml`. If you changed `actcore.nextSteps.encounterTypes`, add `patientInfo:ffdc883f-f99f-496a-83f9-2c3c581e1af0` to it for the Patient Information step |
| `actcore.studyTemplateProgram` | ACT's retired study template program | The program each new study copies its workflows from; a program with a workflow on the same concept as one of its own is a study |
| `actcore.studies.*` | the ACT attribute types and concepts | The registry consent and research opt-in person attribute types, their Yes answer, the Study ID program attribute type, and the status open enrolments end in at death. Empty one to drop its rule, for example while migrating ACT 2.0 data; the rule on an open enrolment for a patient who has died has no property |
| `actcore.adherence.*` | the ACT forms' concepts | The concepts the adherence refresh reads, the regimen intervals and prescription durations as `uuid:days` pairs, and the regimen answers that prescribe nothing (None); see `config.xml` |
| `actcore.consultation.*` | the RHD Consultation Visit's Next Consultation Time Amount and CIEL Time Period (1732) | The two questions the next cardiology visit reads; the period's answers must be CIEL days, weeks, months or years (1072, 1073, 1074, 1734); see `config.xml` |
| `actcore.download.identifyingIdentifierTypes` | the National ID, External Patient ID and Alternate ID | The identifier types a download leaves out without identifiers, and gives as columns with them; the ACT ID is the preferred identifier of any other type |
| `actcore.download.identifyingAttributeTypes` | the primary and secondary phones, their owners and the consent person | The person attribute types a download gives as columns only with identifiers |
| `actcore.download.identifyingConcepts` | the RHD Patient Information form's Home Address, Village/Area of Residence, School Name, Additional information to help locate patient and Relocation Area | The questions a download gives only with identifiers, so never in a research download; without, it leaves them out and lists their labels in `withheld` |

## Security

The refresh endpoint needs `Task: act.refreshFlags`, which the distribution creates; it then runs the
refresh with the daemon user's privileges, so grant it only to roles you would let recompute every
patient's flags.

Listing the research studies needs Get Programs. Creating and renaming them needs
`Task: act.manageStudies`, which the distribution creates; the module then saves the study's concept and
program with Manage Concepts and Manage Programs on the caller's behalf, so grant it only to roles you
would let add concepts and programs.

A download needs `Task: act.downloadData`, or `Task: act.downloadResearchData` for a research download
only, plus Get Forms, Get Concepts, Get Encounters, Get Observations and Get Patients. With
`Task: act.downloadData`, `Task: act.downloadIdentifiers` keeps the identifiers. The distribution
creates the three. Listing the downloads needs the audit log module's View Read Audit Logs.

Calling the next cardiology visit needs Get Observations.

Calling the next steps needs Get Observations; the module reads the patient's records on the caller's
behalf, and offers only the steps the caller may record.

Listing a patient's raised flags needs View Patient Flags. Dismissing one also needs
`Task: act.dismissFlags`, which the distribution creates; the module then evaluates the flag and updates
its list on the caller's behalf.

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
