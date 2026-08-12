# Product Certification — Test Scenario Catalogue

Scope: end-to-end certification of the Schoolsoft platform, from first
enquiry through daily operations to academic-year closure and student exit.
Written against the module surface that exists today (`apps/api`, six
frontends) plus the lifecycle stages the product must cover regardless of
whether they are built yet.

**How to read this.** Each scenario is a certification unit: it passes or it
fails, and a release is certified when every P1 scenario passes. Scenarios
tagged `GAP-nn` cannot pass today — the capability is missing, not broken.
Those gaps are listed in §23 and mirrored into `BACKLOG.md`.

Priority key: **P1** = blocks a school going live · **P2** = blocks a term ·
**P3** = quality bar.

Persona key: `PA` parent · `ST` student · `TE` teacher · `HM` head/principal ·
`AD` school admin/office · `AC` accounts · `HQ` chain HQ · `DR` driver ·
`LB` librarian · `PL` platform operator.

---

## 1. Tenant & School Onboarding (CERT-TEN)

| ID | Scenario | Priority |
|----|----------|----------|
| TEN-01 | `PL` provisions a new chain; chain schema is created and migrated to head version; chain admin can log in and sees zero schools. | P1 |
| TEN-02 | Chain admin creates School A (CBSE) and School B (Cambridge) under one chain; each gets its own theme, campus list, and grade ladder. | P1 |
| TEN-03 | Provisioning is re-run for an existing chain (retry after partial failure) — no duplicate schema, no data loss, migration version unchanged. | P1 |
| TEN-04 | School onboarded with a curriculum cloned from a platform template (`/clone-from-template`); the clone is independent — editing the school copy does not mutate the template. | P1 |
| TEN-05 | Two schools in the same chain define a subject with the same code `MATH`; both persist, neither collides. | P2 |
| TEN-06 | A chain is suspended / plan downgraded — feature flags gate the modules the plan excludes; existing data stays readable. | P2 |
| TEN-07 | Multi-campus school: sections, staff, and timetables are campus-scoped; a campus-level admin sees only their campus. `GAP-24` | P2 |
| TEN-08 | School theme/branding change propagates to parent app, public site, and generated documents without a redeploy. | P3 |

## 2. Academic Year & Structure Setup (CERT-ACAD)

| ID | Scenario | Priority |
|----|----------|----------|
| ACAD-01 | `AD` creates AY 2026-27 with start/end dates and marks it current; the previous AY is automatically no longer current (one-current-per-school invariant holds). | P1 |
| ACAD-02 | Terms T1/T2 created inside the AY; a term with dates outside the AY window is rejected; overlapping terms are rejected. `GAP-25` | P1 |
| ACAD-03 | Two academic years with overlapping date ranges are rejected. `GAP-25` | P2 |
| ACAD-04 | Grades 1–12 created with sort order; sections A/B created per grade per AY, each bound to a curriculum and strategy code. | P1 |
| ACAD-05 | A Cambridge section (`CIE-IGCSE`) and a CBSE section (`CBSE-CCE-2024`) coexist in the same school; assessment and report-card behaviour differ by strategy without config forks. | P1 |
| ACAD-06 | Section capacity is set to 30; the 31st enrolment is blocked or explicitly flagged as an over-capacity override with a reason. `GAP-10` | P1 |
| ACAD-07 | Subject teachers assigned via `section_subject_teacher`; a section with no primary teacher for a subject is surfaced as a setup warning before term start. | P2 |
| ACAD-08 | Curriculum tree (`curriculum_node` + `learning_outcome`) is authored and versioned; a mid-year curriculum edit does not retroactively invalidate lesson plans already delivered. | P2 |
| ACAD-09 | Student elects optional/elective subjects (IGCSE option blocks, Class 11 stream): marks, timetable, and report card follow the student's subject set, not the section's. `GAP-05` | P1 |
| ACAD-10 | School working-day pattern configured (5-day vs 6-day week, alternate-Saturday) and honoured everywhere attendance or fee due dates are computed. `GAP-01` | P1 |

## 3. Leads & Admissions Funnel (CERT-ADM)

