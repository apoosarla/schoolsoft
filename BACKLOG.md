# Schoolsoft — Backlog

Running list of things identified as needed but not yet built. Not sequenced —
check `schoolsoft-design.md` §19 (MVP vs Phase 2 vs Phase 3) for priority context.

---

## Done

- ~~Platform-admin API endpoint for chain provisioning.~~ Added
  `ChainAdminController` (`apps/api/.../tenancy/api/`) — `GET/POST
  /v1/platform-admin/chains`, wraps `ChainProvisioningService`, guarded to
  `subjectType == 'platform_admin'` via `TenantContext` (throws the new
  `ForbiddenException` → 403 otherwise). 2026-08-02.

- ~~Chain HQ Console app — tenant/school onboarding UI.~~ Scaffolded
  `apps/hq-web` (Next.js App Router + TS). `/chains` page lists chains and
  provisions new ones against the endpoint above. 2026-08-02.

- ~~Build verification gap.~~ Both apps now actually compile/build/boot in
  this environment (Java 25 + Maven locally; Node 24 + npm for hq-web).
  `./mvnw -DskipTests package` produces a working jar; `npm install && npm
  run build` produces a working Next.js build. All of the below was verified
  against a live local Postgres instance, not just compiled. 2026-08-03.

- ~~Rename mcb → schoolsoft.~~ Folder, git repo, Java packages
  (`com.mcb` → `com.schoolsoft`), config keys (`mcb.*` → `schoolsoft.*` in
  both `application.yml` and every `@Value`), npm workspace package names
  (`@mcb/*` → `@schoolsoft/*` in root and `hq-web` `package.json` — these
  were missed by the first rename pass since nothing had `npm install`ed
  against them until the HQ Console work below), `MCB-design.md` →
  `schoolsoft-design.md`. 2026-08-02/03.

- ~~Stack upgrade.~~ Java 21 → 25, Spring Boot 3.3.4 → 3.5.16, Spring
  Modulith 1.2.4 → 1.4.12. 2026-08-02.

- ~~Academic Setup CRUD.~~ Campus, Term, Subject, Section-Subject-Teacher
  assignment, plus create endpoints for Academic Year/Grade/Section
  (previously read-only) and section→curriculum binding — all in
  `tenancy.api.SchoolController` / `tenancy.internal.SchoolRepository`.
  2026-08-02.

- ~~Curriculum Engine.~~ New `curriculum` module: platform-level templates
  (seeded CBSE Class 10 Maths + Cambridge IGCSE Maths 0580, migration V003),
  clone-from-template with recursive tree materialisation (path/depth),
  manual node/learning-outcome authoring, publish. 2026-08-02.

- ~~Academic Core + Operations + LMS + Comms + Hardware backend surface.~~
  New modules, each with DTOs/repository/controller and smoke-tested
  end-to-end against live Postgres: `enrolment`, `admissions` (full funnel +
  convert-to-student), `attendance` (day-level + period-level marking,
  leave), `timetable` (teacher clash detection), `assessment` (components,
  marks, report cards), `fees` (invoices, idempotent payments, double-entry
  ledger), `lms` (content, lesson plans, homework, quiz engine), `comms`
  (announcements, 1:1 messaging), `transport` (routes/stops/GPS/trips +
  geofence check), `library` (catalogue, issue/return with late fees),
  `device` (biometric/RFID registry + attendance bridge), `boardintegration`
  (CIE Direct/UDISE+ export job framework — adapter itself is a stub, no
  sandbox credentials available), `dashboard` (single-school operational
  overview). 2026-08-02/03.

- ~~Feature Flags / Theming / Audit / File admin surfaces.~~ These
  Layer-0 modules had working internal logic but no way to actually use them
  — `FeatureFlags` had no write endpoint, `ThemeController` had no update
  endpoint, `AuditService` had no query endpoint, `FileService` had no
  controller at all (completely unreachable over HTTP). All four now have
  full read/write REST surfaces. 2026-08-03.

