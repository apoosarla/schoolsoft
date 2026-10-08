# Browser test bugs — 2026-09-26

## Fix status — 2026-09-27

Gate after the fixes: `P1,harness` 214 run, 0 failed; `P2,P3` 80 run, 0 failed.
All six frontends typecheck. After the fixes, a second browser pass was run
(chrome-devtools, a separate browser context for each person). **Verified**
means that pass saw the fix; **gate** means a cert assertion covers it.

| Bug | Status | How it was checked |
|---|---|---|
| 01 overpaid reversal | Fixed — reversal names its payment, is bounded by what is left of it, unwinds `ADVANCE` first, refuses a second reversal | Verified in the browser (₹10,000 reversed, ledger balanced) + gate (FEE-08, FEE-17) |
| 02 credit invisible | Fixed — `advanceAmount` on the invoice, `credit` on dues; shown in the office and parent apps; overpay needs a second press | Verified in the browser |
| 03 marks confinement | Fixed — mark writes, components, status and creating an assessment now need the subject taught in that section. **The original repro was wrong:** Ravi *is* timetabled for Maths in 10-A. The real hole was that mark entry checked nothing at all. | Gate (STF-05, new write assertions) |
| 04 attendance marker | Fixed — marker comes from the session; the body field is gone | Gate (ATT-01) |
| 05 overlapping leave | Fixed — an overlapping approval is refused (409); materialisation never takes a day from another leave | Verified in the browser + gate (ATT-05) |
| 06 timetable | Fixed — section-clash check (electives exempt), taught-weekday check. **Also found:** the UI sent Sunday as `0` (the API uses ISO 1–7); the form now uses ISO days. | Verified in the browser + gate (TT-02) |
| 07 class teacher lost | Fixed — the form preloads the existing row's flags, the button says "Update", and the notice warns if the section loses its class teacher | Verified in the browser |
| 08 public admissions | Fixed — rate limits (10 per application number, 30 per IP for track; 20 per IP for apply; 15 min), trimmed track response, `@Valid` + service validation | Verified in the browser (429 after 10) + gate (ADM-01) |
| 09 500s | Fixed — handlers for missing/mistyped params, unreadable body, unknown path, 405, `ResponseStatusException` | No unhandled exceptions in the API log across the retest |
| 10 empty-date fetch | Fixed | Verified in the browser |
| 11 admission data | Fixed — phone, email and DOB checks; blanks stored as NULL; required markers; success notice | Verified in the browser |
| 12 exam week outside year | Fixed | Verified in the browser |
| 13 vehicle | Fixed — normalised registration, uniqueness, capacity > 0 | Verified in the browser |
| 14 school slug | Fixed — format + friendly duplicate message | Verified in the browser |
| 15 wrong app sign-in | Fixed — each app refuses other subject types before storing the session | Verified in the browser (school-web, parent-app) |
| 16 parent section reads | Fixed — Home uses the child's attendance; Grades uses new `GET /v1/assessment/students/{id}/marks` (published only, for families) | Verified in the browser |
| 17 parent leave start | Fixed | Verified in the browser |
| 18 driver link | Fixed — linked account named, driver-app message, and linking grants the driver role (`beed668`); unlink revokes only the link's grant (`0210cbb`) | Verified in the browser (display) |
| 19 refresh tokens | Fixed — jti + rotation (30 s grace) + `POST /v1/auth/logout`; every app's sign-out spends its token (platform migration V006) | Verified in the browser + gate (SEC-02) |
| 20 code boxes | Fixed — a replaced digit is a replacement, and the boxes clear after a failed verify | Verified in the browser |
| 21 no-role dashboard | Fixed | Verified in the browser |
| 22 teacher attendance | Fixed (explains instead of a raw 403) | Verified in the browser |
| 23 teacher subject | Fixed — subject picker + `.panel-btn` style | Verified in the browser |
| 24 locked assessment | Fixed — read-only grid, banner, error beside the grid, plain-words server message | Verified in the browser |
| 25 duplicate assessment | Fixed (409 on same section, subject, name, date) | Verified in the browser |
| 26 dashboard aggregates | Fixed — fee and admissions totals follow their modules' grants (`1f9e5f9`) | — |
| 27 admissions moves | Fixed — closing moves take two presses; offers sorted soonest-expiry first | Verified in the browser |
| 28 raw error codes | Fixed — `ApiError.userMessage` used by all 35 screens; several server messages reworded | Verified in the browser |
| 29 off-screen errors | Fixed — the assessment grid has its own banner; Fees payment and adjustment results show beside their controls | Verified in the browser 2026-10-08 (both messages sit under their own button, in view) |
| 30 stale banners | Fixed (attendance tabs) | Verified in the browser |
| 31 timestamps | Fixed (fees, comms, parent messages, timetable clock) | Verified in the browser |
| 32 thread labels | Fixed (named participants) | Verified in the browser |
| 33 register names | Fixed | Verified in the browser |
| 34 overflow | Fixed (roles chips, fee detail cell) | Verified in the browser (roles) |
| 35 line description | Fixed (falls back to the fee head) | Verified in the browser |
| 36 destructive confirms | Fixed (leave Withdraw, timetable Delete) | Verified in the browser (Withdraw) |
| 37 track page | Fixed (placeholder, prefill, local date) | Verified in the browser |
| 38 parent code field | Fixed (autofocus, one-time-code) | Verified in the browser |
| 39 refused marks revert | Fixed (typed value kept for refused rows) | Verified in the browser 2026-10-08 (150 of 100 stays in the box, refusal listed beside the grid) |
| 40 dashboard labels | Fixed (role name, "—" when nothing is marked, readable funnel labels) | Verified in the browser |
| 41 cash → BANK | Fixed — cash receipts post to their own ledger account (`059bbf9`) | — |

