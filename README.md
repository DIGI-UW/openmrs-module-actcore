# OpenMRS ACT Core module

[![Build with Maven](https://github.com/DIGI-UW/openmrs-module-actcore/actions/workflows/build.yml/badge.svg)](https://github.com/DIGI-UW/openmrs-module-actcore/actions/workflows/build.yml)

The server side of ACT 3.0, the OpenMRS 3 edition of the ACT rheumatic heart disease (RHD) registry.
Its screens are in [openmrs-esm-act-app](https://github.com/DIGI-UW/openmrs-esm-act-app).

## What it adds

| Area | What |
| --- | --- |
| Patient flags | Re-evaluates flags daily, on every save and on demand, so time-based flags (such as overdue prophylaxis) fire on their own; a patient list per flag; flag dismissal; the missing data behind a flag |
| Prophylaxis | Each patient's adherence, next due date and on-time injections, ported from ACT 2.0 |
| Clinical summaries | A patient's prophylaxis status, next cardiology visit and next steps for this visit |
| Run charts | ACT 2.0's eight run chart metrics, per clinic, over time |
| Screening counts | Monthly screening totals per clinic, with sums across clinics and months |
| Research studies | Studies as programs, and the consent and death rules on study enrolment |
| Data downloads | Saved forms as rows, with identifiers withheld unless allowed, and a log of every download |
| Logs | What users changed and which patients they opened |
| Users and clinics | Limits site administrators to their own clinics' users, and lets administrators give the ACT roles |
| Reports | Loads the distro's report descriptors at startup |

The flag rules themselves are not in this module: they are SQL patient flags in the distro's configuration.

## REST endpoints

All under `/ws/rest/v1/actcore`.

| Endpoint | Does |
| --- | --- |
| `POST`, `GET refresh` | Run the flag and adherence refresh now, or see when it last ran |
| `GET gap?patient=&flag=` | The forms and questions missing behind a flag |
| `GET raisedflags?patient=`, `POST raisedflags/dismiss` | A patient's raised flags, and dismissing one |
| `GET prophylaxis?patient=` | Regimen, last dose, next due date, status and adherence |
| `GET cardiology?patient=` | The next cardiology visit date |
| `GET nextsteps?patient=` | What to do for the patient at this visit |
| `GET runchart?clinic=&startDate=&endDate=&points=` | Run chart metrics |
| `GET`, `POST screeningcounts`, `GET screeningcounts/summary` | Screening counts for a clinic and month, and their sums |
| `GET`, `POST studies`, `POST studies/<uuid>` | List, create and rename studies |
| `POST download`, `GET download`, `POST download/record` | Download a form's data, list past downloads, log a CSV the app made |
| `GET activity`, `GET reads` | What users changed, and which patients they opened |

## Scheduled tasks

| Task | Runs |
| --- | --- |
| RHD Patient Flag Refresh | Daily: recomputes adherence, then every enabled flag and its list |
| RHD Prophylaxis Adherence Refresh | Daily: adherence alone |

Both are registered on first start. To run one now, use the ACT app's Flags and adherence page, or
`POST /ws/rest/v1/taskaction` with `{"action":"runtask","tasks":["<task name>"]}` as a superuser.

## Writing a gap flag

For the gap look-up to list what is missing, a flag's SQL returns one row per gap: the patient, the
encounter uuid and the question uuid. Its first `<alias>.patient_id` must be the row's patient, nothing
may follow its conditions (no `GROUP BY`, `ORDER BY`, `LIMIT` or `UNION`), a top-level `OR` goes in
parentheses, and its message should not use `${n}` placeholders.

## Requirements

OpenMRS platform 2.4.0+, patientflags 3.0.10, cohort 3.7.3, webservices.rest 2.40.0 and reporting 2.1.0.
The module starts after Initializer, so stopping Initializer stops it too.

## Configuration

Global properties starting `actcore.`. The main groups are below; every property, with its default and
description, is in [`omod/src/main/resources/config.xml`](omod/src/main/resources/config.xml).

| Prefix | Sets |
| --- | --- |
| `actcore.listFlagTag`, `listCohortType`, `dismissFlagTag` | Which flags get lists and can be dismissed |
| `actcore.adherence.*` | The concepts, intervals and durations adherence reads |
| `actcore.consultation.*`, `actcore.nextSteps.*` | The questions, flags and thresholds behind cardiology visits and next steps |
| `actcore.runCharts.*` | The run chart report |
| `actcore.studies.*`, `actcore.studyTemplateProgram` | Study consent, opt-in and status rules |
| `actcore.download.*` | What counts as identifying in a download |
| `actcore.users.*` | The roles each administrator may give |

## Privileges

The distro creates these; grant them with care, as several run work with wider access on the caller's behalf.

| Privilege | Allows |
| --- | --- |
| `Task: act.refreshFlags` | Running the refresh (as the daemon user) |
| `Task: act.dismissFlags` | Dismissing a flag |
| `Task: act.enterScreeningCounts` | Saving screening counts (`App: act.screeningCounts` or `App: act.dashboard` to read them) |
| `Task: act.manageStudies` | Creating and renaming studies |
| `Task: act.downloadData`, `act.downloadResearchData`, `act.downloadIdentifiers` | Downloading data, research data only, and identifiers |
| `Task: act.allClinics` | Administering every clinic's users, not only one's own |

The gap look-up runs flag SQL with SQL Level Access, so grant Manage Flags only to roles you would trust
with that.

## Building

```sh
mvn clean install
```

The module is `omod/target/actcore-omod-*.omod`.

## License

[MPL 2.0 with the OpenMRS Healthcare Disclaimer](LICENSE).