| ID | Scenario | Priority |
|----|----------|----------|
| ADM-01 | Anonymous visitor submits the public-site enquiry form → application created in state `lead` with source `website`; acknowledgement sent to the guardian. | P1 |
| ADM-02 | Walk-in lead entered by `AD` with source `walkin`; same pipeline, different source attribution in funnel reporting. | P1 |
| ADM-03 | Guardian tracks their application by application number + phone (`/admissions/track`) and sees stage, pending documents, and next action — without an account. | P1 |
| ADM-04 | Full happy path: `lead → application_started → document_pending → fee_pending → review → test_scheduled → test_done → offered → accepted → enrolled`; each transition is recorded with actor and timestamp. | P1 |
| ADM-05 | Invalid transition (e.g. `lead → enrolled`) is rejected with a clear error; the state machine is authoritative on the server, not the UI. | P1 |
| ADM-06 | Entrance test scheduled for a cohort, scores captured in bulk, and a rank list produced against available seats per grade. | P2 |
| ADM-07 | Offer issued with `offer_expires_on`; expiry passes with no acceptance → application auto-moves to `lapsed` and the seat returns to the pool. `GAP-11` | P1 |
| ADM-08 | Applicant declines / lapses → the next candidate on the waitlist is offered automatically or flagged for `AD` action. `GAP-11` | P2 |
| ADM-09 | Duplicate enquiry from the same guardian phone for the same child is detected and merged rather than creating a second funnel entry. `GAP-11` | P2 |
| ADM-10 | Seat confirmed → student record created, guardian linked, enrolment created against the correct section, application marked `enrolled` and linked via `converted_student_id`. No orphan student on failure (transactional). | P1 |
| ADM-11 | Sibling of an existing student applies: the family is linked, sibling concession is applied to the fee plan, and the guardian sees both children under one login. `GAP-09` | P1 |
| ADM-12 | Admission documents (birth certificate, previous TC, address proof, immunisation) uploaded, marked verified/rejected per document with reviewer identity; enrolment is blocked while a mandatory document is unverified. `GAP-16` | P1 |
| ADM-13 | Admission fee collected at `fee_pending`; a failed payment leaves the application in `fee_pending` (not silently advanced) and is retryable. | P1 |
| ADM-14 | Rejected application: guardian is notified, data is retained per policy, and the applicant does not appear in any academic roster. | P2 |
| ADM-15 | Funnel analytics: conversion by source and by stage, drop-off counts, time-in-stage — reconciles with the raw application list. | P2 |
| ADM-16 | Mid-year admission (student joins in October): pro-rated fee schedule, attendance denominator starts from the join date, report card reflects partial attendance. | P1 |

## 4. Enrolment & Student Records (CERT-ENR)

| ID | Scenario | Priority |
|----|----------|----------|
| ENR-01 | Student enrolled into a section for an AY; exactly one `active` enrolment exists at any time. | P1 |
| ENR-02 | Admission number and roll number generated per school policy; both unique within their scope; roll numbers renumber correctly after a section transfer. `GAP-26` | P1 |
| ENR-03 | Mid-year section change (A → B): old enrolment ends, new one starts, attendance and marks history stays attached to the student and is attributable to the correct section per date. | P1 |
| ENR-04 | Guardian linkage: primary guardian, secondary guardian, and a non-guardian authorised pickup contact are distinguishable; only guardians get app access. | P1 |
| ENR-05 | Divorced/separated parents: both guardians receive communications, one is flagged as fee-payer, and custody restrictions on pickup are enforceable. `GAP-18` | P2 |
| ENR-06 | Student profile carries medical conditions, allergies, blood group, and emergency contacts; these surface to the class teacher and on the trip/driver view. `GAP-17` | P1 |
| ENR-07 | Student photo and ID card generation for a whole section. `GAP-16` | P3 |
| ENR-08 | Withdrawn student is re-admitted the following year — prior history is preserved and visible, no duplicate student record is created. `GAP-02` | P2 |
| ENR-09 | Bulk import of 500 students from CSV: validation errors reported per row, valid rows committed or the whole batch rejected (documented choice), no partial guardian orphans. `GAP-23` | P1 |
| ENR-10 | Student search across name, admission no, guardian phone returns results scoped strictly to the caller's school. | P2 |

## 5. Calendar, Holidays & Events (CERT-CAL)

| ID | Scenario | Priority |
|----|----------|----------|
| CAL-01 | `AD` publishes the annual school calendar: gazetted holidays, local holidays, vacation blocks, working Saturdays, exam weeks. `GAP-01` | P1 |
| CAL-02 | Attendance cannot be marked on a declared holiday; the day is excluded from the working-day denominator in every attendance percentage. `GAP-01` | P1 |
| CAL-03 | Timetable does not render periods on a holiday; teacher app shows "school closed" rather than an empty schedule. `GAP-01` | P1 |
| CAL-04 | Unplanned closure declared same-day (weather, strike): already-marked attendance for that day is voided or converted, parents are notified, and the day drops out of the denominator. `GAP-01` | P1 |
| CAL-05 | Grade-specific holiday (only Grades 11–12 attend during a board-exam week) applies to the right cohort only. `GAP-01` | P2 |
| CAL-06 | Campus-specific holiday in a multi-campus school applies to one campus. `GAP-01`, `GAP-24` | P2 |
| CAL-07 | Calendar visible in the parent app and on the public site; a mid-year calendar amendment reflects within the app's cache window. `GAP-01` | P2 |
| CAL-08 | Events (sports day, annual day, PTM) published with RSVP; announcement reach and read counts reconcile with the recipient roster. | P2 |

## 6. Timetable (CERT-TT)

