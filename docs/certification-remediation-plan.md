# Certification Remediation — Implementation Plan

Closes the 29 gaps in `BACKLOG.md` (§ "Gaps found during certification
scenario design") so that every **P1** scenario in
`docs/certification-test-scenarios.md` can pass.

Ordered by dependency, not by pain. The controlling fact: **GAP-02 (year
rollover) reads from four other gaps' outputs** — the working-day calendar,
the fee arrears balance, the report card's promotion decision, and section
capacity. It cannot be built first even though it is the largest hole. The
phases below are arranged so nothing is built twice.

Migration numbering continues the chain schema from `V015__notification_device.sql`.

---

## Phase 0 — Make certification executable

Without this, the 150 scenarios are prose and nothing below is measurable.

| Work | Detail |
|------|--------|
| Seed fixture | Two-school chain — one CBSE, one Cambridge — carrying **one full prior academic year** of history (attendance, marks, invoices, report cards). One school seeded to 2,000 students for the NFR scenarios. Fixes the `section_subject_teacher` seed gap already in the backlog. |
| Scenario runner | Integration test per scenario ID, Testcontainers Postgres, one test class per CERT area. Test method names carry the ID (`cert_ATT_05_approvedLeaveMaterialisesAttendance`) so the catalogue and the suite stay in sync mechanically. |
| CI gate | P1 scenarios block merge. P2/P3 report only. A scenario for an unbuilt gap lands as `@Disabled("GAP-01")` from day one — the disabled list *is* the remaining work, and it shrinks visibly. |
| Session expiry fix | Existing open item, cheap, blocks SEC-02. Do it in the shared transport (`packages/api-client`) rather than six times. |
| `packages/api-client` extraction | Already planned for the parent mobile app. Pull it forward to here so every endpoint added in Phases 1–8 lands in one client, not six. |

**Exit:** the suite runs green on today's capability set, with ~60 scenarios
disabled and each one naming its gap.

### Status — landed 2026-08-12

Built: `CertificationFixture` (one chain, two schools, a full prior academic
year of history, optional 2,000-student bulk seed behind
`-Dschoolsoft.cert.bulk-students`), `AbstractCertificationTest` (Testcontainers
Postgres, or an external server via `SCHOOLSOFT_TEST_DB_URL`), one test class
per CERT area, `CatalogueSyncTest` (the catalogue and the suite fail the build
if they drift, and it generates `docs/certification-status.md`), and the
`.github/workflows/certification.yml` gate — P1 blocking, P2/P3 report-only.

Session expiry is closed: the API returned **403** for an expired token
(`sendError` re-dispatches through `/error`, which the security chain rejects
anonymously), so no client could key a refresh on it. It now returns 401 with a
`token_expired` code, and `packages/api-client` does a single-flighted
401 → refresh → replay. All six frontends now share that transport.

The estimate of "~60 disabled" was optimistic. Of 205 catalogue scenarios,
**43 pass and 162 are disabled** — and 15 new gaps (GAP-31..45 in `BACKLOG.md`)
surfaced that the document-only review had missed, four of them
security-relevant: authorization stops at the school boundary (a teacher reads
any teacher's data, a guardian reads any child's), screen access is advisory
only, OTP has no rate limit and a permanent `000000` bypass, and platform-admin
actions are unaudited. Those four are worth pulling ahead of the phase order
below.

---

## Phase 1 — Temporal foundation

**Closes GAP-01, GAP-25, GAP-14, GAP-24.** Everything that computes over dates
depends on this, so it goes first.

`V016__calendar_and_year_lifecycle.sql`

- `working_day_pattern` — school (optionally campus), `effective_from`,
  weekday mask, alternate-Saturday rule.
- `school_calendar` — one row per exceptional date: `kind` ∈
  `holiday | vacation | working_saturday | closure | exam_day`, optional
  `grade_id` and `campus_id` for cohort- and campus-scoped days,
  `declared_by`/`declared_at` (an unplanned same-day closure is an audit event).
- `academic_year.status` ∈ `planning | active | closed`, plus `closed_at`,
  `reopened_by`, `reopened_at`. `is_current` stays as the fast lookup.
- Term-inside-AY constraint; `EXCLUDE USING gist` on `daterange(starts_on,
  ends_on)` per school so academic years cannot overlap.
- `campus_id` added to `section`, `staff`, `timetable_slot` — nullable,
  backfilled to the school's primary campus, then `NOT NULL`.

Code:

- **`WorkingDayService`** — `workingDays(schoolId, from, to, gradeId, campusId)`
  and `isWorkingDay(...)`. Single authority. Retrofit every attendance
  percentage and every fee due-date computation onto it; no module computes
  its own denominator afterwards.
- Attendance write rejects a non-working day. Declaring a same-day closure
  voids that day's marks (retaining them as voided, not deleting) and fires
  the parent notification.
- `AcademicYearLockInterceptor` — mutations against a `closed` AY are refused
  across attendance, marks, and fees, with an authorised reopen path.
- Calendar CRUD endpoints + a gazetted-holiday bulk import.

**Certifies:** CAL-01..07, ACAD-02/03, ATT-02/04/10, TT-09, YEC-08, TEN-07, CAL-06.

### Status — landed 2026-08-12

Built as planned, in three migrations so the campus backfill can be run and
re-run apart from the DDL: `V016__calendar_and_year_lifecycle.sql`,
`V017__campus_scoping_add.sql`, `V018__campus_scoping_enforce.sql`.

`WorkingDayService` is the single authority, and the retrofit went with it:
attendance refuses a non-working day, the attendance percentage
(`/v1/attendance/students/{id}/summary`) counts working days per enrolment
segment rather than calendar days, the timetable day view
(`/v1/timetable/sections/{id}/day`) returns the closure reason instead of an
empty grid, and fee due dates shift onto the next working day. Declaring a
same-day closure voids that day's marks — retained with `voided_at` and a
reason, not deleted — and notifies the affected guardians.

Two things the plan did not anticipate:

- **Campus needed a default, not just a column.** Making `campus_id` NOT NULL
  broke every existing insert path that had no reason to know about campuses.
  A `BEFORE INSERT` trigger fills it from the school's primary campus (and
  from the section, for a timetable slot), so single-campus schools never name
  one and the same-school check still catches a cross-school mistake. `device`
  gained `campus_id` too — DEV-01 asks which campus a reader hangs on.
