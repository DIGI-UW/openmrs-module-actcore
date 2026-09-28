# OpenMRS RHD Flags module

[![Build with Maven](https://github.com/mherman22/openmrs-module-rhd-flags/actions/workflows/build.yml/badge.svg)](https://github.com/mherman22/openmrs-module-rhd-flags/actions/workflows/build.yml)

A companion to the patientflags module. It re-evaluates flags every day, keeps a patient list per
flag, and tells a client which form and question are missing behind a flag.

It was written for ACT 3.0, the OpenMRS 3 edition of the ACT RHD registry, where it replaces the
ACT 2.0 Critical Data Flags screen. The code has nothing RHD-specific in it: the flags themselves
are configuration in the distribution.

## Why it exists

patientflags evaluates a flag when the flag is saved and when a patient's clinical data is saved.
A flag that becomes true only because time passed, such as an injection now overdue or a patient
not seen for 210 days, never fires until someone happens to touch that patient.

patientflags has its own `PatientFlagTask`, but it cannot be registered with the scheduler, and its
admin rebuild page cannot be scripted from platform 2.6.0.

## What it does

- **Daily refresh.** The task **RHD Patient Flag Refresh** re-evaluates every enabled flag and
  writes only the rows that changed.
- **A list per flag.** Each flag is mirrored into a patient list of the same name, shown under
  Patient lists. This is the worklist that replaces the ACT 2.0 Critical Data Flags screen.
- **Gap look-up.** `GET /ws/rest/v1/rhdflags/gap?patient=<uuid>&flag=<uuid>` lists, for a flag that
  stands for missing data, each encounter and question still missing an answer. The RHD frontend
  app (`openmrs-esm-rhd-app`) shows these in its Missing data workspace, with Open form.

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

## Design decisions

| Decision | Why |
| --- | --- |
| A separate module, not a patched patientflags | Works with stock patientflags 3.0.10, so the distribution needs no fork. Fixes for the two underlying patientflags defects (a schedulable task, reconcile instead of rebuild) are drafted but not yet proposed upstream; if they land, this module keeps the lists and sheds most of its reconciliation. |
| Every ACT 2.0 rule is an ordinary SQL patient flag | Rules change by editing configuration, not by releasing code, and they show wherever O3 shows flags. |
| Nothing RHD-specific in the code | Flags, tags, priorities and messages live in the distribution's Initializer files. |
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

How the ACT distribution uses the flags, for context: risk flags (overdue prophylaxis, lost to
follow-up) have priority `RHD High` and show red; missing data flags have priority
`RHD Data Quality` and show orange. The tag `RHD` opens Clinical forms from the chart and the tag
`Critical data` opens the Missing data workspace. Flags cannot be deleted from the chart.

## Requirements

OpenMRS platform 2.4.0 or later, patientflags 3.0.10, cohort 3.7.3, webservices.rest 2.40.0.

## Installing and running

Put the omod in the modules directory (or mount it in a distribution) and restart. The module
registers its task on first start; there is nothing else to set up.

To run the refresh now:

    curl -u admin:<password> -X POST -H 'Content-Type: application/json' \
      -d '{"action":"runtask","tasks":["RHD Patient Flag Refresh"]}' \
      http://<host>/openmrs/ws/rest/v1/taskaction

**Start** in Manage Scheduler only reschedules the task; it does not run it. If Initializer is
stopped, this module will not start on the next boot either; start Initializer first.

## Configuration

| Global property | Default | Meaning |
| --- | --- | --- |
| `rhdflags.listFlagTag` | empty | Only flags with this tag get a list; empty means every flag |
| `rhdflags.listCohortType` | `System List` | Cohort type for the lists; created if missing |

## Security

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
`<Logger name="org.openmrs.module.rhdflags" level="info" />` to its `<Loggers>`, and restart.

## Building

    mvn clean install

The module is `omod/target/rhdflags-omod-*.omod`.

## License

[MPL 2.0 with the OpenMRS Healthcare Disclaimer](LICENSE).