Also fixed while fixing the above:
- Every date input used UTC `todayIso()`, which is yesterday in India until 05:30.
- The school-web sidebar showed the raw schema name.
- Fee adjustment kinds showed raw codes.

Left open on 2026-09-27, fixed 2026-10-08:
- The triple fetch on parent Home. Not only StrictMode: `AppShell` returned the
  page bare before the session was read and wrapped afterwards, so the page was
  remounted and its mount effect fetched again — in a production build too. The
  page now keeps one place in the tree. teacher-app, driver-app and school-web
  had the same early return in their shells and got the same fix.
- The office DOB fields (new application, advanced search) carry `max` = today.
- The legacy `day_of_week = 0` slot. The column's check still said 0..6 while
  the API takes ISO 1..7, so a Sunday slot was refused by the database. `V044`
  moves 0 to 7 and makes the check 1..7.

Found on 2026-10-08 while re-running BUG-29, fixed the same day (bounded by what was billed; the part past the dues becomes credit held):
- A credit note or waiver has no upper bound and its ledger legs can disagree
  with the invoice — see the first entry under "Open items" in `BACKLOG.md`.
  The probe that found it (a ₹99,999,999 credit note on INV-0002) was removed
  from the dev DB by hand the same day; INV-0002 is back at ₹5,250 / partial.

Found in a full browser pass over all six apps (school-web, platform-web,
parent-app, teacher-app, driver-app, public-site) plus curl probes of the API.
Dev chain `smoketest`, school Oakridge Hyderabad. Personas: principal (Priya),
subject teacher (Ravi), staff with no role (Ramesh), guardian (Sunil), chain
admin (`hq@smoketest.test`), platform admin.

Severity: **S1** serious (money, authorization, data integrity), **S2** medium,
**S3** cosmetic / UX.

---

## S1 — Serious

### BUG-01 Overpaid fee payment can never be fully reversed
- **Where:** `apps/api/.../fees/internal/FeeAdjustmentService.java:81-121`
- **Repro:** Fees → Ananya Rao → INV-0002 (₹3,250 outstanding) → record ₹10,000
  cash. Then Adjustments → reversal ₹10,000.
- **Actual:** `bad_request: Cannot reversal 10000.0 against INV-0002: only 5250.0 was paid`.
  `fee_invoice.paid` is capped at the invoice total, so the ₹6,750 excess (which
  was correctly posted to `ADVANCE`) is unreachable.
- **Also:**
  - The reversal branch never unwinds the `ADVANCE` leg; it always debits
    `FEE_RECEIVABLE` for the full amount.
  - `paymentId` is optional for a reversal/refund.
  - The amount is not checked against the chosen payment's amount.
  - A payment's status is not checked, so it can be reversed twice.
  - A partial reversal marks the whole payment `failed`.
  - Money is handled as `double`.
- **Expected:** Reversal is tied to one payment and bounded by that payment's
  amount. It unwinds receivable and advance in proportion, and a payment already
  reversed can't be reversed again.

### BUG-02 Family credit (advance) is invisible and overpay is not warned
- **Where:** school-web Fees → Collections; parent-app Fees.
- **Repro:** As BUG-01. The office screen shows "Outstanding ₹0.00 — nothing
  owed" and Paid ₹5,250, while the payments listed total ₹12,000. The parent app
  shows "Nothing outstanding".