- **The device path had its own attendance INSERT**, which meant the calendar
  and closed-year rules did not reach it. It now writes through
  `AttendanceMarking` in the attendance module's API.

ATT-12 (future date, out-of-enrolment-window) came along with the same guard,
which partly closes GAP-39; the source-precedence half (ATT-08) stays open.
TT-09 remains disabled — it needs the exam-timetable entity from Phase 5.

**Executable scenarios: 43 → 58.** Newly passing: CAL-01..07, ACAD-02/03,
ATT-04/10/12, YEC-08, TEN-07, DEV-01.

---

## Phase 2 — Structural integrity

**Closes GAP-05, GAP-10, GAP-26, GAP-12.** Schema shape changes that get more
expensive the longer real data sits on top of them.

`V017__student_subjects_and_identity.sql`

- `elective_group` (grade + AY + name + pick-count rule) and
  `student_subject` (enrolment, subject, elective_group, status,
  effective_from). Resolution rule: **a student's subject set = the section's
  compulsory subjects + that student's elections.** Introduce
  `SubjectSetResolver` and route marks entry, timetable rendering, report
  cards, and board export through it — this is the change that touches the
  most existing code, which is exactly why it is early.
- `number_series` (school, kind ∈ `admission | roll | invoice | receipt |
  certificate`, pattern, next value, reset policy) — one generator for every
  human-facing number. Partial unique index on `(section_id, roll_no)` for
  active enrolments; backfill existing roll numbers into the series.
- Section capacity enforced in the enrolment and admission-offer paths;
  `over_capacity_reason` for the explicit override.
- `bell_schedule` + `period` masters per grade band; `timetable_slot` gains
  `period_id` and stops carrying free-text times. Room-clash check added
  alongside the existing teacher-clash check; teacher weekly-load warning at
  publish.

**Certifies:** ACAD-06/09, ENR-02, ASMT-13, TT-01/03/04, INT-02, YEC-05.

### Status — landed 2026-08-12

`V019__student_subjects_and_identity.sql` carries all four gaps, because they
are one shape change: who studies what, what they are called, how many fit, and
when the day's periods run.

- **`SubjectSetResolver`** is the rule, in one place: *a student's subject set
  = the section's compulsory subjects + that student's elections*.
  `section_subject_teacher.is_elective` separates the two, `elective_group`
  holds the option block with its pick count, and `student_subject` records
  the choice effective-dated so a mid-year change does not rewrite the marks
  earned before it. Marks entry refuses a subject the student does not take,
  `/v1/timetable/students/{id}` filters the section's week to their own
  periods, the report-card payload carries their subjects, and a board export
  lists each candidate's real option block.
