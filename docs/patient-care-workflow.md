# Patient care workflow

What happens to a patient in ACT 3.0 from the day they are registered: which form comes next, who
records it, and how ACT says what is due. The screens are the ACT frontend app
(`openmrs-esm-act-app`) with the distribution's configuration, using demo patients; no name or date
in them is real.

ACT does not make forms follow a fixed order. The patient summary's **Next steps for this visit** card
lists only the forms the patient needs today, each with its reason, from the rules in
[Next steps](../README.md#next-steps). The usual order for a new patient is:

1. **Sign in** at the clinic you are working at.
2. **Register** the patient.
3. **Patient Information**: the diagnosis.
4. **Consultation Visit**: the assessment and the prophylaxis prescription.
5. **Echocardiogram**, when there is none or the last is over 12 months old.
6. **Prophylaxis**, BPG or oral, at every due date, for as long as the patient is in care.
7. **A review consultation** whenever an echo, an anaphylaxis or low adherence calls for one.

Steps 6 and 7 repeat. Steps 3 to 5 can happen on the same day or across several visits.

## Contents

- [1. Sign in at a clinic](#1-sign-in-at-a-clinic)
- [2. Register the patient](#2-register-the-patient)
- [3. Record Patient Information](#3-record-patient-information)
- [4. First consultation](#4-first-consultation)
- [5. Echocardiogram](#5-echocardiogram)
- [6. Prophylaxis at every due date](#6-prophylaxis-at-every-due-date)
- [7. Reviews the data triggers](#7-reviews-the-data-triggers)
- [8. Following up the whole list](#8-following-up-the-whole-list)
- [Forms by role](#forms-by-role)
- [Worklists](#worklists)
- [Where the rules come from](#where-the-rules-come-from)

## 1. Sign in at a clinic

**Who:** everyone.

Sign in, then choose the location you are working at. Every form you record is saved at that
location, and every ACT page shows it at the top, beside today's date.

![ACT home for a clinician at Uganda Heart Institute Tertiary](images/workflow/home.jpg)

*ACT home for a clinician: quick actions, worklist counts, overdue consultations and the care cascade.*

## 2. Register the patient

**Who:** data clerk, community clinician, clinician.

**Register patient** on ACT home opens registration, in four sections:

- **Basic Info:** name, sex and date of birth. The ACT ID is generated.
- **Contact Details:** the address (County, Sub-county and Parish).
- **Relationships:** related people, such as a parent or caregiver.
- **RHD Registration:**
  - the assigned cardiac clinic and the primary care clinic;
  - whether consent was obtained, from whom, and the Date of Consent, which cannot be in the future;
  - the primary phone, whose phone it is, WhatsApp or Telegram, and the Text-It SMS settings.

![Create new patient page](images/workflow/register.jpg)

*Registration, as a data clerk at Acimi HCIII.*

## 3. Record Patient Information

**Who:** data clerk, clinician.

Patient Information holds the **diagnosis**: its category (such as RHD or acute rheumatic fever), its
priority, when it started, and how the case was detected. A patient found by screening also gets
**Screen +**. Yes asks for the screening site and the follow-up status, and lists the patient on
**Confirmatory echo due** until a confirmatory echo is recorded.

The diagnosis is what makes the patient need secondary prophylaxis. Once it is saved without a
prescription, the patient gets the **RHD prophylaxis not prescribed** flag, and Next steps asks for a
consultation.

![RHD Patient Information form](images/workflow/patient-info-form.jpg)

*RHD Patient Information opens beside the summary, from All forms on the Next steps card.*

## 4. First consultation

**Who:** clinician.

A newly diagnosed patient's summary asks for a **Consultation visit**, because no prophylaxis is
prescribed, and an **Echocardiogram**, because none is recorded. The flag under the card names the
missing data.

![A newly diagnosed patient's summary](images/workflow/new-patient.jpg)

*Each step's button opens its form. The flag "Secondary Antibiotic Prophylaxis missing on RHD
Consultation Visit" is the reason for the first step.*

The RHD Consultation Visit records the assessment and the **prophylaxis prescription**. The regimen
(BPG every 28, 21 or 14 days, or oral penicillin) sets every later due date. A consultation that
prescribes Warfarin also starts INR monitoring.

![The clinical forms list](images/workflow/all-forms.jpg)

*All forms lists every form this user may record, with when each was last completed.*

![RHD Consultation Visit form](images/workflow/consultation-form.jpg)

## 5. Echocardiogram

**Who:** clinician.

Next steps asks for an echo when the patient has **none recorded**, or when the last is **over 12
months old**. Saving one adds a **new** Consultation visit step: "Review the new echo result and update
the care plan".

![RHD Echocardiogram form](images/workflow/echo-form.jpg)

*RHD Echocardiogram, from Enter result on the Next steps card. The ECG has its own form under All forms.*

## 6. Prophylaxis at every due date

**Who:** community clinician, data clerk, clinician.

From the prescription, ACT Core works out when each dose is due. Next steps shows **Give BPG
injection** or **Record oral prophylaxis visit** from 2 days before the due date ("Due in 2 days",
"Due today") and keeps it while overdue ("Overdue: was due 12-Oct-2026"). The Prophylaxis card shows
the regimen, the last dose, the next due date, and how many recent injections were on time, or, for
oral, the last adherence estimate.

![A BPG patient whose dose is overdue](images/workflow/bpg-due.jpg)

![An oral patient whose visit is overdue](images/workflow/oral-due.jpg)

**Record BPG** opens BPG Delivery: the date of injection, the dose given, any reaction, the facility
and, if late, why. Ticking Anaphylaxis under Injection Tolerance asks whether BPG was given at this
visit. A withheld dose saves no injection date, so the patient stays due.

![RHD BPG Delivery form](images/workflow/bpg-form.jpg)

*Opening a form starts a visit when the patient has none, so the banner shows Active Visit.*

The chart's **Prophylaxis** page lists every injection and whether it was on time. **Enter
prophylaxis**, on ACT home, lists every patient on prophylaxis in one table, to record a day's
injections or catch up from paper without opening each chart.

![The Prophylaxis page](images/workflow/prophylaxis-page.jpg)

![Enter prophylaxis](images/workflow/enter-prophylaxis.jpg)

## 7. Reviews the data triggers

**Who:** clinician.

Next steps adds a **new** Consultation visit step when:

- an echo was saved this visit: review the result and update the care plan;
- the last BPG form reported anaphylaxis: review before the next dose. The BPG step is on hold until
  that consultation is saved;
- the last oral adherence estimate is below 80%: consider switching to BPG.

A user who may not record a consultation sees **Refer to clinician** instead. Next steps also asks for
**Procedures and outcomes** when the 30-day follow-up after a discharge is due, and for an **INR
review** when one is due or overdue.

![A new consultation step after low oral adherence](images/workflow/review-trigger.jpg)

*An oral patient at 50% adherence.*

## 8. Following up the whole list

**Who:** clinician, site administrator.

The chart handles one patient. These pages find the patients who need you:

- **Worklists:** one tile per list with its count, and the patients on the chosen one, each with a
  button to record what is due.
- **Confirmatory echo due:** screen-positive patients still waiting for an echo, with their screening
  site and follow-up status.
- **Registry:** every patient, filtered by status, clinic and diagnosis category, with BPG status,
  adherence and flags.
- **Reports:** the month's or quarter's active patients, doses due and overdue, and the on-time rate,
  by facility.

![Worklists](images/workflow/worklists.jpg)

![Confirmatory echo due](images/workflow/screen-positive.jpg)

![The registry](images/workflow/registry.jpg)

![Reports](images/workflow/reports.jpg)

## Forms by role

Each role records the forms the v2 prototype gives it. A form a user may not record is not offered.

| Form | Community clinician | Data clerk | Clinician, site and instance administrator |
| --- | --- | --- | --- |
| BPG Delivery, Oral Adherence | yes | yes | yes |
| Patient Information | | yes | yes |
| Consultation Visit, Hospital Admission, Echocardiogram, Electrocardiogram, Procedures and Outcomes, INR Monitoring, Pregnancy, Research Participation | | | yes |

![A community clinician's forms](images/workflow/community-forms.jpg)

*A community clinician's forms: BPG Delivery and Oral Adherence.*

![A data clerk's forms](images/workflow/clerk-forms.jpg)

*A data clerk's forms add Patient Information.*

![Home for a community clinician](images/workflow/community-home.jpg)

*A community clinician's home leads with recording prophylaxis and the patients due in the next 48 hours.*

## Worklists

Most worklists are the patients holding one flag; Due for prophylaxis, Confirmatory echo due and the
procedural waiting list are computed from the patients' records.

| Worklist | Who is on it |
| --- | --- |
| Due for prophylaxis | a BPG or oral dose is due soon, due today or overdue |
| Confirmatory echo due | screen positive, with no confirmatory echo yet |
| Cardiology follow-up due | the next review set at the last consultation is past or within 30 days |
| Lost to follow-up | no consultation and no prophylaxis for 210 days, with a prescription |
| RHD 30-day post-procedural follow-up due | discharged over 30 days ago, with no follow-up recorded |
| RHD pregnancy outcome due | the due date passed 30 days ago, with no delivery recorded |
| INR review due | on Warfarin, with the next INR date past or within 30 days |
| Procedural waiting list | recommended for a cardiac procedure, by procedure and urgency |

Other flags show on the patient's chart, under the Next steps card: RHD prophylaxis overdue, RHD no
data for 5 months, and the data-quality flags (prophylaxis not prescribed, INR target missing,
hospitalisation outcomes not recorded, a delivery outcome overdue, and a death recorded on a form but
not on the patient).

## Where the rules come from

- **Next steps** follow the ACT O3 v2 prototype ([#33](https://github.com/DIGI-UW/openmrs-module-actcore/issues/33), [#69](https://github.com/DIGI-UW/openmrs-module-actcore/pull/69)). ACT 2.0 had no next steps; its forms sat
  on the patient record with nothing saying which was due.
- **The data-quality flags** are ACT 2.0's six critical data rules:

  | ACT 2.0 rule | ACT 3.0 flag |
  | --- | --- |
  | 1. A patient diagnosed with RHD or ARF requires prophylaxis | RHD prophylaxis not prescribed |
  | 2. Warfarin prescribed without an INR target | RHD INR target missing |
  | 3. Hospitalisation outcomes due | RHD perfusion issues, site infection and bacterial sepsis not recorded |
  | 4. 30-day follow-up due | RHD 30-day follow-up due |
  | 5. Estimated due date overdue | RHD delivery outcome overdue |
  | 6. A form reports a death | RHD death not recorded on patient |

  ACT 2.0 ran rules 1, 2 and 6 only when a form was saved; ACT 3.0 runs every flag daily.
- **Lost to follow-up** and **no data for 5 months** are ACT 2.0's Needs Prophylaxis Data red and blue
  (210 and 150 days).
- **RHD prophylaxis overdue** was an ACT 2.0 registry filter, computed from the next due date. ACT 3.0
  makes it a flag.
- **Cardiology follow-up due** and **INR review due** are new in ACT 3.0, for the prototype's
  clinician screens. They read ACT 2.0's own fields: the next consultation interval and the Next INR
  Date.
- **Prophylaxis adherence and due dates** are ACT 2.0's secondary prophylaxis rule; see
  [Prophylaxis adherence](../README.md#prophylaxis-adherence).
- **Forms by role** follow the v2 prototype, chosen as the reference on 10 October 2026. ACT 2.0 also
  let community clinicians record Patient Information, Consultation Visit, Hospital Admission,
  Echocardiogram and Electrocardiogram.
