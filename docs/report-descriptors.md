# Report descriptors

ACT's reports are plain YAML and SQL files in the distribution, under
`distro/configuration/reports/reportdescriptors/` in DIGI-UW/openmrs-distro-referenceapplication.
There is no Java per report.

## How they are loaded

The reporting module's `org.openmrs.module.reporting.config.ReportLoader` reads every `.yml` file under
`{appDataDir}/configuration/reports/reportdescriptors/`, turns each into a report definition with an SQL
dataset, and saves it with its designs. It saves by UUID, so loading again updates a report rather than
adding a copy, and keeps the report's past runs. Initializer copies the distribution's configuration into
`{appDataDir}/configuration/` as the server starts.

The reporting module loads the descriptors itself only when
`reporting.loadReportsFromConfigurationAtStartup` is true, and it reads that property while it starts,
before Initializer has set the distribution's value. On a fresh database it therefore loads nothing.
**ACT Core does the loading instead**: it requires reporting and is aware of Initializer, so it starts
after both, and loads the descriptors when the property is true (`rhd_reporting.xml` in the distribution
sets it). See `ReportDescriptorLoader`.

## The YAML

```yaml
key: uniqueCamelCaseKey           # internal lookup key
uuid: "xxxxxxxx-..."              # stable UUID: the report is saved by it
name: "Human readable name"       # shown in the reporting UI
description: "What this exports"

parameters:
  - key: startDate                # referenced as :startDate in the SQL
    type: java.util.Date
    label: "Start Date"

datasets:
  - key: myDataset
    type: sql
    config: "sql/my_query.sql"    # relative to this YAML file's directory

designs:
  - type: csv                     # output format offered in the reporting UI
    uuid: "yyyyyyyy-..."          # stable UUID for the report design
```

| Parameter `type` | In the SQL |
| --- | --- |
| `java.util.Date` | `:startDate`, `:endDate` |
| `java.lang.Integer` | `:someParam` |
| `org.openmrs.Location` | `:location` |

| Design `type` | Renderer |
| --- | --- |
| `csv` | `CsvReportRenderer` |
| `xls` | `XlsReportRenderer` (needs a `template:` path) |

## SQL conventions

- Parameters take a colon prefix: `:startDate`, `:endDate`.
- SQL files sit in a `sql/` folder next to their `.yml` file.
- Look up concepts and encounter types by UUID in a sub-select, never by integer id, which differs
  between environments.
- To include the whole end date: `>= :startDate AND < DATE_ADD(:endDate, INTERVAL 1 DAY)`.
- Prophylaxis adherence and next due dates come from ACT Core's `actcore_prophylaxis_adherence` table,
  which the RHD Prophylaxis Adherence Refresh task fills nightly.

## ACT's reports

| Folder | Report | Parameters | One row per |
| --- | --- | --- | --- |
| `patients/` | RHD Patient List | `startDate`, `endDate` | patient enrolled in the RHD Registry: demographics, identifiers, diagnosis category, prophylaxis, BPG status, adherence, and the RHD flags whose lists they are on. The app's registry reads it. |
| `waitinglist/` | Procedural Waiting List | none | open interventional recommendation in each living RHD Registry patient's latest RHD Consultation Visit. A recommendation is open unless its Completed answer is true; `date_added` is the first consultation that recommended the same procedure since it was last completed. |
| `screenpositive/` | Screen Positive, Pending Confirmation | none | patient enrolled in the RHD Registry whose latest Category at Diagnosis is Screen + pending confirmatory echo: they screened positive, and no confirmatory echo has recategorised them. |
| `cascade/` | RHD Care Cascade | `endDate` | step: Active, Prescribed Prophylaxis, Oral, BPG, Initiated BPG, Adherent (adherence of 80% or more). ACT home shows Active, Prescribed Prophylaxis, Initiated BPG and Adherent. |
| `cascade/` | RHD Screening Cascade | `endDate` | step: Active, Screened positive (echo pending), Confirmed (echo resulted), Heart disease. |
| `visits/` | RHD Visits Report | `startDate`, `endDate` | visit with at least one RHD encounter: the encounter types in it, NYHA class, prophylaxis regimen. |
| `encounters/` | RHD Encounters Export | `startDate`, `endDate` | encounter of the RHD encounter types, with each type's key fields. |
| `inr/` | RHD INR Monitoring Export | `startDate`, `endDate` | INR reading (the form repeats up to 20 readings), with the target range and whether the reading is in range. |

## Adding a report

1. Make a folder, `reportdescriptors/myreport/`.
2. Write `myreport.yml` and `sql/myreport.sql`.
3. Give the report and its CSV design a new UUID each (`uuidgen`).
4. Restart the backend. Initializer copies the files, and ACT Core loads them.

The report then appears under Reporting, and the ACT app can read its dataset through reporting REST.

## Reference UUIDs

| Encounter type | UUID |
| --- | --- |
| RHD Consultation Visit | `c2503561-c00d-5460-8157-43d594472b4a` |
| RHD BPG Delivery | `04cf03db-3b8e-5020-84b0-50b06338767a` |
| RHD Echocardiogram | `730f5ec2-7102-55d0-8602-2d792844f245` |
| RHD Electrocardiogram | `64c3f35f-a3ec-59d6-8178-0ca9f068cda8` |
| RHD Hospital Admission | `181e0106-35d3-537a-a25e-18d3ebf61883` |
| RHD Pregnancy | `ce6b111e-9beb-5a91-91ad-ec5043976fd5` |
| RHD Oral Adherence | `55271793-ef37-58da-9d86-1d9092a5a809` |
| RHD Interventions and Outcomes | `c9b87090-8768-50be-987e-8ca0a8983429` |
| RHD INR Monitoring | `b4bb88a3-9a04-5142-85bd-bb63c270f632` |

| Patient identifier type | UUID |
| --- | --- |
| RHD ID (primary) | `240f85fa-46e1-540e-9234-2796c623f7ea` |
| External Patient ID | `810bfaee-85de-5a81-b79c-954774076594` |
| Alternate ID | `060a2595-da89-5509-8cf4-645a8e50ad8a` |
| National ID | `fdd6f720-743b-57fc-915a-87c0f571097b` |