| ID | Scenario | Priority |
|----|----------|----------|
| TT-01 | Bell schedule / period master defined per grade band (period count, start/end, breaks); slots are created against it rather than each carrying free-text times. `GAP-12` | P1 |
| TT-02 | Weekly timetable built for a section; a slot that double-books a teacher is rejected with a clear message. (Implemented — `TimetableRepository` clash check.) | P1 |
| TT-03 | A slot that double-books a room is rejected. `GAP-12` | P2 |
| TT-04 | Teacher weekly load exceeds the configured maximum → warning at publish time. `GAP-12` | P3 |
| TT-05 | Mid-year timetable revision: new slots with `effective_from` set to the change date; historical attendance and lesson plans still resolve against the timetable that was in force on their date. | P1 |
| TT-06 | Teacher's personal timetable view (`/teachers/{id}`) matches the sum of their section slots, across sections and grades. | P1 |
| TT-07 | Parent/student view shows today's periods with subject and teacher, and hides periods after school hours. | P2 |
| TT-08 | Teacher absent for the day → substitution assigned; the substitute sees the period in their app, the section sees the substitute, and attendance for that period is markable by the substitute. `GAP-07` | P1 |
| TT-09 | Exam week: regular timetable is suppressed and the exam timetable is shown instead. `GAP-06`, `GAP-01` | P1 |
| TT-10 | Deleting a section with a live timetable is blocked or cascades cleanly with no orphaned slots. | P2 |

## 7. Daily Attendance (CERT-ATT)

| ID | Scenario | Priority |
|----|----------|----------|
| ATT-01 | Class teacher marks day-level attendance for a section; re-submitting the same day updates rather than duplicating (day-level uniqueness holds — V010). | P1 |
| ATT-02 | Subject teacher marks period-level attendance; day-level and period-level records for the same date coexist without conflict. | P1 |
| ATT-03 | Absent mark triggers a parent notification within the configured SLA; a student marked present then corrected to absent does not send a duplicate. | P1 |
| ATT-04 | Late / half-day / excused / on-leave statuses each compute correctly into the monthly attendance percentage. | P1 |
| ATT-05 | Approved student leave automatically materialises as `leave` attendance for the covered dates rather than requiring the teacher to mark it. `GAP-08` | P1 |
| ATT-06 | Attendance correction after lock: an amendment requires approval, retains the prior value, and is audit-logged with actor and reason. `GAP-08`, `GAP-27` | P1 |
| ATT-07 | Biometric/RFID device pushes student events (`/{deviceId}/events/student`); duplicate events for the same student/day are idempotent; device clock skew does not create a next-day record. | P1 |
| ATT-08 | Device offline for a day, then replays a backlog — records land on their true dates and do not overwrite manual corrections made in the interim. | P1 |
| ATT-09 | Teacher marks attendance offline (poor connectivity) and it syncs later; conflicting server-side edits are surfaced rather than silently overwritten. | P2 |
| ATT-10 | Attendance percentage for a student joining mid-year, and for one who withdrew mid-year, uses the correct enrolment window as the denominator. | P1 |
| ATT-11 | Chronic-absence report: students under the threshold over a rolling window, per section and per grade; matches raw records. | P2 |
| ATT-12 | Attendance is not markable for a future date, and not markable for a date outside the student's enrolment window. | P1 |
| ATT-13 | Staff attendance: in/out punches, half-day, WFH; a staff member with an approved leave shows `leave` not `absent`. `GAP-08` | P2 |

## 8. Assessment, Marks & Report Cards (CERT-ASMT)

| ID | Scenario | Priority |
|----|----------|----------|
| ASMT-01 | CBSE flow: PT1 / HY / Annual assessments created per the strategy, with components and weights summing to 100%. | P1 |
| ASMT-02 | Cambridge flow: papers/components with coursework weighting; grade computed on the CIE scale, not the CBSE scale. | P1 |
| ASMT-03 | Weight validation: components whose weights do not sum to the assessment total are rejected or flagged before marking opens. | P1 |
| ASMT-04 | Bulk mark entry (`/mark/bulk`) for a full section; a mark above `max_marks` is rejected; a blank is distinguishable from a zero. | P1 |
| ASMT-05 | Student absent for an exam: recorded as absent (not zero), excluded from averages, and shown as AB on the report card. `GAP-06` | P1 |
| ASMT-06 | Assessment lifecycle `draft → scheduled → in_progress → marking → locked → published`; marks are not editable once locked. | P1 |
| ASMT-07 | Locked marks are unlocked by an authorised role only, with reason captured and audit trail. `GAP-06`, `GAP-27` | P1 |
| ASMT-08 | Re-evaluation request from a parent → moderated mark supersedes the original, both are retained, report card regenerates. `GAP-06` | P2 |
| ASMT-09 | Exam timetable created for a term: no student sits two papers in the same slot, rooms and invigilators are allocated, hall tickets are issued. `GAP-06` | P1 |
| ASMT-10 | Report card generated for a section: subject marks, grades, attendance summary, co-scholastic ratings, teacher remarks, and the promotion decision. `GAP-13` | P1 |
| ASMT-11 | Class rank / percentile / grade boundaries computed consistently and reproducibly. `GAP-29` | P2 |
| ASMT-12 | Report card locked (`/report-cards/{id}/lock`) then published; parents see it in the app; a locked card cannot be silently regenerated. | P1 |
| ASMT-13 | Report card for a student with an elective set differing from the section shows only their subjects. `GAP-05` | P1 |
| ASMT-14 | Report card for a mid-year joiner shows only terms they were present for, with an explanatory note rather than blank rows. | P2 |
| ASMT-15 | Marks published while a fee dues block is active — the school-configurable policy (withhold vs release) is honoured. | P2 |