- **`NumberSeries`** issues admission and roll numbers under `SELECT … FOR
  UPDATE`, with a partial unique index making roll numbers unique among a
  section's active enrolments. Renumbering after a transfer is an explicit
  endpoint, not a side effect — it changes what is written in every child's
  exercise book.
- **`SectionCapacity`** refuses a seat the section does not have, on both the
  enrolment and admission-conversion paths, and takes an explicit
  `overCapacityReason` that is stored on the enrolment.
- **Bell schedules** (`bell_schedule` + `bell_period`, bound to grades) drive
  slot times through `timetable_slot.period_id`; a trigger keeps the legacy
  time columns in step, and a break refuses to hold a lesson. Room clashes are
  now checked alongside teacher clashes, and publish-time warnings surface
  teachers over `staff.max_weekly_periods`.

Three things worth recording:

- The roll-number index needed the migration to **refuse rather than
  renumber**: which of two children keeps roll 12 is a school's decision. The
  migration lists the collisions and stops.
- A generated roll number has to start **past** whatever the section already
  holds by hand, and skip anything typed in since — a seeded fixture found this
  immediately.
- Board export gained a **real schema check** even though the adapter is still
  a stub: a candidate with no name, date of birth or subjects fails the job
  here rather than at the board.

**Executable scenarios: 58 → 66.** Newly passing: ACAD-06/09, ENR-02, ASMT-13,
TT-01/03/04, INT-02. YEC-05 still needs Phase 6's reshuffle.

---

## Phase 3 — Daily-operations correctness

**Closes GAP-08, GAP-07, GAP-27.** Small surface, high daily value.

`V018__attendance_amendments_and_cover.sql`

- `attendance_amendment` — record, old status, new status, reason, requester,
  approver, decided-at. The upsert path stops being a silent overwrite: an
  edit after the marking window opens an amendment instead.
- Leave-approval → attendance materialisation. Approving student or staff
  leave writes `leave` records across the covered **working** days (Phase 1),
  and revoking the approval unwinds them.
- `timetable_cover` — slot, date, substitute staff, reason. Surfaces in the
  substitute's day view, notifies the section, and authorises the substitute
  to mark that period's attendance.
- Audit retrofit as a **`@Audited` interceptor**, not fifteen controller
  edits. Mandatory coverage before certification: enrolment status change,
  mark unlock, fee waiver/concession, role grant.

**Certifies:** ATT-05/06, TT-08, STF-02/03, SEC-08.

### Status — landed 2026-08-12

`V021__attendance_amendments_and_cover.sql` (V018 was taken by Phase 1's campus
backfill, so the numbering moved).

- **`attendance_policy`** gives each school a marking window and a list of
  amendment approvers. Inside the window a mark is still a correction; outside
  it the upsert refuses with a 409 that names the amendment path.
- **`attendance_amendment`** carries the request: prior value, new value,
  reason, requester, and a decision by somebody who is neither the requester
  nor a colleague without the role. Approval applies the change; rejection is
  recorded too, because a refused correction is evidence that somebody looked.
- **Leave materialisation** writes the covered working days on approval —
  student leave against each enrolment segment's own calendar scope, staff
  leave into `staff_attendance` — and takes them back when the approval is
  withdrawn.
- **`timetable_cover`** is per slot per date. Approved staff leave raises the
  day's cover needs with the teachers who are actually free in that period;
  assigning cover notifies the substitute and the section's primary teacher,
  moves the period between the two teachers' day views, and authorises the
  substitute to mark that period's register.
- **`@Audited` + one interceptor** covers enrolment status change, assessment
  reopen, report-card unlock, fee adjustment and concession, role grant and
  revoke, and both attendance decisions. `audit_log` gained `reason` and
  `request_payload`.

Five things the plan did not anticipate:

- **Attendance had no authorisation at all.** Any staff token could mark any
  section's register, so "the substitute may now mark that period" was not a
  new permission — it was the first one. `AttendanceAuthorizer` is the rule the
  cover work needed to exist in order to mean anything: office roles, the
  section's own teachers, and the holder of a cover for that period. It is not
  teacher scoping; STF-05's gap is still open and doing half of it here would
  only have hidden it.
- **ATT-02 was certifying the wrong thing.** It marked a period with a teacher
  who does not teach it, which passed only because nothing was checked. The
  scenario now uses the teacher actually timetabled for the period.