- ~~HQ Console per-chain stats.~~ `GET
  /v1/platform-admin/chains/{id}/stats` (school count, active enrolments,
  active staff, fees collected) using the Risk R12-sanctioned fan-out
  pattern; wired into the `/chains` page as a per-row "Stats" toggle.
  Verified in-browser. 2026-08-03.

- ~~School Admin Web — Admissions page.~~ New `/admissions` route: create
  application, filter by state, per-row state transition, and enrol-to-
  student (`AdmissionsController`'s `/enrol`) with section picker scoped to
  the applicant's grade. `lib/api.ts` gained the admissions + academic-years
  + grades clients. Verified live end-to-end against local Postgres (chain
  `smoketest`, staff OTP login) — created an application, moved it through
  `accepted`, enrolled it, and confirmed the resulting row in `/students`.
  2026-08-09.

- ~~School Admin Web — Fees page.~~ New `/fees` route: student search,
  fee-head management (create/list), invoice creation with dynamic
  multi-line items and live subtotal/GST/total, per-invoice drill-down
  (lines + payment history), and payment recording against `FeesController`.
  `lib/api.ts` gained the fees clients. Verified live end-to-end against
  local Postgres — created a fee head, raised a ₹5,250 invoice with GST,
  recorded a ₹2,000 UPI payment, watched status flip `open` → `partial`
  with the correct paid amount. 2026-08-09.

- ~~School Admin Web — Timetable page.~~ New `/timetable` route: per-section
  weekly grid, add-slot form (subject/teacher/day/period/time/room), delete.
  `lib/api.ts` gained the subjects, staff, and timetable clients. Verified
  live end-to-end — confirmed `TimetableRepository`'s teacher-overlap clash
  guard surfaces as a readable error in the form, then added and deleted a
  non-conflicting slot successfully. 2026-08-09.

- ~~School Admin Web — Assessment page.~~ New `/assessment` route:
  per-section assessment list/create, status transitions (draft → …→
  published), component authoring, and roster-based mark entry (pre-filled
  from existing marks, bulk "Save all"). `lib/api.ts` gained the assessment
  + components + marks clients, plus `sectionId` support on `listStudents`.
  Report cards (`ReportCardDto`, generate/lock) intentionally left out —
  next slice if needed. Verified live end-to-end: entered/persisted marks
  for a 3-student roster, created a new assessment + component from
  scratch, and drove a status transition (`draft` → `scheduled`).
  2026-08-09.

- ~~School Admin Web — Library, Comms, LMS pages.~~ Three more routes:
  - `/library` — catalogue search/create, per-title copy list + add-copy,
    issue-to-student (with student search), active-loans-by-student panel
    with return.
  - `/comms` — announcements (create, scope/channel picker, publish) and a
    messaging panel (thread list for the signed-in account, view/reply, and
    thread *creation* via a directory-backed participant picker — see
    below).
  - `/lms` — per-section lesson plans (create + status workflow) and
    assignments (create + view/grade submissions). Content items and the
    quiz engine (authoring + attempts) intentionally left out — quiz
    question/option/answer authoring is a distinct enough UI investment to
    warrant its own pass.
  `lib/api.ts` gained the library, comms, and lms clients. Verified live
  end-to-end: added a library copy and ran it through issue → active-loan →
  return; created and published an announcement, then sent/received a
  message in a thread (seeded directly in Postgres since none existed for
  the test account); created a lesson plan and drove its status, and
  re-graded an existing assignment submission, confirming persistence on
  reselect. 2026-08-09.

- ~~User-directory endpoint.~~ `GET /v1/people/directory` (schoolId, +
  optional `q` / `subjectType`) resolves `user_account` rows to display
  names by joining whichever table `subject_type` points at
  (staff/guardian/student — `chain_admin` excluded, school-less).
  `PeopleRepository.listDirectory`. Closes the gap noted above: wired into
  `/comms`'s "New thread" flow as a search-and-add participant picker.
  Verified live: searched, added a second staff member as a participant,
  created the thread, and sent/received a message in it. 2026-08-09.

- ~~Role-based screen access (RBAC) for admin-web.~~ Seeded 11 personas for
  a Cambridge-curriculum international school in India — Principal, Vice
  Principal, IT Administrator, Cambridge Coordinator, Exams Officer,
  Registrar, Class Teacher, Subject Teacher, Accountant, Librarian, Front
  Office — plus support for arbitrary custom roles (e.g. "Sports
  Coordinator"). Built on the `staff_role` grant table that was already in
  the schema (§5) but unused until now, rather than inventing a parallel
  system: new `role` catalog table (code, name, description, `screen_keys`
  text[], is_system) with an FK from `staff_role.role_code → role.code`
  (V013). New `iam` endpoints — `RoleController`: `GET/POST /v1/iam/roles`,
  `PUT/DELETE /v1/iam/roles/{id}` (system roles can't be deleted),
  `GET /v1/iam/staff-roles`, `POST /v1/iam/staff-roles/{assign,unassign}`,
  `GET /v1/iam/me/screens` (union of `screen_keys` across the caller's role
  grants, via `Authz.rolesOfCurrentUser()`). admin-web: `Session` gained
  `screens`/`roleCodes`, fetched right after OTP verify; `Nav` is now a
  client component that only renders links the session has access to; every
  page gained a `hasScreen(s, "...")` redirect-to-`/dashboard` guard;
  `dashboard` is always implicitly allowed so nobody lands on a blank page.
  New `/roles` page (itself gated behind the `admin` screen key, held only
  by Principal/Vice Principal/IT Administrator): role catalog with
  create/edit-screens/delete, and a staff roster for assigning/unassigning
  roles (multi-role per staff member).

  Deliberately decoupled from *how* the caller authenticated — role
  resolution keys off `staff_id` reached via `user_account`, not off OTP
  specifics — so a future school-SSO integration only replaces the
  identity-verification step in `AuthController`; the role catalog, grants,
  and every screen-gating check on the frontend stay untouched. When that
  lands, Keycloak (or whatever brokers to the school's real IdP) is the
  natural fit *in front of* this model, not a replacement for it — group→role
  mapping is a small addition, not a rewrite.

  Verified live end-to-end: seeded roles came back correctly from
  `/v1/iam/roles` (including the apostrophe in "section's" surviving the
  seed SQL); assigned `principal` to one staff member and confirmed
  `/me/screens` returned the full union; created a custom `sports_coordinator`
  role via the API, confirmed system-role deletion is rejected (400); in the
  browser, logged in as the Principal (full nav incl. Roles & Users),
  created/assigned/removed roles through the `/roles` UI; logged in as a
  second staff member holding only `subject_teacher` + the custom role and
  confirmed the nav showed exactly the union of both roles' screens, and
  that navigating directly to `/fees` or `/roles` by URL redirected to
  `/dashboard`. 2026-08-09.

## Bugs found and fixed along the way

Worth keeping a record of these since none were caught until something
actually exercised the code path — a reminder that "compiles" and "correct"
are different claims:

- `NotificationRepository`: uncaught `SQLException` from `PGobject.setValue`
  — the whole module had never compiled before.
- `DataSourceConfig`: bean name collision on `DataSourceProperties` surfaced
  by the Spring Boot 3.5 upgrade.
- `ChainProvisioningService`: outer `@Transactional` conflicted with
  `ChainSchemaMigrator`'s `propagation = NEVER` — chain provisioning was
  completely broken (500 on every call) until this session.
- `attendance_record`'s `UNIQUE(student_id, on_date, period_no)` doesn't
  dedupe day-level rows under Postgres NULL semantics — added a partial
  unique index (V010).
- Nine call sites doing `(Double) rs.getObject(col)` on `NUMERIC` columns —
  Postgres returns `BigDecimal` there, not `Double`; every one threw
  `ClassCastException` on first real read. Added `platform.db.Jdbc#nullableDouble`.
- `Enrolment`/`Timetable`'s business-rule guards threw `IllegalStateException`,
  which `GlobalExceptionHandler` doesn't map — surfaced as raw 500 instead
  of 400.
- `transport_stop.school_id` is `NOT NULL` but `addStop()` never populated
  it — every stop insert failed until fixed.
- `ThemeController`'s original upsert used `INSERT..ON CONFLICT` with
  `COALESCE` on the `VALUES` side, so a partial update (e.g. accent color
  only) would silently reset the primary color to the schema default.
- `ChainSchemaMigrator` computed the tracked `schema_version` from Flyway's
  `result.targetSchemaVersion`, which is only populated when `migrate()`
  actually applies something — every restart after the first (a no-op
  migrate call) silently zeroed `platform.chain.schema_version` and
  `platform.chain_schema_version`. Found via the new HQ Console stats UI.

## Open items

- **Platform-admin login flow.** The HQ Console's `/chains` page still
  requires pasting in a bearer token by hand — `AuthController` /
  `UserLookupService` only resolve identities that live inside a chain schema
  (staff/guardian/student); nothing resolves against `platform.platform_user`
  or issues a `platform_admin` JWT. Needs an OTP-or-password flow scoped to
  the platform schema before the HQ Console is usable by anyone but a dev
  with direct DB/token access.

- **School Admin Web, Parent app, Teacher app, Driver app, Public/Admissions
  microsite.** None of these surfaces exist yet — only `hq-web` (chain-level)
  has any frontend. The backend now has working endpoints for essentially
  everything a School Admin Web console would need (academic setup,
  admissions, attendance, timetable, assessment, fees, LMS, comms,
  transport, library, dashboards) — that's the natural next frontend to
  scaffold, since it's the primary consumer of nearly all of the above.

- **Real external integrations.** GST e-Invoice (NIC IRP), Tally/Zoho Books
  sync, LTI 1.3 / OneRoster 1.2 (real OAuth/OIDC flows), CIE Direct / UDISE+
  actual HTTP adapters (the job-queue framework exists — `boardintegration`
  module — but `process()` is a stub with a canned result; no sandbox
  credentials available in this environment to build/test a real client
  against), WhatsApp Business API. All need real credentials/sandboxes this
  environment doesn't have.

- **Cross-module audit trail.** `AuditService.record(...)` exists and has a
  query endpoint now, but none of the ~15 new modules built this session
  call it on their mutations — attendance marks, fee payments, mark entry,
  etc. are not yet audit-logged. Retrofitting it across every controller is
  a real but mechanical chunk of work.

- **Chain HQ multi-chain warehouse dashboards.** The per-chain stats
  endpoint added this session is deliberately the Risk-R12-sanctioned
  "small fan-out, hard tenant cap" MVP posture — a real cross-chain
  analytics warehouse is explicitly Phase 2 per design doc §19.

- **School SSO / Keycloak.** Auth is still custom JWT + OTP
  (`AuthController`, `OtpStore`, `JwtService`) — deliberately, so the new
  role/screen-access model (see RBAC entry above) stays decoupled from
  identity. When the school's real SSO is ready to wire up: add a new
  `AuthController` path that validates an externally-issued token (via
  Keycloak as broker, or directly against the IdP), resolves it to a
  `user_account` by email, and issues our existing JWT shape — the `role`,
  `staff_role`, and every frontend screen-gating check are untouched by
  this. Worth designing then, not now: a small lookup table mapping IdP
  groups → our role `code`s, so group membership can auto-assign roles
  instead of the manual assignment `/roles` does today.

---

_Added 2026-08-02, from a conversation reviewing SSO/RBAC plans and noticing
tenant onboarding had no admin surface. Rewritten 2026-08-03 after a session
that built out nearly the entire MVP backend module surface — see git log
for the full sequence of commits._