## 9. LMS, Homework & Content (CERT-LMS)

| ID | Scenario | Priority |
|----|----------|----------|
| LMS-01 | Teacher publishes an assignment with a due date and attachment; the section's students and their parents see it. | P1 |
| LMS-02 | Student submits before the deadline; a submission after the deadline is accepted but flagged late per policy. | P1 |
| LMS-03 | Teacher grades a submission with feedback; the grade appears to the student and parent. | P1 |
| LMS-04 | Quiz authored with questions/options/answers, attempted by a student, auto-scored, and reattempt policy enforced. (Authoring UI absent — see backlog.) | P2 |
| LMS-05 | Lesson plan created against curriculum nodes, moved through its status workflow, and visible to the HM for review. | P2 |
| LMS-06 | Content item uploaded (video/PDF) via the upload ticket flow; download ticket is time-bound and does not leak cross-tenant. | P1 |
| LMS-07 | External LTI tool launch carries the correct roles and returns a grade to the right assessment. (Stubbed — see backlog.) | P3 |
| LMS-08 | Assignment for a section is not visible to students of another section or school. | P1 |

## 10. Fees, Payments & Accounting (CERT-FEE)

| ID | Scenario | Priority |
|----|----------|----------|
| FEE-01 | Fee structure defined per grade with heads (tuition, transport, lab, exam) and an instalment schedule; a new AY structure is created without mutating last year's. | P1 |
| FEE-02 | Invoices generated in bulk for a grade/cycle from the fee structure; re-running the generation does not duplicate invoices. `GAP-09` | P1 |
| FEE-03 | Concession/scholarship applied to a student reduces the invoice correctly and is visible as a line, not a silent adjustment. | P1 |
| FEE-04 | Sibling concession applied across a family; the family can pay a combined invoice. `GAP-09` | P2 |
| FEE-05 | Parent pays online end-to-end: order created, gateway callback captured, invoice moves `open → paid`, receipt issued. `GAP-09` (no checkout initiation exists) | P1 |
| FEE-06 | Duplicate gateway callback with the same idempotency key does not double-credit. (Idempotency key is unique on `payment` — verify the service path honours it.) | P1 |
| FEE-07 | Partial payment moves the invoice to `partial`; the balance and the next-due amount are correct in the parent app. | P1 |
| FEE-08 | Offline payment (cash/cheque) recorded by `AC` with receipt number; cheque bounce reverses the payment and restores the dues, with a ledger entry. `GAP-09` | P1 |
| FEE-09 | Due date passes unpaid → invoice moves to `overdue` automatically and a reminder goes out on the configured cadence. `GAP-09` | P1 |
| FEE-10 | Late fee applied per policy after a grace period; waivable by an authorised role with reason and audit. `GAP-09`, `GAP-27` | P2 |
| FEE-11 | Refund on withdrawal: pro-rated per policy, credit note issued, ledger balanced, invoice status `refunded`. `GAP-09` | P1 |
| FEE-12 | Transport fee follows the student's route assignment; changing or dropping the route mid-year adjusts subsequent invoices. `GAP-09`, `GAP-30` | P2 |
| FEE-13 | GST computed on applicable heads only; e-invoice IRN fields populate when the integration is live; a failed IRN does not block the receipt. | P2 |
| FEE-14 | Day-book / collection report for a date range reconciles to the sum of payments and to the ledger. | P1 |
| FEE-15 | Outstanding-dues report by grade/section/student matches invoice balances; a student with dues is flagged at year-end clearance. | P1 |
| FEE-16 | Fee arrears carry forward into the next AY's opening balance at rollover. `GAP-02`, `GAP-09` | P1 |
| FEE-17 | Two parents pay the same invoice simultaneously — no over-credit, second payment is either rejected or recorded as an advance. | P2 |

## 11. Communications (CERT-COMM)

| ID | Scenario | Priority |
|----|----------|----------|
| COMM-01 | Announcement targeted at a school / grade / section reaches exactly that audience; read receipts tracked. | P1 |
| COMM-02 | Teacher–parent thread: message, reply, and unread counts behave correctly on both apps. | P1 |
| COMM-03 | Push notification delivered to a registered device; token invalidation on logout/uninstall stops delivery. | P1 |
| COMM-04 | Notification failure (invalid token, provider error) is retried per policy and visible in the dispatch log. `GAP-21` | P2 |
| COMM-05 | Guardian sets channel preferences and quiet hours; non-urgent notifications respect them, emergency ones override. `GAP-21` | P2 |
| COMM-06 | Emergency broadcast (school closure) reaches all guardians across channels within the SLA, with delivery stats. | P1 |
| COMM-07 | WhatsApp template message sent with the correct approved template and variables. (Adapter stubbed — see backlog.) | P2 |
| COMM-08 | A parent of a withdrawn student stops receiving section communications from the withdrawal date. `GAP-03` | P1 |
| COMM-09 | PTM slots published, parent books a slot, teacher sees their booked schedule, double-booking prevented. `GAP-20` | P2 |
| COMM-10 | Message content is scoped per tenant — no cross-school thread visibility under any role. | P1 |