- **Expected:** Show the advance/credit balance on both surfaces, and confirm
  before recording a payment larger than what is outstanding.

### BUG-03 Teacher can edit marks in any subject of a section they teach
- **Where:** `apps/api/.../iam/api/TeacherScope.java` (section-only confinement);
  `AssessmentController` calls `teacherScope.requireSection`.
- **Repro:** Sign in as `ravi.kumar@oakridge-hyd.test` (subject_teacher; teaches
  English in 10-A via timetable, **not** Maths). Assessment → Class Test 1
  (Mathematics) → Enter marks → change Ananya 76 → 77 → Save all.
- **Actual:** Saved; `mark_revision` row by Ravi. (Left at 77 in dev DB.)
- **Expected:** A confined teacher may enter marks only for subjects they teach
  in that section (`section_subject_teacher` / timetable subject).

### BUG-04 Attendance "marked by" is client-supplied and never set
- **Where:** `apps/api/.../attendance/api/AttendanceController.java:46-73`
  (`req.markedByStaffId()`).
- **Repro:** Mark attendance in school-web, then query `attendance_record`.
- **Actual:** `marked_by_staff_id` is NULL on every row, because school-web
  doesn't send it. Any caller can send another staff member's id instead.
- **Expected:** Derive the marker from the session (`TenantContext` →
  `user_account.subject_id`) and ignore the body field.

### BUG-05 Overlapping leave: withdrawing one deletes days the other still covers
- **Where:** `apps/api/.../attendance/internal/LeaveMaterialisationService.java:137-139`
  (student) and the staff `ON CONFLICT ... SET leave_application_id = EXCLUDED...`.
- **Repro:** Approve leave A (08-10 → 08-11). Approve overlapping leave B
  (08-10). B takes over 08-10's `leave_application_id`. Withdraw B, and 08-10's
  leave row is deleted while A is still approved.
- **Also:** Approval doesn't refuse an overlap with an already-approved leave
  for the same person (seen in the browser: "fever" 08-10→11 and "Family
  function" 08-10 both approved).
- **Expected:** Refuse overlapping approvals, or keep the day owned by the first
  leave and only unwind days a leave actually owns.

### BUG-06 Timetable allows a section to hold two lessons at once, and lessons on closed days
- **Where:** `apps/api/.../timetable/internal/TimetableRepository.java` — only
  `requireNoTeacherClash` and `requireNoRoomClash` exist; there is no section
  clash check and no working-day check.
- **Evidence in dev data (10-A):**
  - A Sunday slot (Maths, P3).
  - Monday P1 has two in-force slots (08:00 Ramesh Maths + 09:00 Priya Maths).
  - Monday P2 has two (Ravi English + Priya Maths).
- **Also:** The add-slot form defaults to "Own times…", so slot times drift from
  the bell schedule (P1 09:00–09:40 vs bell 08:00–08:45).
- **Expected:** Refuse a slot that overlaps another in-force slot of the same
  section, and refuse days the school calendar marks as non-working.

### BUG-07 Re-assigning a subject teacher silently removes class-teacher status
- **Where:** `apps/api/.../tenancy/internal/SchoolRepository.java:503-507`
  (upsert overwrites `is_primary`); school-web Academics → Subjects & teaching.
- **Repro:** 10-A, Mathematics, Priya Menon, leave "Class teacher" unchecked
  (the default) → Assign. The message says "Teacher assigned.", but the Class
  Teacher column goes from Yes to —, leaving the section with no class teacher.
  (Restored in dev DB.)
- **Also:** There is no control to remove an assignment.
- **Expected:** Don't clear `is_primary` implicitly, or warn that the section will
  lose its class teacher.

### BUG-08 Public admissions: no rate limit, over-exposed track response, raw DB errors
- **Where:** `/v1/public/schools/{chain}/{school}/admissions/track|apply`.
- **Repro / actual:**
  - 40 consecutive `track` calls with guessed phones all answer 404 — no 429.
    Application numbers are sequential (`APP260001`, `APP260002`) and the phone
    is the only secret.
  - A successful `track` returns the full row: `applicantDob`, `guardianName`,
    `guardianPhone`, `schoolId`, `academicYearId`, `gradeId`, `id`. The page
    shows only name and status.
  - `POST apply` with `{}` → 409 `ERROR: null value in column "grade_id" of
    relation "admission_application" violates not-null constraint`, which leaks
    schema names to anonymous callers. The DTO has no `@Valid`.
  - `POST apply` with a non-JSON body → 500.
- **Expected:** Rate-limit per IP and per application number, return only
  `{name, state, updatedAt}`, validate the DTO (400 with field messages), and
  handle malformed JSON as 400.

---

## S2 — Medium

### BUG-09 Unmapped Spring exceptions become 500
- **Where:** `apps/api/.../platform/web/GlobalExceptionHandler.java` has no
  handler for:
  - `NoResourceFoundException` — should be 404
  - `MissingServletRequestParameterException` — should be 400
  - `MethodArgumentTypeMismatchException` — should be 400
  - `HttpMessageNotReadableException` — should be 400
- **Seen:**
  - `GET /v1/attendance?onDate=` (empty) → 500
  - `GET /v1/fees/invoices` without `studentId` → 500
  - Any unknown `/v1/...` path → 500
  - Malformed JSON → 500
  - Platform admin calling `/v1/tenancy/schools` → 500 `BadSqlGrammarException`
    (no chain schema on the search_path); should be 403.

### BUG-10 Attendance screen fires a request with an empty date on every date edit
- **Where:** school-web `/attendance` Register tab.
- **Repro:** Type a date into the date field. The intermediate empty value
  triggers `GET /v1/attendance?...&onDate=`, which returns 500 (BUG-09), and
  `internal_error: Unexpected error` stays on screen after the valid date loads.
- **Expected:** Skip the fetch while the date is empty, and clear the error on
  the next successful load.

### BUG-11 Admission application accepts impossible data (office and public)
- **Where:** school-web Admissions → New application; public-site `/apply`;
  admissions create service.
- **Repro:** DOB `2030-01-01` (office) or `2031-01-01` (public), guardian phone
  `abc`. Both are accepted (APP260001, APP260002).
- **Also:**
  - Blank gender and email are stored as `''` instead of NULL.
  - The office form doesn't mark which fields are required (the button just
    stays disabled).
  - There's no success message or new application number after creating one.