- **An approved leave over an existing mark is an amendment.** Reusing
  `attendance_amendment` rather than overwriting is what makes the unwind
  exact: revoking an approval deletes the days the approval created and
  restores the days it changed, instead of leaving a hole where a teacher's
  'absent' used to be.
- **Leave decisions were unchecked in both directions.** Any staff id could be
  passed as `approverStaffId`, and a teacher could approve their own leave. The
  approver is now the caller, holds an approving role, and is never the
  applicant.
- **RLS had stopped following new tables.** V009 applied school isolation once,
  in a loop over the tables that existed then; everything added by Phases 1, 2
  and 4 had no policy at all. V021 re-runs that rule over anything school-scoped
  still missing one.

**Executable scenarios: 83 → 90.** Newly passing: ATT-05/06/13, TT-08,
STF-02/03, SEC-08. Suite: 208 run, 0 failures, 115 disabled.

---

## Phase 4 — Fee engine completion

**Closes GAP-09, the fee half of GAP-22, the fee half of GAP-30.** Rollover
(Phase 6) reads the arrears balance this phase produces.

First, a prerequisite: the codebase's only scheduled job is `OutboxPublisher`.
Phase 4 opens with a **real tenant-aware scheduler** — per-chain fan-out,
per-school locking, restartable runs — because four of the items below are
jobs and Phases 6–8 need three more.

`V019__fee_lifecycle.sql`

- `fee_schedule_run` — idempotent invoice generation keyed on
  (school, AY, cycle). Re-running is a no-op, which is what makes FEE-02 pass.
- `fee_adjustment` — typed `credit_note | refund | waiver | late_fee |
  reversal`, each posting to the ledger. Cheque bounce becomes a reversal, not
  a delete.
- `family` grouping over guardians; sibling concession rules; combined family
  invoice.
- Dunning config + jobs: scheduled `open → overdue` transition, reminder
  cadence, late-fee application after grace.
- Transport fee derived from the student's route assignment and
  effective-dated, so a mid-year route change adjusts subsequent invoices.
- Library fines and lost-book charges post as `fee_adjustment` rows.

Online checkout initiation stays tracked as its own existing backlog item —
it needs gateway credentials this environment lacks, and everything above is
testable without it.

**Certifies:** FEE-02/04/08/09/10/11/12/14/15, LIB-03/04, TRN-06, ADM-11.

### Status — landed 2026-08-12

`V020__fee_lifecycle.sql`, plus the scheduler the phase opened with.

- **`TenantJobRunner`** — per-chain fan-out, a per-school advisory lock, and a
  `job_run` row keyed on (school, job, run key). A repeated run key is one run,
  which is what makes a crashed billing run safe to retry. Phases 6–8 inherit
  it.
- **Generation** assembles each invoice from the grade's structure, the
  student's transport assignment, their own concessions and their sibling
  rank — every discount a visible line, GST per head on the net amount.
  Idempotency is enforced twice: the run record for the friendly answer, and a
  partial unique index on generated (student, cycle) for the real one.
- **`fee_adjustment`** carries every after-the-fact change, each posting a
  balanced ledger pair. A bounced cheque is a reversal, never a deleted
  payment.
- **Dunning** marks overdue, sends the school's reminder cadence and applies a
  late fee after grace, with `dunning_event` keyed on (invoice, kind, day) so a
  family never gets the same reminder twice.
- **Reports** — a day book that reconciles collections against the ledger's own
  bank movement, and an outstanding-dues report that year-end clearance reads
  the student level of.
- **Library and transport charges** post through `FeeCharges`, so a fine or a
  lost book lands on a real invoice rather than a column nobody collects.

Four things the plan did not anticipate:

- **The payment race was still open after the atomic UPDATE.** `FOR UPDATE`
  only serialises inside a transaction, and the repository was not
  transactional — two threads paying at once still over-credited. The
  certification scenario ran two real threads and caught it.
- **Roll-number allocation had drifted into two places** (direct enrolment and
  admission conversion), and the conversion path immediately handed out a
  number the section already used. Now one `RollNumbers` service.
- **Admission conversion never created a guardian**, so a converted applicant's
  family had no login and no household to hang a sibling rule on. Conversion
  now links (or reuses) the guardian by phone — which also closes the
  ADM-10 half of that gap.
- **The day book must count receipts by when the money arrived**, not by the
  payment's current status: a cheque that bounced next week was still banked
  today, and the reversal belongs in its own column.