## 12. Transport (CERT-TRN)

| ID | Scenario | Priority |
|----|----------|----------|
| TRN-01 | Route with ordered stops created; student assigned to a stop; route roster matches assignments. | P1 |
| TRN-02 | Route capacity vs assigned students enforced against vehicle capacity. `GAP-30` | P2 |
| TRN-03 | Driver starts a trip, checks students in at stops, ends the trip; parents get boarding/alighting notifications. | P1 |
| TRN-04 | GPS pings stream during a trip; geofence entry/exit fires the arrival notification once, not repeatedly. | P1 |
| TRN-05 | Student marked present in class but never boarded the bus (or vice versa) → mismatch alert to `AD`. `GAP-30` | P2 |
| TRN-06 | Stop or route change mid-year takes effect from a date, adjusts the roster, and adjusts transport fees. `GAP-30`, `GAP-09` | P2 |
| TRN-07 | Driver app works through a connectivity dead zone: check-ins queue and sync without duplicates. | P2 |
| TRN-08 | Vehicle breakdown / driver substitution mid-route — trip reassigned, parents informed. | P3 |
| TRN-09 | Withdrawn student is removed from the route roster automatically. `GAP-03` | P2 |

## 13. Library (CERT-LIB)

| ID | Scenario | Priority |
|----|----------|----------|
| LIB-01 | Title and copies catalogued; copy issued to a student with a due date; return closes the issue. | P2 |
| LIB-02 | Per-grade issue limits enforced; issuing beyond the limit is blocked. `GAP-22` | P3 |
| LIB-03 | Overdue copy accrues a fine that posts to the student's fee ledger. `GAP-22` | P2 |
| LIB-04 | Lost/damaged copy charged and removed from circulation. `GAP-22` | P3 |
| LIB-05 | Year-end clearance blocks a student with an unreturned copy. `GAP-03`, `GAP-22` | P2 |

## 14. Staff Operations (CERT-STF)

| ID | Scenario | Priority |
|----|----------|----------|
| STF-01 | Staff record created with role(s); a teacher without a `teacher` staff role cannot be assigned to a section. | P1 |
| STF-02 | Staff leave applied, approved/rejected by the right approver, and reflected in staff attendance. | P2 |
| STF-03 | Approved teacher leave surfaces the affected periods for substitution assignment. `GAP-07` | P1 |
| STF-04 | Staff exit: access revoked on the last working day, section assignments reassigned, historical marks/attendance attribution preserved. `GAP-27` | P1 |
| STF-05 | A teacher's app shows only their own sections, students, and marks — never another teacher's. | P1 |
| STF-06 | HM/principal view aggregates across all sections in their school and nothing beyond it. | P1 |

## 15. Roles, Access & Security (CERT-SEC)

| ID | Scenario | Priority |
|----|----------|----------|
| SEC-01 | OTP login for a guardian: valid OTP issues a token; expired/reused OTP is rejected; brute-force is rate-limited. | P1 |
| SEC-02 | Access token expiry mid-session → transparent refresh or clean redirect to login, on every app. (Known open item.) | P1 |
| SEC-03 | Screen-access model (`/me/screens`) gates every frontend route; a hand-crafted API call to a screen the role lacks is rejected server-side, not just hidden in the UI. | P1 |
| SEC-04 | Row-level security holds: a token for School A cannot read School B's students, marks, invoices, or messages, including via ID enumeration. | P1 |
| SEC-05 | Chain HQ role can read across the chain's schools but cannot read another chain. | P1 |
| SEC-06 | Platform admin actions are separately authenticated and fully audit-logged. | P1 |
| SEC-07 | File upload/download tickets are tenant-scoped, expiring, and non-guessable. | P1 |
| SEC-08 | High-risk mutations (mark unlock, fee waiver, enrolment status change, role grant) are audit-logged with actor, before/after, and reason. `GAP-27` | P1 |
| SEC-09 | DPDP: guardian consent captured, withdrawable; data export and erasure requests are servable within the statutory window. `GAP-23` | P1 |
| SEC-10 | A parent can see only their own children — including after a custody change or a guardian unlink. | P1 |

## 16. Academic Year Closure & Rollover (CERT-YEC)