- **Expected:** DOB in the past and within a sane age range for the grade,
  phone/email format checks, and blanks stored as NULL.

### BUG-12 Exam week accepted outside its academic year
- **Where:** school-web Exams; exam schedule create.
- **Repro:** Year 2026-27 (ends 2027-04-30) → add exam week BT-1
  2028-11-10 → 2028-11-20. Accepted.
- **Also:** end-before-start is caught only by the DB check constraint, and the
  raw message `constraint_violation: ERROR: new row for relation "exam_schedule"
  violates check constraint "exam_schedule_check"` is shown.
- **Expected:** Same checks terms already have: inside the year, and
  end ≥ start with a readable message.

### BUG-13 Vehicle accepts negative capacity and case-variant duplicate registration
- **Where:** school-web Transport → Vehicles.
- **Repro:** Reg `ts07xy9988` (existing: `TS07XY9988`), capacity `-5`. Accepted.
  There's no way to remove it in the UI.
- **Expected:** Capacity > 0, registration normalised (upper-case, no spaces)
  and unique.

### BUG-14 School slug accepts any string
- **Where:** school-web `/chain` (chain admin) → Open a school.
- **Repro:** slug `Bad Slug!` → created "Dup School" in draft.
- **Also:** A duplicate slug shows the raw `ERROR: duplicate key value violates
  unique constraint "school_slug_key"`.
- **Expected:** The same lower-case slug rule platform-web applies to chain
  slugs, and a friendly duplicate message.

### BUG-15 Wrong subject type can sign into each portal
- **Repro:**
  - Guardian `sunil.rao@test.dev` completes **Staff sign-in** on school-web and
    lands in the School Admin shell with `forbidden: You do not have permission
    to do this`.
  - Staff `priya.menon@oakridge-hyd.test` signs into the **parent app** and
    lands on Home ("No children linked") with announcements visible.
- **Expected:** Each app refuses subject types it doesn't serve at verify time,
  with a pointer to the right app ("Parents use the Schoolsoft app").

### BUG-16 Parent app requests section-wide data for one child
- **Where:** parent-app Home and Grades (`/report-cards`).
- **Actual:**
  - Home calls `GET /v1/attendance?sectionId=…&onDate=…` → 403, so "Today's
    attendance" never shows.
  - Grades calls `GET /v1/assessment?sectionId=…` → 403, so "Grades this year"
    is stuck on "Loading…" with a `forbidden` banner.
