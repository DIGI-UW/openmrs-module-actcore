# OpenMRS RHD Flags module

[![Build with Maven](https://github.com/mherman22/openmrs-module-rhd-flags/actions/workflows/build.yml/badge.svg)](https://github.com/mherman22/openmrs-module-rhd-flags/actions/workflows/build.yml)

Scheduled maintenance for patient flags, and for the patient lists built from them, and a look-up
of the data missing behind a flag.

## Why this exists

The patientflags module evaluates a flag when its definition is saved, and otherwise through
AOP advice on `EncounterService`, `ObsService`, `OrderService`, `PatientService`,
`ConditionService` and `ProgramWorkflowService`. That covers any flag whose criteria turn on a
data change. It does not cover a criterion that becomes true because time passed, such as an
injection that is now overdue for its regimen interval, or a patient who has not been seen for
210 days. Nothing is written to those records on the day they start matching, so the flag never
fires until somebody happens to touch the patient for an unrelated reason.

The module also ships a `PatientFlagTask`, but it is a `DaemonToken` runnable rather than an
`org.openmrs.scheduler.Task`, so it cannot be registered with the scheduler, and from platform 2.6.0
its admin rebuild page sits behind CSRFGuard so it cannot be driven from a script.

## What it does

**RHD Patient Flag Refresh** runs once a day. It re-evaluates every enabled flag, writes only the
rows that changed, and mirrors each flag into a patient list of the same name. It registers itself
with the scheduler on first start, because Initializer has no domain for `scheduler_task_config`.

Writing only the difference means this task resets a row's `date_created` only when the row's
message has changed. patientflags itself resets it far more often: its AOP advice deletes and
re-inserts a patient's rows on every clinical write, and saving a flag rebuilds all of that flag's
rows. So `date_created` is not a record of how long a flag has been raised, and this task alone
cannot make it one. Fixes for that are proposed upstream; see below.

### Flag rows

A row whose message has changed, such as a SQL flag whose `${n}` placeholders now evaluate to
other values, is rewritten. The task therefore evaluates the message of every matching patient on
each run, which for a SQL flag with placeholders is one query per patient.

Voided patient flag rows are ignored: a patient counts as flagged only through a live row.

### Lists

Membership comes from the live flag rows rather than from re-running the criteria. Removal from a
list that is kept end-dates a membership rather than voiding it, because the cohort module's REST
resource counts a voided row when it rejects a duplicate.

Each list carries its flag's uuid, so a rename renames the list, and a cohort someone made by hand
under the same name is left alone (the cohort module rejects the duplicate name, so that flag gets
no list until one of the two is renamed). A rename onto such a name keeps the list's old name. When
flags swap or rotate names, one list steps aside to a temporary name so the others can move: a two-
flag swap settles within two runs, and a longer rotation takes more.

A flag that is disabled, retired or no longer tagged keeps its list with every membership ended. A
flag that is deleted has its list voided, memberships included; a `Source patient flag` cohort
attribute holding the flag's uuid marks the lists this module made, so a hand-made cohort is never
voided. A flag recreated under the deleted flag's uuid, as Initializer does, gets that list back
with the flag's current patients, unless another cohort already holds the flag's name. A list
someone voided stays voided.

### Gap look-up

A flag says which patients match, not what is missing. For a flag that stands for missing data,
the module can list the gaps behind it, one entry per encounter and question, so a client can
open the encounter that still needs the answer:

    GET /ws/rest/v1/rhdflags/gap?patient=<patient uuid>&flag=<flag uuid>

    {"configured": true,
     "results": [{"encounter": "<uuid>", "encounterDatetime": "2026-03-14T09:00:00.000+0000",
                  "form": {"uuid": "<uuid>", "display": "Procedures and Outcomes", "links": [...]},
                  "concept": {"uuid": "<uuid>", "display": "Perfusion Issues", "links": [...]}}]}

`form` and `concept` are the REST module's reference representations.

Each flag that supports this has a query in the global property `rhdflags.gapQuery.<flag uuid>`.
The query names the patient as `:patientId`, which the module replaces with the patient's id, and
returns rows of (encounter uuid, uuid of the question whose answer is missing). For example, for a
flag raised when a discharged patient's Perfusion Issues answer is missing:

    SELECT e.uuid, q.uuid FROM encounter e JOIN concept q ON q.uuid = '<Perfusion Issues uuid>'
    WHERE e.patient_id = :patientId AND e.voided = 0 AND ...
      AND NOT EXISTS (SELECT 1 FROM obs o WHERE o.encounter_id = e.encounter_id
                      AND o.concept_id = q.concept_id AND o.voided = 0)

A flag without a query answers `"configured": false` with no results. The response carries only
the patient's own unvoided encounters and real concepts: a row naming another patient's encounter,
a voided one, one of a type the caller may not view, or something that is not a concept uuid is
left out. A cell that is not an encounter or concept uuid, such as `e.encounter_id` returned in
place of `e.uuid`, is also logged as a warning, since a query returning only such cells otherwise
answers exactly as one that finds no gaps. The warning names the column and the Java type of what
it held, never the value. A query without `:patientId`, or one that returns a row of fewer than
two columns, is refused. Days pending can be counted from `encounterDatetime`.

Calling it takes View Patient Flags, the privilege that shows flags on the chart, along with the Get
Patients, Get Encounters and Get Concepts privileges for the data it returns. patientflags 3.0.10
checks View Patient Flags but does not create it, so a distribution creates that privilege and
grants it to the roles that should see flags. For a caller without Get Forms, `form` is null. The
module reads the flag definition and the gap query, and runs the query with SQL Level Access, on the
caller's behalf.

Saving a `rhdflags.gapQuery.*` property therefore amounts to SQL Level Access. The query can be any
SELECT, and what it finds, in any table the database account can read, can reach the caller even
though the response carries only the patient's encounters, their forms and dates, and concept names:
whether the response carries a row reveals it, and a query that fails answers with the database's
error message, which can quote the value it failed on. So grant Manage Global Properties only to
roles you would trust with SQL Level Access. Manage Flags is much the same: patientflags asks for
SQL Level Access before it accepts SQL criteria only in its legacy flag form, and a flag saved
through its API runs whatever criteria it holds.

### Upstream

Two of the reasons this module exists are defects in patientflags rather than facts of life, and
both have fixes proposed against it: a schedulable task, and generation that reconciles instead of
deleting and rebuilding. If those land, this module keeps the list syncing and sheds most of the
reconciliation logic.

## Requirements

OpenMRS platform 2.4.0 or later, patientflags 3.0.10, cohort 3.7.3, webservices.rest 2.40.0.

## Installing

Build the module, then either drop the omod into the running instance:

    cp omod/target/rhdflags-omod-*.omod /openmrs/data/modules/

or, in a distribution, mount it alongside the other modules and restart the backend. Once it starts
it registers its own scheduled task, so there is nothing to configure to get it running. The first
run is five minutes after the module first starts, and daily from then on. Where Initializer is
installed the module starts after it, and Initializer creates a distribution's flags while it
starts, so on a first boot that run already finds them. Stopping Initializer keeps this module from
starting on the next boot, as it does any module that is aware of it; start Initializer again and
then this module, or set Initializer's `initializer.started` global property to `true` and restart.
Uninstalling Initializer is not enough on a distribution image, which copies its modules back in at
every start. To run the task sooner:

    curl -u admin:<password> -X POST -H 'Content-Type: application/json' \
      -d '{"action":"runtask","tasks":["RHD Patient Flag Refresh"]}' \
      http://<host>/openmrs/ws/rest/v1/taskaction

**Start** in **Manage Scheduler** does not do this: it reschedules the task for its next daily run.
A run asked for while another is in progress is skipped, with a warning in the log. The request
still answers as though it ran, and a skipped scheduled run still gets a last execution time in
Manage Scheduler.

Flags whose criteria have become true show up on the patient chart as usual, and each flag also
appears under **Patient lists** as a list of the patients currently carrying it.

## Configuration

| global property | default | meaning |
| --- | --- | --- |
| `rhdflags.listFlagTag` | empty | only give a list to flags carrying this tag; empty means all |
| `rhdflags.listCohortType` | `System List` | cohort type for lists this module creates; created if missing, unless a voided type has that name |

The task runs once a day. The scheduler owns the interval once the task exists, so change it in
**Administration > Manage Scheduler** rather than here.

## Logging

Every run reports itself in one line:

    Patient flag refresh finished in 0.4s: 10 flags evaluated, 3 rows raised, 1 cleared; lists
    1 created, 0 restored, 0 retired, 3 members added, 1 members ended

A run that could not finish part of its work reports at `warn` instead, saying how many flags and
lists failed, and logs the cause of each at `error`. A run asked for while another is in progress
logs only that it was skipped, at `warn`. The platform's packaged `log4j2.xml` puts `org.openmrs`
at `warn`, so those are the lines you get without configuring anything.

For the rest, including which list changed and by how much, give `org.openmrs.module.rhdflags` a
logger of its own in core's logging configuration. On platform 2.4.4, 2.5.1, 2.6.0 and later, core
reads a `log4j2.xml` from the application data directory in place of the packaged one, so copy the
platform's `log4j2.xml` there and add

    <Logger name="org.openmrs.module.rhdflags" level="info" />

to its `<Loggers>`, then restart. A `log.level` entry for `org.openmrs.module.rhdflags` then changes
that logger alone. Without it, core applies such an entry to the nearest logger its configuration
defines, `org.openmrs`, and everything under it moves too. On these platforms core applies a
`log.level` entry when it is saved, not at startup, so after a restart the logger is back at the
level in `log4j2.xml`; set the level you want to keep there.

On 2.4.0 to 2.4.3 and on 2.5.0, core reads no `log4j2.xml` from the application data directory, so
this route is not available. On those platforms core applies `log.level` at every startup as well
as on a save, and an entry for `org.openmrs.module.rhdflags` sets the level of all of `org.openmrs`.

## Building

    mvn clean install

The module is `omod/target/rhdflags-omod-*.omod`.

## License

[MPL 2.0 with the OpenMRS Healthcare Disclaimer](LICENSE).