| ID | Scenario | Priority |
|----|----------|----------|
| YEC-01 | Year-end readiness check: unpublished assessments, unlocked report cards, unmarked attendance days, and outstanding dues are all listed before closure is allowed. `GAP-02` | P1 |
| YEC-02 | Next AY created with grades, sections, and curriculum bindings cloned from the current year, editable before activation. `GAP-02` | P1 |
| YEC-03 | Bulk promotion: all students in Grade 5A with a `promote` decision move to Grade 6 sections in the new AY; old enrolments close as `promoted`. `GAP-02` | P1 |
| YEC-04 | Retention: a student marked `detained` stays in the same grade in the new AY with the correct new section and a preserved history. `GAP-02` | P1 |
| YEC-05 | Section reshuffle at promotion (5A+5B → 6A/6B/6C by a rule or manual allocation) respects capacity and keeps siblings/twins per policy. `GAP-02`, `GAP-10` | P2 |
| YEC-06 | Rollover carries forward: fee arrears, library dues, transport assignment, guardian links, medical info. `GAP-02` | P1 |
| YEC-07 | Rollover is idempotent and reversible: a re-run does not double-enrol, and a mistaken rollover can be rolled back before the new AY is activated. `GAP-02` | P1 |
| YEC-08 | Closed AY becomes read-only: attendance, marks, and invoices for a closed year cannot be edited without an explicit reopen by an authorised role. `GAP-14` | P1 |
| YEC-09 | Historical reporting still works against closed years (attendance %, report cards, fee collection) after two rollovers. | P1 |
| YEC-10 | Teachers' section assignments do not silently carry into the new AY — reassignment is explicit. `GAP-02` | P2 |
| YEC-11 | Rollover of a 2,000-student school completes within the agreed window and is restartable after an interruption. `GAP-02` | P2 |

## 17. Transfer Out, Withdrawal & TC (CERT-XFER)

| ID | Scenario | Priority |
|----|----------|----------|
| XFER-01 | Withdrawal initiated with reason and last-working-date; clearance checklist runs (fees, library, transport, assets). `GAP-03` | P1 |
| XFER-02 | Transfer Certificate generated with the statutory fields (admission/withdrawal dates, grade, conduct, attendance, board reg. no.), numbered and non-repudiable. `GAP-03` | P1 |
| XFER-03 | Post-withdrawal: enrolment closes as `withdrawn`/`transferred`, the student drops off rosters, attendance, timetable, transport, and communications from the effective date — but history remains queryable. `GAP-03` | P1 |
| XFER-04 | Parent app access after withdrawal: read-only access to records and receipts for a defined period, then revoked. `GAP-03`, `GAP-04` | P1 |
| XFER-05 | Student transfers between two schools *within the same chain*: records move or are linked, no re-keying of the profile, fee ledger is settled at the source school. `GAP-15` | P1 |
| XFER-06 | Student transfers in from an external school mid-year: prior-school marks captured as historical context without polluting current-term computations. `GAP-16` | P2 |
| XFER-07 | Duplicate TC request or a TC issued for an already-withdrawn student is prevented. `GAP-03` | P2 |
| XFER-08 | Withdrawal with dues outstanding is blocked or requires an authorised override with reason. `GAP-03` | P1 |

## 18. Graduation & Alumni (CERT-GRAD)

| ID | Scenario | Priority |
|----|----------|----------|
| GRAD-01 | Grade 12 cohort graduates at year-end: enrolments close as `graduated`, students exit all rosters, and no next-AY enrolment is created. `GAP-02` | P1 |
| GRAD-02 | School-leaving certificate / final transcript generated for the graduating cohort. `GAP-03` | P1 |
| GRAD-03 | Board results imported and merged onto the final transcript. (Board integration stubbed.) | P2 |
| GRAD-04 | Alumni access: login downgrades to an alumni scope — transcript and receipt retrieval only, no fees, attendance, or messaging. `GAP-04` | P2 |
| GRAD-05 | An alumnus requests a document three years later; records are retrievable within the retention policy and the request is audit-logged. `GAP-04`, `GAP-14` | P2 |
| GRAD-06 | Graduating students are excluded from the next AY's fee generation, transport roster, and communications. `GAP-02` | P1 |

## 19. Safety & Day-to-Day Administration (CERT-OPS)

| ID | Scenario | Priority |
|----|----------|----------|
| OPS-01 | Gate pass / early dismissal: guardian requests, class teacher and office approve, gate-out is recorded, attendance shows half-day. `GAP-18` | P1 |
| OPS-02 | Authorised pickup list enforced at dismissal; an unlisted person is refused and the incident is logged. `GAP-18` | P1 |
| OPS-03 | Visitor log with check-in/out and host staff member. `GAP-18` | P3 |
| OPS-04 | Discipline incident recorded with severity and action taken; visible to HM and (per policy) the guardian; feeds the conduct line on the TC. `GAP-19` | P2 |
| OPS-05 | Infirmary visit logged with symptoms and action; guardian notified; medical history visible to authorised staff only. `GAP-17` | P2 |
| OPS-06 | Counselling notes stored under restricted access — not visible to the general teaching staff. `GAP-19` | P2 |
| OPS-07 | Fire drill / emergency roll call: an evacuation roster is produced from live attendance in under a minute. `GAP-18` | P3 |

## 20. Devices & IoT (CERT-DEV)