- **Also:** `guardians/{id}/students` and `announcements` each fire 3× per page
  load.
- **Expected:** Use child-scoped endpoints (e.g.
  `/v1/attendance/students/{id}`), which already work and are guarded by
  `SelfScope`.

### BUG-17 Parent leave request can only start today
- **Where:** `apps/parent-app/app/attendance/page.tsx:169` — the from-date is a
  disabled text input showing `todayIso()`.
- **Impact:** A parent can't request leave for a future trip. The two date
  inputs also look different (text vs date picker).

### BUG-18 Driver linking doesn't give app access; link target hidden
- **Where:** school-web Transport → Drivers; driver-app.
- **Repro:** Ramesh is shown "linked" as a driver, but signing into the driver
  app shows "You do not have permission to do this" and "No routes set up for
  this school yet" (route R1 exists). Linking doesn't grant the `driver` role.
- **Also:** Driver "Suresh Babu" is linked to **Priya's** staff record. The UI
  only says "linked", never to whom.
- **Expected:** Linking grants (or requires) the driver role and shows the
  linked account. The driver app says "not set up as a driver" instead of "no
  routes".

### BUG-19 Refresh tokens are never revoked or rotated
- **Where:** `apps/api/.../iam/api/AuthController.java:156-175`; there is no
  logout endpoint.
- **Actual:** Refresh token TTL is 30 days, stateless. The same refresh token
  was used 3× (all 200). Sign-out only clears localStorage, so a copied refresh
  token stays valid after sign-out. (Deactivation *is* enforced: refresh
  re-checks `is_active`.)
- **Expected:** Rotate refresh tokens, detect reuse, and add a logout that
  revokes them.

### BUG-20 Sign-in code boxes scramble a retyped code
- **Where:** `packages/ui/src/code-step.tsx:46-58`.
- **Repro:** Enter a wrong code (`123456`) and get the error. Click the first box
  and type `000000`. The result is `013456`.
- **Cause:** In a box that already holds a digit, `raw` is old + new digit
  (e.g. `"01"`), which is treated as a paste and spread across the boxes.
- **Expected:** A single keystroke in a filled box replaces that digit. Also
  consider clearing the boxes after a failed verify.

### BUG-21 Staff with no role lands on a raw error
- **Repro:** `ramesh.kumar@oakridge-hyd.test` signs into school-web and gets the
  dashboard with a red `forbidden: You do not have permission to do this`.
- **Expected:** An empty state: "You have no access yet — ask the school office
  to assign a role."

### BUG-22 Teacher app: Attendance tab shown to a teacher who can't mark
- **Repro:** Ravi (subject_teacher) in teacher-app → Attendance →
  `forbidden: You do not have permission to do this`.
- **Expected:** Hide the tab without `attendance.mark`, or explain.

### BUG-23 Teacher app: new assessment subject is guessed from the timetable
- **Where:** `apps/teacher-app/app/assessment/page.tsx:61-97` —
  `subjectBySection[slot.sectionId] = slot.subjectId` (last slot wins).
- **Impact:** A teacher with two subjects in one section can only ever create
  assessments for one of them. There's no subject picker.
- **Also:** Assessment list items render as mis-styled buttons (text misaligned
  in a bordered pill).

### BUG-24 Locked assessment still looks editable
- **Where:** school-web Assessment → a `locked` assessment.
- **Repro:** Enter marks → change one → Save all. The server refuses correctly,
  but:
  - The error is shown at the page top, off-screen.
  - The error exposes a UUID and an API path: `conflict: Assessment 09c30a3c-…
    is locked; reopen it through /v1/assessment/{id}/status with a reason…`.
  - The input keeps the unsaved value.
  - "Add component" and the mark inputs stay enabled.
- **Expected:** Read-only grid when locked, and the error next to the grid in
  user language.

### BUG-25 Duplicate assessments allowed
- **Evidence:** Two "Periodic Test 1 / Mathematics / PT / 20 / 2026-08-15" in
  10-A (one draft, one locked).
- **Expected:** Warn or refuse the same name, subject, section and date.

### BUG-26 Dashboard shows school-wide fees and admissions to teachers
- **Repro:** Ravi (subject_teacher) dashboard shows Fees month-to-date and the
  full Admissions funnel.
- **Decision needed:** Is `dashboard.view` meant to include finance and
  admissions aggregates? If not, gate each card by its module's permission.