**Executable scenarios: 66 → 83.** Newly passing: FEE-01/02/03/04/08/09/10/11/
12/13/14/15/17, LIB-03/04, TRN-06, ADM-11. FEE-05 stays blocked on gateway
credentials and FEE-16 on rollover.

---

## Phase 5 — Assessment and report card content

**Closes GAP-06, GAP-13, GAP-29.** Produces the promotion decision that
Phase 6 consumes.

`V020__exam_ops_and_report_cards.sql`

- `exam_schedule` + `exam_session` — paper, date, period, room, invigilator.
  Clash detection runs over `student_subject` (Phase 2), so it catches the
  clash that matters: one *student* with two papers, not one section.
  Hall-ticket generation per student.
- `mark.status` ∈ `entered | absent | medical_leave | exempt`. A blank stops
  being indistinguishable from a zero; absent is excluded from averages and
  renders as AB.
- `mark_revision` — re-evaluation and moderation supersede without discarding;
  unlock of a locked assessment requires an authorised role, a reason, and an
  audit row (Phase 3's interceptor).
- Report card content model: subject rows, attendance summary (via
  `WorkingDayService`), co-scholastic ratings, teacher remarks, and
  **`promotion_decision`** ∈ `promote | detain | graduate`.
- Rank / percentile / grade boundaries computed inside the curriculum
  strategy, so CBSE and CIE differ without a config fork.
- Templated renderer → PDF, per strategy.

**Certifies:** ASMT-05/07/08/09/10/11/12/13/14, TT-09, GRAD-03 (partially).

---

## Phase 6 — Year closure and rollover

**Closes GAP-02.** The largest single capability; buildable only now.

`V021__rollover.sql`

- `rollover_run` — school, from-AY, to-AY, state ∈ `draft | structure_cloned |
  allocated | committed | rolled_back`, batch checkpoints, statistics.
- **Readiness check** endpoint, assembled from earlier phases: unpublished
  assessments and unlocked report cards (Ph 5), unmarked working days (Ph 1),
  outstanding dues (Ph 4), missing promotion decisions (Ph 5), students with
  no allocated section.
- **Structure clone** — grades, sections, curriculum bindings, bell schedules,
  fee structures cloned into the next AY as `planning`, fully editable before
  activation.
- **Allocation** — promote / detain / graduate driven by
  `promotion_decision`; section reshuffle by rule or manual, respecting
  capacity (Ph 2) and the sibling/twin policy.
- **Carry-forward** — fee arrears as an opening balance, library dues,
  transport assignment, guardian links, medical info, and elective
  continuity (`student_subject`).
- **Idempotent** on the run key, **restartable** at batch checkpoints,
  **reversible** until the target AY is activated.
- Commit closes the source AY (`status = closed`, Phase 1 lock engages).
- Teacher section assignments deliberately **do not** carry forward —
  reassignment is explicit (YEC-10).

Perf target from NFR: a 2,000-student rollover inside the agreed window, and
restartable after a mid-run interruption.

**Certifies:** YEC-01..11, GRAD-01/06, FEE-16, ENR-08.

---

## Phase 7 — Exit, transfer, graduation, alumni

**Closes GAP-03, GAP-15, GAP-04.** Depends on Phase 4 (dues) and Phase 6
(graduation as a rollover outcome).

`V022__exit_and_alumni.sql`

- `withdrawal` — enrolment, reason, last working date, per-domain clearance
  state, workflow state. Clearance probes call fees (Ph 4), library (Ph 4),
  transport, and assets. Withdrawal with dues is blocked pending an authorised
  override with reason.
- `certificate` — kind ∈ `TC | SLC | transcript`, serial from `number_series`
  (Ph 2), **payload snapshotted at issue** so a reprint is byte-identical,
  `issued_by`, revocation support, duplicate-issue prevention.
- **One `enrolmentActiveOn(studentId, date)` predicate**, reused by rosters,
  timetable, attendance, transport, fee generation, and communications. This
  single predicate is what makes the de-listing scenarios (XFER-03, COMM-08,
  TRN-09, GRAD-06) pass together rather than one leaky surface at a time.
- Intra-chain school transfer: link the student across schools, settle the
  source ledger, carry documents and history, no profile re-keying.
- Alumni scope — role downgrade to transcript/receipt retrieval within the
  retention window, audited document requests.

**Certifies:** XFER-01..08, GRAD-02/04/05, COMM-08, TRN-09, LIB-05, ENR-05.

---

## Phase 8 — Missing record types

**Closes GAP-16, 17, 18, 19, 20, 21, 23, 11, and the remainder of 30.** Mostly
independent of each other, so this phase parallelises across the team more
than any other.

`V023__student_records_and_safety.sql`

- **Documents** (GAP-16) — typed document store with per-document
  verification state and reviewer; enrolment gated on mandatory documents;
  applies to enrolled students, not only applicants.
- **Health** (GAP-17) — conditions, allergies, blood group, prioritised
  emergency contacts; surfaced to the class teacher and the driver's trip
  view, which are the two people present during an incident.
- **Safety** (GAP-18) — gate pass / early-dismissal approval chain (writes a
  half-day via Phase 3's amendment path), authorised-pickup list, visitor
  log, evacuation roster from live attendance.
- **Conduct** (GAP-19) — discipline incidents feeding the TC conduct line
  (Ph 7); counselling notes under a restricted access class, not general
  staff visibility.
- **PTM** (GAP-20) — slot publication, parent booking, double-booking
  prevention.
- **Notifications** (GAP-21) — per-guardian channel preferences, quiet hours,
  category mute, emergency override; dispatch retry policy and a failure
  surface.
- **Data lifecycle** (GAP-23) — CSV bulk import for students/staff/marks with
  per-row validation (schools onboard from spreadsheets, so this is an
  onboarding blocker, not a convenience); DPDP export, erasure, and retention
  jobs behind the existing `consent_record`.
- **Admissions automation** (GAP-11) — offer-expiry job returning seats to the
  pool, waitlist promotion on decline, guardian-phone dedupe on intake.
- **Transport** (GAP-30 remainder) — route capacity against vehicle capacity,
  boarded-vs-attendance mismatch alert.

**Certifies:** ADM-07/08/09/12, ENR-05/06/07/09, OPS-01..07, COMM-04/05/09,
SEC-09, TRN-02/05.

---

## Parallel workstreams

Not phases — they run alongside from Phase 1 onward.

- **Frontend slices.** Every phase carries admin-web / teacher-app /
  parent-app work. Two need real design investment rather than a form:
  the **rollover wizard** (Phase 6) and the **report card template renderer**
  (Phase 5).
- **Report card renderer** is on the critical path for Phase 6 — the
  promotion decision is captured there.
- **External integrations** (GST e-Invoice, Tally, LTI/OneRoster, CIE Direct,
  UDISE+, WhatsApp) stay blocked on credentials. Keep the adapters stubbed and
  the job framework exercised; certify INT-01 against the stub.
- **Chain HQ analytics** remains Phase 2 per design doc §19 — out of scope here.

## Sequencing rationale

```
Ph0 harness
  └─ Ph1 calendar / AY lifecycle ──┬─ Ph3 daily-ops correctness
                                   ├─ Ph4 fee engine ─┐
     Ph2 structure (subjects, ─────┼─ Ph5 assessment ─┼─ Ph6 rollover ── Ph7 exit/alumni
         numbers, capacity, bell)  │                  │
                                   └──────────────────┘
     Ph8 records/safety — parallel from Ph3 onward
```

Ph1 and Ph2 can start together (different tables). Ph3, Ph4, Ph5 are
independent of each other and can run three-abreast. Ph6 needs all of
1, 2, 4, 5. Ph7 needs 4 and 6. Ph8 needs only 3 (for the gate-pass
half-day path) and otherwise floats.

## Risks

| Risk | Handling |
|------|----------|
| `SubjectSetResolver` (Ph 2) touches marks, timetable, report cards, and export | Land it before any of those modules grow further; it is the widest blast radius in the plan |
| `campus_id` backfill (Ph 1) on live data | Nullable → backfill to primary campus → `NOT NULL`, in three separate migrations |
| `mark.status` migration (Ph 5) over existing marks | Existing non-null marks → `entered`; existing nulls need a human decision per school, not a default |
| Roll-number backfill into `number_series` (Ph 2) | Existing free-text roll numbers may already collide; detect and report before applying the unique index |
| Rollover reversibility (Ph 6) | Reversible only until target-AY activation. Make activation a distinct, explicit, audited step — not a side effect of commit |
| Rollover perf at 2,000 students | Batch with checkpoints from the start; retrofitting restartability after the fact is the expensive path |

## Definition of done

Every P1 scenario in gates 1–4 of `docs/certification-test-scenarios.md` §24
passes against the Phase 0 seed fixture, with the closure gate run **twice
consecutively** — proving rollover is repeatable and history survives two
year boundaries.