| ID | Scenario | Priority |
|----|----------|----------|
| DEV-01 | Device registered to a school and campus; an unregistered device's events are rejected. | P1 |
| DEV-02 | Device event ingestion is idempotent and tolerant of out-of-order delivery. | P1 |
| DEV-03 | Device heartbeat/health surfaces an offline device to `AD` before the day's attendance is affected. | P2 |
| DEV-04 | Device shared between two schools on one campus routes events to the correct tenant. | P3 |

## 21. Board & External Integration (CERT-INT)

| ID | Scenario | Priority |
|----|----------|----------|
| INT-01 | Board export job queued, processed, and its output downloadable; a failure is retryable without duplicating the export. | P2 |
| INT-02 | Export payload validates against the board's schema for a full grade cohort, including students with elective subject sets. `GAP-05` | P2 |
| INT-03 | UDISE+/CIE Direct submission retries on transient failure and does not resubmit an accepted record. (Adapters stubbed.) | P3 |
| INT-04 | Accounting export (Tally/Zoho) reconciles to the ledger for a period. (Not built.) | P3 |

## 22. Non-Functional (CERT-NFR)

| ID | Scenario | Priority |
|----|----------|----------|
| NFR-01 | Morning attendance peak: 60 teachers marking 40-student sections concurrently — p95 response within target, no lost writes. | P1 |
| NFR-02 | Report-card publication for 2,000 students completes within the window and does not degrade interactive traffic. | P1 |
| NFR-03 | Fee-due day: invoice generation plus a notification fan-out to 2,000 guardians without queue backlog beyond the SLA. | P1 |
| NFR-04 | Parent app on a low-end Android device over 3G loads the home screen within target; tablet layout renders correctly. | P2 |
| NFR-05 | Chain-level dashboards over 10 schools return within target. | P2 |
| NFR-06 | Backup and point-in-time restore of a single chain schema is exercised and verified. | P1 |
| NFR-07 | Migration of a chain schema from version N to N+1 on a populated database: no downtime beyond the agreed window, no data loss, rollback path tested. | P1 |
| NFR-08 | Timezone and DST-free correctness: a 23:55 attendance mark lands on the right IST date; date-only fields are never shifted by a UTC conversion. | P1 |
| NFR-09 | Localisation of names, currency (₹, lakh/crore formatting), and date format (dd/mm/yyyy) across app and generated documents. | P2 |
| NFR-10 | Observability: a failed fee payment, a failed notification, and a failed migration each produce an actionable alert with tenant context. | P2 |

---

## 23. Gaps Found During Scenario Design

These block one or more P1 scenarios. Mirrored into `BACKLOG.md`.