### BUG-27 Admissions transitions are one click, no confirm or undo
- **Where:** school-web Admissions stage lists ("Move to" → Rejected / Lapsed / …).
- **Also:** "Show offers" from the expiry banner opens all offered applications,
  not the expired ones, and they aren't sorted by expiry.

---

## S3 — Cosmetic / UX

- **BUG-28** Raw error codes shown to users: `bad_request:`, `internal_error:`,
  `forbidden:`, `constraint_violation:` prefixes; field names like "maxPicks
  must be at least minPicks"; "Cannot reversal"; "on day 1" instead of "Monday".
- **BUG-29** Errors render at the page top, out of view of the control that
  failed (Fees adjustments, Assessment save).
- **BUG-30** Stale banners persist across tabs ("Saved attendance for 3
  student(s)" on the Amendments, Leave and Cover tabs).
- **BUG-31** Raw ISO timestamps (`2026-09-26T15:05:22.354110Z`) in fee payments
  and Comms threads; timetable times with seconds (`11:00:00–11:40:00`).
- **BUG-32** Message threads labelled by UUID fragment (`Thread 465744df`) with
  no subject or participants (school-web Comms, parent-app Messages).
- **BUG-33** Attendance register shows roll numbers only, no student names; a
  child without a roll number shows as `—`.
- **BUG-34** Horizontal overflow: fee invoice detail (GST and Details columns
  cut off; mouse-wheel scroll doesn't move the page there); Roles screen chips
  clipped.
- **BUG-35** Fee invoice line description is blank.
- **BUG-36** One-click destructive actions with no confirm: leave Withdraw,
  timetable Delete, admissions Rejected.
- **BUG-37** Public track page placeholder `WEB-XXXXXXXX` doesn't match issued
  numbers (`APP260002`), and "Track this application's status" doesn't prefill
  the number.
- **BUG-38** Parent app sign-in uses its own single code field instead of the
  shared `CodeStep`, and it isn't auto-focused.
- **BUG-39** Marks input reverts to the old value after a refused save
  (over-max / negative), so the user can't see what they typed.
- **BUG-40** Dashboard says "Signed in as staff · chain chain_smoketest" (raw
  schema name) and shows attendance "0%" when nothing has been marked (should
  be "—").
- **BUG-41** Transport: cash payments post to the `BANK` ledger account rather
  than a cash account (confirm with accounting whether intended).

---

## Not a bug, noted

- In dev mode a full page load takes ~12 s between the JS chunks and the first
  API call. Input typed before hydration is wiped (seen twice on `/login`).
  Likely dev-only; worth checking on a production build.
- Seed data inconsistency: admissions funnel shows 312 "Enrolled" applications
  against 3 students on the register.
- Legacy leave "fever" (08-10→08-11) is `approved` but was never materialised
  (`materialised_at` NULL).

## Verified working

- **Sign-in:**
  - Unknown identifier: no enumeration before the code step.
  - Wrong code: rejected.
  - Garbage or tampered JWT: 401.
- **Validation that held:**
  - Attendance refused for future dates and weekends.
  - Academic year and term: overlap, reversed dates and out-of-year all refused.
  - Elective blocks: min/max and picks vs options checked.
  - Marks: over-max and negative refused per row.
  - Offer validity must be at least 1 day.
  - CSV import preview lists row errors clearly.
- **Authorization that held:**
  - Guardian IDOR on another child: attendance, report cards, re-evaluations,
    invoices, dues and profile all 403 via `SelfScope`.
  - Cross-chain access blocked (header spoof ignored).
  - Chain admin confined to `/v1/tenancy/schools` and `/v1/iam/me`.
  - Principal token refused on platform-admin endpoints.
  - Direct URL to an unpermitted screen redirects to the dashboard.
- **Other checks that held:**
  - Payments ≤ 0 refused by the API.
  - Overpayment ledger legs balance (`ADVANCE`).
  - Platform-web chain slug validation.
  - Refresh refuses deactivated accounts.

## Test data left in the dev DB (`chain_smoketest`)

- Applications APP260001 (Zara Browsercheck) and APP260002 (Public
  Browsercheck).
- Vehicle `ts07xy9988` (capacity −5).
- Draft school "Dup School" (`Bad Slug!`).
- Exam week BT-1 (2028).
- ₹10,000 cash payment on INV-0002.
- SEED-0313 moved offered → lapsed.
- Attendance for 10-A on 2026-09-25 and 2026-09-01.
- Leave "Family function" (08-10) approved then withdrawn.
- New leave request 2026-09-26 → 2026-10-01.
- Class Test 1 mark for Ananya 76 → 77 (by Ravi).