| Gap | Summary | Evidence |
|-----|---------|----------|
| GAP-01 | **No school calendar / holiday master.** No table, no API. Working-day denominators, timetable suppression, closure handling, and due-date shifting all have nothing to compute against. | No `holiday`/`calendar` table in any chain migration; only `admission_event` and `announcement`. |
| GAP-02 | **No year rollover / bulk promotion.** `enrolment.status` allows `promoted`/`graduated` but nothing sets them; no next-AY section cloning, no arrears carry-forward, no readiness check, no idempotent re-run. | No promotion/rollover code outside a comment in `enrolment/package-info.java`. |
| GAP-03 | **No exit workflow.** No Transfer Certificate generation, no clearance checklist (fees/library/transport), no withdrawal reason capture, no downstream de-listing from rosters/transport/comms. | Statuses exist; no artifacts, endpoints, or documents. |
| GAP-04 | **No alumni identity.** Post-graduation access is undefined — no scope downgrade, no alumni record, no document-request path. | No alumni table or role. |
| GAP-05 | **No student-level subject election.** `section_subject_teacher` binds subjects to sections only. IGCSE/A-level option blocks and Class 11 streams cannot be modelled; marks, timetables, report cards, and board exports all assume a section-wide subject set. | Schema + `CurriculumController`/`AssessmentController` surface. |
| GAP-06 | **Exam operations missing.** No exam timetable entity, no per-student paper-clash check, no room/invigilator allocation, no hall tickets, no `absent`/medical-leave mark semantics (blank vs zero), no re-evaluation or moderation, no audited unlock of locked marks. | `assessment.scheduled_on` is the only scheduling field. |
| GAP-07 | **No teacher substitution/cover.** Staff absence is recorded and timetable slots name a teacher, but nothing links them; no cover assignment, no substitute notification, no permission for the substitute to mark that period. | `staff_attendance` and `timetable_slot` are unrelated. |
| GAP-08 | **Attendance corrections are unaudited overwrites.** The upsert replaces the prior value with no history, no approval, and no reason. Approved leave does not auto-materialise as attendance. | `AttendanceRepository` upsert on the V010 unique index. |
| GAP-09 | **Fee lifecycle holes.** No invoice-generation job from `fee_structure`; no scheduled `overdue` transition; no reminders/dunning; no late fee; no refund/credit-note endpoint (status `refunded` exists, nothing sets it); no cheque-bounce reversal; no sibling/family concession or combined family invoice; no transport-fee linkage; no online checkout initiation (already in the backlog). | Only scheduled job in the codebase is `OutboxPublisher`. |
| GAP-10 | **Section capacity not enforced.** `section.capacity` is stored and read but never checked at enrolment or at admission offer. | `capacity` appears in Java only for `vehicle` and a `SELECT` in `SchoolRepository`. |
| GAP-11 | **Admissions funnel automation missing.** `offer_expires_on` and the `lapsed` state exist but no job expires offers; no waitlist promotion on decline; no duplicate-lead dedupe by guardian phone. | `AdmissionsRepository` reads the field; nothing acts on it. |
| GAP-12 | **Timetable: room clash and load rules missing; no bell-schedule master.** Teacher clash is checked; room double-booking is not. Each slot carries its own free-text times, so there is no period/bell master to validate against. | `TimetableRepository` clash query is teacher-only. |
| GAP-13 | **Report card has no content model.** No score payload, attendance summary, co-scholastic ratings, remarks, or promotion decision; no template renderer. (Partially recorded in the backlog already.) | `ReportCardDto` carries metadata only. |
| GAP-14 | **No closed-year lock.** Prior-AY attendance, marks, and invoices stay mutable indefinitely; there is no reopen-with-approval path. | No AY status field beyond `is_current`. |
| GAP-15 | **No intra-chain school transfer.** `/enrolments/{id}/transfer` moves a section only; a student moving between two schools in the same chain has no path that preserves history and settles the source ledger. | `TransferRequest(newSectionId, rollNo)`. |
| GAP-16 | **No student document store.** `admission_application.documents` is loose JSONB with no verification state; enrolled students have no document set at all (TC in, birth certificate, immunisation). | Schema. |
| GAP-17 | **No health/emergency data.** Allergies, conditions, blood group, and prioritised emergency contacts are not modelled — they cannot reach the class teacher or the driver view. | No such columns on `student`. |
| GAP-18 | **No safety operations.** No gate pass / early dismissal, no authorised-pickup list, no visitor log, no evacuation roster. Bus check-in exists; the walk-home and parent-pickup paths do not. | Schema + controller surface. |
| GAP-19 | **No discipline or counselling records.** Conduct has no source of truth, which also leaves the TC conduct line unbacked. | Schema. |
| GAP-20 | **No PTM scheduling.** Message threads exist; slot publication and parent booking do not. | Schema + `CommsController`. |
| GAP-21 | **No notification preferences or delivery management.** No per-guardian channel choice, quiet hours, category mute, or opt-out; no retry/failure surface over `notification_dispatch`. | Schema + `notification` module. |
| GAP-22 | **Library has no fines or holds.** No overdue fine calculation, no posting of fines/lost-book charges to the fee ledger, no reservations, no per-grade issue limits. | `library_issue` has no fine fields. |
| GAP-23 | **No bulk import or DPDP data lifecycle.** No CSV import for students/staff/marks; `consent_record` exists but there is no export, erasure, or retention job. | Schema + controller surface. |
| GAP-24 | **Campus is decorative.** `campus` exists, but `section`, `staff`, and `timetable_slot` carry no `campus_id`, so campus-scoped timetables, attendance, holidays, and admin roles are impossible. | Schema. |
| GAP-25 | **No AY/term date validation.** Terms are not constrained to sit inside their academic year, and academic years may overlap. | `term` and `academic_year` DDL — only `ends_on > starts_on`. |
| GAP-26 | **No admission/roll number policy.** `roll_no` is free text with no uniqueness constraint and no generator; admission numbers have no scheme. | `enrolment` DDL. |
| GAP-27 | **Audit trail not wired to high-risk mutations.** (Extends the backlog's audit retrofit item.) Enrolment status changes, mark unlock, fee waiver, and role grants specifically must be audited before certification. | `AuditService.record` exists; callers do not. |
| GAP-29 | **No rank/percentile/grade-boundary computation.** Needed for report cards and board preparation. | `assessment` module. |
| GAP-30 | **Transport operational gaps.** Route capacity is not checked against vehicle capacity, mid-year stop/route change has no effective-dated path, and there is no boarding-vs-attendance mismatch alert. | `transport` module + schema. |

_(GAP-28 intentionally unused — session expiry / token refresh is already an
open item in `BACKLOG.md` and is referenced by SEC-02 rather than duplicated.)_

---

## 24. Suggested Certification Sequence

1. **Setup gate** — TEN, ACAD, SEC. Nothing downstream is meaningful until
   tenancy isolation and academic structure are proven.
2. **Intake gate** — ADM, ENR. Establishes the population everything else
   operates on.
3. **Operating gate** — CAL, TT, ATT, ASMT, LMS, FEE, COMM, TRN, LIB, STF, OPS.
   Run as a simulated term: one full month of school days, compressed.
4. **Closure gate** — YEC, XFER, GRAD. Run twice consecutively to prove
   rollover is repeatable and history survives two year boundaries.
5. **Cross-cutting** — DEV, INT, NFR, run continuously against gates 3 and 4.

A release is certified when every P1 scenario in gates 1–4 passes against a
seeded two-school chain (one CBSE, one Cambridge) carrying at least one full
prior academic year of history.
