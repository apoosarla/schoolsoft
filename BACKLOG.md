# Schoolsoft — Backlog

Running list of things identified as needed but not yet built. Not sequenced —
check `schoolsoft-design.md` §19 (MVP vs Phase 2 vs Phase 3) for priority context.

---

## Borrowed from healthcare-initiative — remaining

A comparison against the sibling HMS codebase (same architecture, further
along) surfaced these. Four of what it found are closed — see the first four
entries under **Done** below.

- **No unit tests.** Everything runs through HTTP against a real database, so
  pure logic — fee generation, grading bands, rollover date maths, sibling
  policy — cannot be exercised without Postgres and has no fast test. HMS runs
  domain tests as plain JUnit with no Spring context; the equivalent here is
  extracting that logic from the repositories first.

- **The six frontends have zero tests.** HMS's `lims-web` is 385 source files
  to 187 test files. Vitest + React Testing Library is the shape.

- **No shared UI package, beyond a first piece.** `packages/ui` now exists and
  holds exactly one thing — the sign-in `CodeStep`, extracted 2026-09-22. What
  is still duplicated: `app-shell.tsx` four times (216/129/113/83 lines) across
  school/parent/teacher/driver, and the design tokens, which are copied into
  each app's `globals.css` rather than shipped. `packages/api-client` is the
  model for how far this should go — one implementation of bearer auth, error
  mapping and the 401 → refresh → replay dance.

- **Frontends have no per-route permission check.** `screen_keys` drives
  navigation, which is fine — frontend gates are UX and the backend is the
  boundary — but there is no route guard and no test that a route declares
  one. HMS fails its build on a route with no `beforeLoad` unless it is listed
  as deliberately open.

- **Slow certification fixture.** `CertificationFixture` drops and
  re-provisions the whole chain schema on every run. HMS onboards once per JVM
  and then restores a snapshot (truncate the non-empty tables, copy the
  reference rows back with FK triggers off), measured at 5s/method → a
  fraction. Its worker-per-database trick also runs ITs 4-way parallel
  (2.0s/method vs 5.8s serial) — read its CLAUDE.md "A database per test
  worker" section first, which documents the disk-fill and DDL-deadlock traps
  that make it look like mass test breakage.

### Gaps opened by the authorization work

- ~~**`driver` holds school-wide `student.view`.**~~ Fixed 2026-09-09. The
  route roster is the route-scoped student read that was missing: it returns
  the rider's name, admission number and section, so driver-app no longer walks
  `/v1/people/students/{id}` per rider, `V029` revokes the grant, and
  `RouteScope` narrows the roster to the routes the caller is rostered to drive
  today. See GAP-31 below.
- **RLS covers a table only if it carries `school_id`.** V009 (and the blocks
  V021/V022/V025 copied from it) enable row-level security on every table that
  has the column, so cross-school isolation inside a chain is the database's
  job and not the handler's. A table without the column has no policy, and is
  safe only while every read of it joins an RLS-covered parent.
  `gps_ping` was the one that did not — `GET /v1/transport/vehicles/{id}/gps-pings`
  read by vehicle id alone, so any staff or parent token (every guardian holds
  `transport.track`) could follow any bus in the chain, another school's
  included. Fixed 2026-09-09 by joining `vehicle`; pinned in `cert_SEC_04`,
  which fails without the join.

  **Audited 2026-09-09.** All 33 chain tables without `school_id`, and all 139
  SQL statements touching one. Twelve reads and one write did not join a
  covered parent, each returning or writing another school's rows to a valid
  token: invoice lines, assessment components, library copies, terms,
  curriculum nodes, learning outcomes, thread messages, assignment
  submissions, quiz questions, quiz attempts, admission events, a bus's GPS
  trail — and `assignRole`, which wrote a `staff_role` row onto another
  school's staff because nothing tied the request's `schoolId` to the caller's
  own. All fixed by joining the covered parent; `CrossSchoolIsolationTest`
  (harness) is the regression net and each case was confirmed to fail before
  the fix. `role`, `role_perm`, `feature_flag`, `outbox`, `ledger_account` and
  `flyway_schema_history` are chain-global by design and stay unpolicied.
- **Exam schedule reads do not filter unpublished.** `exam.view.own` lets a
  family read `/v1/exams/schedules` and the repository does not restrict to
  published. Pre-existing; the gate did not introduce it.
- ~~**Teacher grants are school-wide.**~~ Fixed 2026-09-09. The *grants* are
  still school-wide — `class_teacher` and `subject_teacher` hold
  `attendance.mark` and `mark.enter` across the school, which is what
  `staff_role` records — but the section-keyed academic **reads** are now
  confined by `TeacherScope`. See GAP-31 under "Gaps found by running the
  certification suite" below.
- **`AuditInterceptor` runs ahead of method security.** It is a web
  interceptor, so an `@Audited(requireReason = true)` endpoint called without a
  reason answers 400 about the payload even when the caller would have been
  refused 403.

---

## Done

- ~~A chain's own admin signed in to the vendor's console.~~ `admin-web` and
  `hq-web` were named for who sat at them from our side of the table, and
  `hq-web` served two unrelated people behind a two-tab login: Schoolsoft's
  operators, and a customer's chain HQ. Renamed to `school-web` (:3001) and
  `platform-web` (:3002), and the chain HQ moved into `school-web` at
  `/chain` — the same app as the schools it opens, reached through the same
  OTP door with the chain's slug. `homeFor(session)` routes staff to
  `/dashboard` and a `chain_admin` to `/chain`; the shell's menu branches on
  `isChainAdmin` rather than on `screen_keys`, since a chain admin holds no
  role for a grant to hang on.

  The move needed a gate that did not exist. A `chain_admin` has no `sid`, and
  V009 reads `... OR current_school_id() IS NULL` — so a school-scoped read
  made with their token answers 200 with **every school in the chain merged**,
  not 403 and not empty, because they hold every unrestricted read. While the
  two apps were separate, the app boundary was the only thing preventing it.
  `TenantResolverFilter.CHAIN_ADMIN_PREFIXES` is now the thing preventing it:
  `/v1/tenancy/schools` and `/v1/iam/me`, refused before the handler runs.
  `RbacEnforcementTest.chainAdminCannotReachASchoolScopedRead` is the guard.
  2026-09-21.

- ~~The API had no authorization at all.~~ 29 controllers, ~230 endpoints,
  zero `@PreAuthorize`, and no `@EnableMethodSecurity` — `.anyRequest()
  .authenticated()` was the whole model, so any valid token could call any
  endpoint. `POST /v1/iam/staff-roles/assign` was reachable by a guardian's
  token, i.e. any parent could make themselves `it_admin`. Now: `Perm`
  vocabulary (96 codes), `role_perm` grants (`V026`), 268 `@PreAuthorize`
  annotations, `SelfScope` for the `.own` half, and eleven ArchUnit rules that
  fail the build on drift. `docs/adr/0001-authorization-model.md`. 2026-08-30.

- ~~The audit log was not tamper-evident.~~ `audit_log` was an ordinary table:
  a row could be edited or removed and the log still read as consistent. Now a
  Postgres trigger chains every row to the hash of the row before it (`V027`),
  `UPDATE` is refused outright, and `GET /v1/audit/chain` walks the chain and
  names the first break. `AuditChainTest` tampers the way an attacker with
  database access would and asserts it is caught. 2026-08-30.

- ~~Report-card transitions were read-then-write.~~ `publish` checked for
  `draft` and then wrote unconditionally, so a card unlocked in between got
  published anyway — a family shown a card the school had taken back. `lock`
  guarded on `status <> 'published'`, so against a published card it updated
  nothing and reported success. Each transition is now one conditional UPDATE
  naming the state it moves out of, with zero rows affected treated as a
  conflict. `ReportCardTransitionTest`. 2026-08-30.

- ~~Lost updates on records two people edit as a form.~~ `role` (name,
  description, screen list) and `fee_structure` (whose line editor deletes every
  line and re-inserts) were blind overwrites: the later save won, the earlier
  one vanished, nobody was told. Both now carry a `version` the client sends
  back, with a 409 on a stale save. Deliberately not applied to invoice
  arithmetic (`paid = paid + ?` is computed in the database and already atomic)
  or to status columns (state machines, better served by a conditional
  transition) — `V028__optimistic_locking.sql` argues both. `academic_year`
  transitions got that conditional treatment at the same time: activating or
  reopening previously read the status and then wrote unconditionally, so a year
  closed in between could be reopened with no reason and no `reopened_by`.
  `ArchitectureTest` fails the build on an unversioned write to a versioned
  table. 2026-08-30.

- ~~Authorization decided inside four repositories.~~ `AttendanceRepository`,
  `SchoolRepository`, `AssessmentRepository` and `PeopleRepository` imported
  `Authz` and decided access in SQL helpers, where nobody auditing "who may do
  this" would look. `Authz` is now split: `CampusScope` (a filter — answers "of
  what", stays next to the SQL) and the role checks (decisions — moved into
  `LeaveAuthorizer` and `AssessmentAuthorizer`, alongside the existing
  `AttendanceAuthorizer`). `ArchitectureTest` fails the build on a repository
  that reaches for `Authz`. 2026-08-30.

- ~~`publicsite` reached into `admissions.internal`.~~ The unauthenticated
  public site held a reference to `AdmissionsRepository`, so `transition`,
  `recordTestScore` and `convertToStudent` were one call away from a
  no-token endpoint. Now goes through `admissions/api/PublicAdmissions`, a
  two-method interface. Enforced by `ArchitectureTest`. 2026-08-30.

- ~~Platform-admin API endpoint for chain provisioning.~~ Added
  `ChainAdminController` (`apps/api/.../tenancy/api/`) — `GET/POST
  /v1/platform-admin/chains`, wraps `ChainProvisioningService`, guarded to
  `subjectType == 'platform_admin'` via `TenantContext` (throws the new
  `ForbiddenException` → 403 otherwise). 2026-08-02.

- ~~Chain HQ Console app — tenant/school onboarding UI.~~ Scaffolded
  `apps/platform-web` (Next.js App Router + TS). `/chains` page lists chains and
  provisions new ones against the endpoint above. 2026-08-02.

- ~~Build verification gap.~~ Both apps now actually compile/build/boot in
  this environment (Java 25 + Maven locally; Node 24 + npm for platform-web).
  `./mvnw -DskipTests package` produces a working jar; `npm install && npm
  run build` produces a working Next.js build. All of the below was verified
  against a live local Postgres instance, not just compiled. 2026-08-03.

- ~~Rename mcb → schoolsoft.~~ Folder, git repo, Java packages
  (`com.mcb` → `com.schoolsoft`), config keys (`mcb.*` → `schoolsoft.*` in
  both `application.yml` and every `@Value`), npm workspace package names
  (`@mcb/*` → `@schoolsoft/*` in root and `platform-web` `package.json` — these
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

- ~~Role-based screen access (RBAC) for school-web.~~ Seeded 11 personas for
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
  grants, via `Authz.rolesOfCurrentUser()`). school-web: `Session` gained
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

- ~~Modern responsive design for school-web.~~ Prototyped a redesign first as
  a standalone artifact (four persona dashboards — Principal, Class Teacher,
  Accountant, Librarian — demonstrating the RBAC nav filtering visually)
  before touching the real app; design plan: cool "paper" neutral (not the
  cliché warm-cream/terracotta AI look), Oxford-indigo accent with brass/gold
  used sparingly, semantic status colors kept separate from the accent,
  Georgia serif for titles/headings paired with system-sans for
  everything operational, tabular numerals throughout.

  Ported into `apps/school-web` as a **CSS-and-shell-only** change — zero
  edits to any of the 11 page components. This worked because every page
  already funneled its markup through a small, consistent class contract
  (`.panel`, `.stat-grid`/`.stat-tile`, `.badge`/`.badge-active`/
  `.badge-suspended`, `.error-banner`, `.hint`, `table`, `input`/`select`/
  `button`) — rewriting `globals.css` against that same contract restyled
  every page for free. The only new files: `app-shell.tsx` (replaces the old
  `nav.tsx`) — a persistent sidebar with icons + active-state, an
  icon-rail collapse under 1024px, a slide-over drawer with scrim under
  640px, and a footer showing the signed-in user's role(s) (humanized from
  `session.roleCodes`) with sign-out; `layout.tsx` now just wraps
  `{children}` in `<AppShell>`.

  Caught one real bug during redesign QA that predates this session: the
  Roles & Users table's badge-chip rows (up to 11 per role) had no
  overflow container and would blow out the card at tablet width. Fixed by
  giving `.panel` `overflow-x: auto; max-width: 100%` globally — the classic
  wide-content-needs-its-own-scroll-container pattern, verified via computed
  `scrollWidth`/`clientWidth` (the panel clips to its parent while its
  content stays independently scrollable). Verified live end-to-end:
  full desktop sidebar, tablet icon-rail, mobile drawer, and both prototype
  themes, plus the real app's Dashboard/Students/Fees/Roles pages rendering
  actual backend data through the new design. 2026-08-10.

- ~~platform-web's operator door.~~ Done 2026-09-22. It was one page toggling
  between the chain-HQ and platform-admin flows; the HQ half moved to
  school-web first (`ddfc2e7`), and what is left is a single door on the
  school-web sign-in layout — deliberately not school-branded, resolving no
  tenant, saying instead that platform accounts are a separate register and
  that the session is audited. It takes the code in `@schoolsoft/ui`'s
  `CodeStep`, and the console's topbar no longer frames it. A wrong address
  gets the same sentence as a deactivated one, because the resolver 404s both
  and the difference would say which addresses are Schoolsoft's.
- ~~Same design system ported into platform-web.~~ platform-web (`/`, `/chains`) used
  the exact same original bare-bones dark CSS and class contract as
  school-web pre-redesign, so the same zero-page-edit trick applied:
  swapped `globals.css` for the same token system (scoped down — no
  sidebar/gold-accent/serif-in-cards machinery this 2-page app doesn't
  need), added active-link state to the topbar nav via a small `nav.tsx`
  client component (the only structural change; `chains/page.tsx`
  untouched). `.panel` got the same `overflow-x: auto` treatment
  preemptively since the chains table has 9 columns. Verified live against
  the real API: hand-crafted a dev-secret-signed `platform_admin` JWT
  (no login flow exists yet — see the open item below) to drive the
  `/chains` page with real data — token-set badge, chain table, and
  confirmed via computed `scrollWidth`/`clientWidth` that the wide table
  scrolls inside its card rather than pushing the page wider. (The
  hand-crafted JWT was a stopgap — a real login flow landed right after,
  see below.)
  2026-08-10.

- ~~Platform-admin login flow.~~ platform-web's paste-a-bearer-token workaround is
  gone. New OTP flow parallel to the existing chain one, but resolving
  against `platform.platform_user` instead of scanning chain schemas (that
  table existed since the original platform migration but nothing had ever
  queried it): `UserLookupService.resolvePlatformAdmin`/
  `resolvePlatformAdminById`, and `AuthController`'s
  `POST /v1/auth/platform-admin/otp/{start,verify}` — same dev OTP bypass
  (`000000`) as the chain flow. `resolveById` (used by the shared
  `/v1/auth/refresh` endpoint) now branches on `chainSchema == "platform"`
  so refresh works for platform admins too, not just chain accounts. Added
  a seed platform-admin row (`platform/V004`) since there was previously no
  way to create the first one short of a manual `INSERT` — a real
  chicken-and-egg gap, same shape as the curriculum-template seed in
  `platform/V003`. platform-web: new `/login` page (mirrors school-web's OTP login
  UI, minus the chain-slug field platform admins don't have), `/chains` now
  guards on `isLoggedIn()` instead of exposing a raw token paste-box.
  Verified live end-to-end against local Postgres: start → verify with the
  dev bypass code → token issued → chains list loads with real data → sign
  out → redirected to `/login`; also verified via curl that `/v1/auth/refresh`
  correctly re-issues a platform-admin access token, and that an unknown
  email 404s at verify time without leaking existence at start time (same
  behavior as the chain OTP flow). 2026-08-10.

- ~~Teacher app — Login, Today, Attendance.~~ New `apps/teacher-app`
  (`@schoolsoft/teacher-app`, `next dev -p 3003`; added to root
  `package.json`'s workspaces — the slot already existed — and a
  `teacher:dev` script). Mobile-first, not school-web's sidebar shell:
  single scrolling column under a fixed bottom tab bar (Today, Attendance —
  written so a third tab drops in without restructuring), 44px+ touch
  targets, same design tokens as school-web/platform-web (Oxford-indigo,
  Georgia/system-sans, light-default with a dark media-query override).
  Reuses the existing chain OTP login flow. New backend endpoint
  `GET /v1/iam/me` (`RoleController` + `RoleRepository.subjectIdForUserAccount`)
  resolves the caller's `staff.id` from `user_account.subject_id` — the JWT
  only carries `user_account.id`, and "my timetable" needs the staff row.
  Today pulls `GET /v1/timetable/teachers/{staffId}`, filters to
  `dayOfWeek === new Date().getDay()`, and links each period into
  Attendance with the section pre-selected via a `?section=` query param;
  Attendance itself is the same roster/mark-bulk flow as school-web's page,
  scoped to the distinct sections the teacher's timetable actually covers.

  Found and fixed a real bug during live verification, present in
  **both** teacher-app and school-web's attendance pages: the existing-marks
  matching logic checked `e.periodNo === null`, but Jackson's
  `non_null` property inclusion (`application.yml`) *omits* a null field
  from the JSON entirely rather than serializing it as `null` — so
  `periodNo` arrives client-side as `undefined`, and the strict-equality
  check never matched. Attendance saved correctly every time, but reloading
  the page always showed everyone back at the "present" default instead of
  what was actually saved. Fixed in both apps with a loose-equality check
  (`e.periodNo == null`, catching both `null` and `undefined`) and verified
  the fix live: marked a student absent, saved, hard-reloaded the page, and
  confirmed the roster now shows the real persisted status.

  Verified live end-to-end against local Postgres and a real API instance:
  full OTP login as a real seeded teacher (`priya.menon@oakridge-hyd.test`,
  who has real `timetable_slot` rows), Today correctly showing only the
  slot matching today's day-of-week (a slot was inserted for today
  specifically to exercise this — her only other slots are Monday, and
  those correctly did *not* appear), tapping through to Attendance with
  the section pre-filled, marking and saving a real roster, and confirming
  persistence survived a hard reload after the bug fix above. Assessment,
  LMS, and Comms for the teacher app are intentionally out of scope for
  this pass — see the open item below. 2026-08-10.

- ~~Driver app — Login, Home (route/vehicle select, trip start/end, live
  GPS tracking).~~ New `apps/driver-app` (`@schoolsoft/driver-app`,
  `next dev -p 3005`), same scaffolding/design-token/OTP-login pattern as
  teacher-app, single-screen shell (topbar + one Home panel — no bottom
  tabs needed for a one-screen app). Backend: `TransportController`'s
  `GET /v1/transport/drivers` gained an optional `staffId` filter
  (`TransportRepository.listDrivers`) so the app can resolve "which
  `driver` row am I" from the logged-in staff's id via `/v1/iam/me` — the
  `driver` table's `staff_id` FK is optional (drivers don't have to have a
  login), this is the case where they do. Home: pick a route + vehicle +
  direction (`pickup`/`drop`), Start trip calls
  `POST /v1/transport/trips/start`; while a trip is active, a 20s interval
  calls `navigator.geolocation.getCurrentPosition` and posts each fix to
  `POST /v1/transport/gps-pings` (`speed`/`heading` converted from the
  Geolocation API's m/s to km/h), with a pulsing "tracking" badge and last-
  fix timestamp/coords shown live; End trip calls
  `POST /v1/transport/trips/{id}/end`. Permission-denied and no-fix cases
  show a real error instead of failing silently.

  Verified live against local Postgres and a real API instance using a
  real seeded driver (`ramesh.kumar@oakridge-hyd.test`, linked `driver`/
  `vehicle`/`transport_route` rows): OTP login, route/vehicle pre-filled
  from real data, Start trip created a real `trip` row (confirmed via
  psql), End trip stamped it with `ended_at`. One thing couldn't be driven
  through the UI in this automation environment: Chrome's native
  geolocation permission prompt is a browser-chrome dialog, not a page
  element, and the available browser-automation tooling can't grant it —
  `getCurrentPosition` never resolved (neither success nor error callback
  fired) while waiting on that prompt. Verified the actual ping pipeline
  instead by calling the same `recordGpsPing` codepath directly against
  the live API with the session's real access token and confirming the row
  landed in `gps_ping` with the right vehicle/lat/lng/speed/heading — the
  only untested piece is the browser's own permission UI, which is outside
  the app's control. Stop-by-stop check-in and trip history are
  intentionally out of scope for this pass — see the open item below.
  2026-08-10.

- ~~Parent app — Login, Home (child snapshot), Fees (view-only).~~ New
  `apps/parent-app` (`@schoolsoft/parent-app`, `next dev -p 3004`),
  bottom-tab shell (Home, Fees) same as teacher-app/driver-app's family.
  Backend: `PeopleController`/`PeopleRepository` gained
  `GET /v1/people/guardians/{id}/students` (`studentsOfGuardian`) — the
  reverse of the existing `guardiansOfStudent` — since a guardian's JWT
  only resolves to `guardian.id` via `/v1/iam/me`, and there was no way to
  go from there to "which children." Home: a chip picker if the guardian
  has more than one child, then a snapshot card (today's attendance status
  if marked, enrolment status) plus the latest 3 published announcements.
  Fees: per-child invoice list with status pills, tap-to-expand showing
  real line items and payment history — read-only, no payment gateway
  integration (that's real scope, not this pass).

  Confirmed the guardian↔student linkage and OTP auth flow actually work
  end-to-end from a real frontend (previously unverified, per this same
  open item) — created a real `guardian` row, linked it to the existing
  seeded student Ananya Rao (ADM-0001) via `guardian_student`, and gave it
  a `user_account` so it could log in like any staff/guardian identity.
  Verified live against local Postgres and a real API instance: OTP login
  as that guardian, Home correctly showing Ananya Rao's real enrolment/
  section and the two real announcements seeded earlier this session, Fees
  showing the exact two real invoices from this session's fees-page work
  (`INV-0002` partial ₹5,250, `INV-0001` paid ₹5,000) — drilling into
  `INV-0002` showed the real ₹5,000+₹250 GST line and the real ₹2,000 UPI
  payment recorded earlier, confirming the whole chain (guardian →
  student → invoice → line → payment) resolves correctly. Attendance
  history, report cards, homework, and messaging are intentionally out of
  scope for this pass — see the open item below. 2026-08-10.

- ~~Public/Admissions microsite — school info page, public inquiry form.~~
  New `apps/public-site` (`@schoolsoft/public-site`, `next dev -p 3006`) —
  no login anywhere in this app, unlike every other frontend in the repo.
  Home shows the real school name/board and a few highlight cards; `/apply`
  is a public admissions form (child + guardian details) that posts into
  the existing admissions funnel and shows a real `applicationNo` on
  success.

  New backend module `publicsite` (`PublicController` /
  `PublicLookupRepository`) exposes `GET /v1/public/schools/{chainSlug}/
  {schoolSlug}`, `GET .../grades`, and `POST .../admissions/apply` with
  **no JWT/auth at all** — genuinely new infrastructure, since every other
  endpoint in the codebase resolves `TenantContext` from a JWT via
  `TenantResolverFilter`. This is the second caller (after
  `UserLookupService`'s OTP-login lookup) of the
  `TenantContext.trustedJob(schemaName, chainId)` pattern for manually
  setting tenant context outside the request-filter flow, wrapped in
  try/finally with `TenantContext.clear()`. `apply()` resolves the current
  academic year via `is_current`, generates `applicationNo` as
  `"WEB-" + <8 random hex chars>`, and reuses the existing
  `AdmissionsRepository.create(...)` so applications land in the same
  funnel school-web's Admissions screen already reads.

  Verified live against local Postgres and a real API instance: curled
  both GET endpoints with zero auth headers (200s, correct data for the
  real seeded school/grades), submitted the `/apply` form end-to-end
  through a real browser (child "Aarav Bhat", guardian "Sunita Bhat"),
  confirmed the confirmation screen rendered a real `applicationNo`
  (`WEB-6FD86721`), and confirmed the row landed correctly in
  `admission_application` via `psql`. Also explicitly re-tested tenant
  isolation: after hitting the public endpoint (which sets
  `TenantContext` manually), a normal authenticated staff request still
  resolved the correct tenant afterward — no leakage between requests.
  2026-08-10.

- ~~Teacher app — Assessment, Classwork (LMS), Comms.~~ New `/assessment`,
  `/classwork`, `/comms` routes (`apps/teacher-app`), same section-scoped
  pattern as the existing Attendance tab — resolves the teacher's sections
  from their timetable, not a school-wide list. Assessment: create an
  assessment (auto-creates a single "Overall" component so mark entry
  doesn't need separate component-authoring UI), roster-based mark entry
  with absent toggling. Classwork: create assignments, view/grade
  submissions inline. Comms: post + auto-publish section-scoped
  announcements, plus a read-only feed of announcements visible to the
  teacher's sections. `lib/api.ts` gained the assessment/lms/comms clients
  (mirrors school-web's, scoped down). Bottom tab bar grew from 2 to 5 tabs.

  Verified live end-to-end against local Postgres and a real API instance
  (`priya.menon@oakridge-hyd.test`, real timetable/section data): created
  "Class Test 1," entered marks for a 3-student roster, confirmed save;
  created "Book Report" assignment, confirmed the existing seeded HW1
  submission showed its real grade/feedback; posted a section announcement
  and confirmed it appeared both here and in the parent app's Home feed
  later in the same session. 2026-08-11.

- ~~Parent app — Attendance history + leave, Grades (live marks + report
  cards), Homework, Messages.~~ New `/attendance`, `/report-cards`,
  `/homework`, `/messages` routes. Attendance: date-range history
  (`GET /v1/attendance/students/{id}`, already existed but unused) plus a
  leave-application form (`POST /v1/attendance/leave`, also already existed
  but unused). Grades: pulls live assessment marks directly (assessment →
  components → marks) since `ReportCardDto` itself carries no score
  payload — the generated-report-card list is shown separately as status
  metadata (draft/final) rather than duplicating grade display. Homework:
  assignment list scoped to the child's current section with submit.
  Messages: a "message this child's teacher" quick-start
  (`GET /v1/tenancy/sections/{id}/teachers` + `GET /v1/people/directory` to
  resolve a teacher's `userAccountId`) that creates or reuses a thread, then
  the existing thread/message read-send flow.

  Found the seeded `section_subject_teacher` table was empty (nothing had
  ever populated it, despite `timetable_slot` independently carrying
  teacher assignments) — the Messages teacher-picker had no data to work
  with until one row was added directly via SQL for live verification.
  This is a seed-data gap, not a code bug; a fresh `db:seed` run today would
  reproduce it — worth a real seed-script fix later (see Open items).

  Verified live end-to-end (`sunil.rao@test.dev`, guardian of Ananya Rao):
  pulled real attendance history, submitted a leave request, saw the "Class
  Test 1" mark entered from the teacher-app session above show up correctly
  in Grades, submitted the seeded "Book Report" homework, and completed a
  full parent→teacher message round-trip (started a thread with Priya
  Menon, sent a message). 2026-08-11.

- ~~Public/Admissions microsite — application status tracking.~~ New
  `/track` route: applicant looks up status by application number + the
  phone number they applied with. New backend:
  `AdmissionsRepository.findByApplicationNoAndPhone`,
  `PublicLookupRepository.track`,
  `GET /v1/public/schools/{chain}/{school}/admissions/track` — deliberately
  requires both fields to match (not just the application number) so a
  guessed/leaked number alone can't read another family's record; same
  "no JWT, trusted-job tenant context" pattern the rest of `publicsite`
  already uses. Home and the `/apply` confirmation screen both link into it.

  Verified live: submitted a fresh application ("Rahul Verma,"
  `WEB-E6DA0F34`), looked it up successfully with the right phone,
  confirmed a wrong phone number gets a generic "not found" (not "wrong
  phone," avoiding the enumeration tell). 2026-08-11.

- ~~Driver app — student check-in, trip history.~~ New `/history` tab plus
  a boarding/drop-off checklist that appears on Home while a trip is
  active — the app's first bottom tab bar (it was single-screen until now).
  Backend: check-ins write into `trip.manifest` (jsonb column that existed
  since the original transport migration but was never read or written)
  via new `POST /v1/transport/trips/{id}/checkin`, read back via new
  `GET /v1/transport/trips` (by `driverId`, or `schoolId` for the
  fleet-wide admin view added alongside — see below) and
  `GET /v1/transport/trips/{id}`. History lists past trips with duration
  and a check-in count.

  Verified live (`ramesh.kumar@oakridge-hyd.test`, real route/vehicle):
  started a pickup trip on Route 1, boarded the one student assigned to
  that route, ended the trip, confirmed it and the two pre-existing seeded
  trips all show correctly in History. 2026-08-11.

- ~~School Admin Web — Transport screen.~~ New `/transport` route,
  RBAC-gated behind a new `transport` screen key (migration `V014`, granted
  to Principal/Vice Principal/IT Admin — the same roles that already hold
  every other full-access screen). Vehicles, drivers (including linking a
  driver to a staff account — see gap closed below), routes + stops,
  student-route assignment, a fleet-wide recent-trips feed
  (`GET /v1/transport/trips?schoolId=`, new alongside the driver-app work
  above), and an ad-hoc geofence check.

  Closed a real API gap: `DriverDto`/`CreateDriverRequest` had no `staffId`
  even though the `driver.staff_id` column has existed since the original
  transport migration — there was no way to create a driver *and* grant
  them driver-app login in one step. Both now carry it.

  Verified live end-to-end (Principal login, `priya.menon@oakridge-hyd.test`
  — who is also seeded as a class teacher, convenient for testing): added a
  vehicle, added a driver linked to Priya's own staff account (confirmed
  "linked" badge), created Route 2/Kondapur, added a stop, assigned Ananya
  Rao to it, ran a geofence check. 2026-08-11.

- ~~Parent Mobile App — Phase 0 (shared `packages/api-client`, tablet CSS
  breakpoints).~~ First phase of the plan linked above, executed by a fresh
  subagent per the "clear context between phases" instruction and verified
  independently by the orchestrating session before commit (see Bugs
  section — none found this phase, but the process is worth noting since
  it caught real bugs in earlier work).

  New workspace package `packages/api-client` (`@schoolsoft/api-client`) —
  transport (`ApiError`, a `createApiClient({ baseUrl, getAccessToken })`
  factory where `getAccessToken` may be async so a future Capacitor
  Preferences-backed token store slots in without an API change), OTP wire
  shapes (`createAuthApi` — deliberately *not* a shared `verifyOtp`, since
  school-web resolves `screens`/`roleCodes` while parent/teacher/driver
  resolve `subjectId`, a real divergence not worth papering over), and
  chain-scoped domain wrappers grouped by API module (`createPeopleApi`,
  `createTenancyApi`, `createCommsApi`, `createAttendanceApi`,
  `createFeesApi`, `createAssessmentApi`, `createLmsApi`) plus their DTOs.
  `apps/parent-app/lib/api.ts` is now a thin adapter — keeps
  `SESSION_KEY`/`Session`/`getSession`/`setSession`/`clearSession` local
  (genuinely app-specific) and re-exports everything else from the shared
  package under the same names, so no page component's imports changed.
  The other five apps' `lib/api.ts` are untouched — same migration is a
  fast-follow whenever each app's next slice touches it, not forced now.

  Tablet breakpoint infrastructure added to `globals.css` as CSS custom
  properties remapped at `min-width: 768px` (`--shell-max`, `--shell-pad`,
  `--gutter`) rather than a single hard-coded `560px` cap, plus two layout
  primitives screens opt into: `.grid-2` (side-by-side panels) and
  `.pane-split`/`.pane-detail` (list + sticky detail pane, the tablet
  equivalent of drilling into a row). Proven on two screens — Home
  (`.grid-2`: child snapshot + announcements side by side) and Messages
  (`.pane-split`: teacher-picker/conversation-list column + open-thread
  detail column) — full screen-by-screen rollout is Phase 3.

  Verified live end-to-end against local Postgres and a real API instance:
  `tsc --noEmit` clean on both the new package and parent-app;
  `npm install` from repo root correctly symlinks
  `node_modules/@schoolsoft/api-client` to the new package; fresh OTP login
  as `sunil.rao@test.dev` through the refactored client hit real endpoints
  and rendered real data (unchanged from before the refactor — proving the
  extraction didn't silently change behavior); resized to tablet width
  (820×1180) and confirmed both `.grid-2` (Home) and `.pane-split`
  (Messages, including opening a real thread and seeing list + detail
  genuinely side by side, not stacked) actually use the extra width rather
  than just centering a wider column; resized back to phone width (390×844)
  and confirmed both screens correctly fall back to single-column with no
  regression. Console clean throughout. 2026-08-11.

- ~~Parent Mobile App — Phase 2 backend (device push tokens + FCM
  adapter).~~ Also executed by a fresh subagent, independently re-verified
  by the orchestrating session with its own curl/psql calls (not just a
  re-read of the agent's report) before commit — see verification below.

  New table `notification_device` (migration V015) keyed by
  `user_account_id` rather than `(recipient_type, recipient_id)` — every
  app session already holds its own `user_account.id` directly (e.g.
  `parent-app`'s `Session.userAccountId`), and the existing notification
  pipeline's `(recipient_type, recipient_id)` resolves back to it via
  `user_account(subject_type, subject_id)`, a join `NotificationDeviceRepository
  .tokensForRecipient` does rather than duplicating the recipient-typing
  scheme onto every device row. `UNIQUE(token)` with upsert-on-conflict —
  an FCM token is unique per app install, so re-registering (app reinstall,
  token rotation) moves it rather than duplicating. New
  `PushDeviceController`: `POST /v1/notifications/devices` (register/
  upsert — the owning account comes from the bearer token via
  `TenantContext.require().userAccountId()`, never the request body, so a
  session can only manage its own devices) and `DELETE
  /v1/notifications/devices/{id}` (unregister, e.g. on sign-out).

  `ChannelRouter.send()`'s push branch now actually resolves registered
  device tokens and calls a new `FcmSender` (`com.google.firebase:
  firebase-admin`, added to `pom.xml`) — but only if
  `schoolsoft.notifications.fcm.credentials-path` (new config property,
  env-overridable via `SCHOOLSOFT_FCM_CREDENTIALS_PATH`) is set to a real
  service-account JSON path. No Firebase project exists in this
  environment, so the property is unset by default, and `FcmSender
  .isEnabled()` gates `FirebaseApp` initialization entirely — with it
  unset, push falls back to exactly the pre-existing log-and-mark-sent stub
  (now per-device, logging device id/platform but never the raw token).
  Zero devices registered for a recipient is handled separately (marks the
  dispatch `failed` with a reason, doesn't throw) from "recipient doesn't
  resolve at all" (the pre-existing `NotificationService` guard, untouched).

  Verified live, independently, end-to-end against local Postgres and a
  real API instance — re-ran everything myself rather than trusting the
  subagent's report at face value: `mvn compile` clean; killed and
  restarted the API with **no** FCM credentials configured and confirmed
  via the boot log it starts cleanly with zero Firebase initialization
  attempted; confirmed migration V015 applied
  (`flyway_schema_history` → `015 notification device`) and the table
  shape matches; logged in as `sunil.rao@test.dev` via curl OTP, registered
  a real device token, confirmed the row in `psql`. Added a temporary
  debug endpoint (removed before commit, recompiled clean afterward) to
  directly exercise `NotificationService.notify(...)` with `channels:
  ["push"]` and confirmed all three real paths from the server log: (1)
  a resolvable recipient with registered devices → stubbed FCM send logged
  with device ids only, dispatch marked sent; (2) a recipient that doesn't
  resolve at all → the pre-existing guard skips cleanly, unaffected by
  this change; (3) a resolvable recipient (real staff member) with zero
  registered devices → the new empty-tokens branch skips cleanly with a
  `failed`/reason dispatch row, no exception. Re-confirmed the real
  register endpoint still works after removing the debug code. 2026-08-11.

- ~~Parent Mobile App — Phase 3 (tablet layouts, remaining four screens).~~
  Home and Messages got Phase 0's `.grid-2`/`.pane-split` treatment already;
  this phase extended it to Fees, Attendance, Grades, and Homework — same
  fresh-subagent-then-independently-reverify pattern as Phases 0 and 2.

  Fees: switched to `.pane-split` (invoice list + drilled-down detail side
  by side, matching Messages' thread-list/thread-detail pattern — the
  existing expand/collapse interaction mapped onto it directly, with an
  `.active` state on the selected invoice row). Attendance: `.grid-2`
  (history table left, leave-application form right — two already-distinct
  panels). Grades: `.grid-2` (live marks table + report-card list side by
  side). Homework: `.grid-2` as a card grid rather than a list/detail
  split — judged not to need drill-down since each assignment's full state
  (submitted/graded/feedback) already shows inline.

  Verified live end-to-end against local Postgres and a real API instance,
  independently re-checked by the orchestrating session (not just the
  subagent's report): `tsc --noEmit` clean; screenshotted all four screens
  at both tablet (820×1180) and phone (390×844) width as
  `sunil.rao@test.dev` against real seeded data — Fees showing the real
  two invoices with a real drill-down (lines + the ₹2,000 UPI payment) at
  tablet width and correctly stacking single-column on phone; Attendance
  showing real history + a working leave form side by side; Grades showing
  the real "Class Test 1" 76/100 mark (entered via teacher-app earlier this
  session) beside the report-card list; Homework showing both real
  submissions (HW1 graded 10/10, Book Report) in a two-card grid. Console
  clean on every screen. 2026-08-11.

- ~~Parent Mobile App — Phase 1 (Capacitor Android shell, scaffold only).~~
  **Explicitly unverified past the Node/npm layer** — this environment has
  no Android SDK (`ANDROID_HOME` unset, no `adb`), so nothing here has been
  gradle-built, signed, or run on an emulator/device. Flagged to the user
  before this phase started; the subagent was briefed to be honest about
  exactly this boundary rather than claim more than it could actually run,
  and the orchestrating session independently re-ran the one thing that
  *is* verifiable without an SDK (see below) rather than trusting the
  agent's report at face value.

  `apps/parent-app/next.config.mjs` gained `output: "export"` +
  `trailingSlash: true` (file:// routing needs real directories, not bare
  `.html` files an unsuffixed route would resolve to). `@capacitor/core`,
  `@capacitor/android`, `@capacitor/cli` added to `package.json`.
  `capacitor.config.ts`: app id `com.schoolsoft.parent`, `webDir: "out"`.
  `npx cap add android` generated a full standard Gradle project under
  `apps/parent-app/android/` (gradle wrapper, app module, the Cordova
  compatibility bridge Capacitor still ships) — this is the *unverified*
  part; it was never built. No custom app icon exists anywhere in this
  repo to source from, so app icon/splash were deliberately left as
  Capacitor's stock placeholder resources rather than fabricating a
  finished-looking one — a real icon is a design asset this phase
  shouldn't manufacture.

  What **is** genuinely verified, independently, by the orchestrating
  session: `next build` with `output: "export"` actually ran clean end to
  end — real `out/` directory with real static HTML for every route
  (`/`, `/attendance`, `/fees`, `/homework`, `/login`, `/messages`,
  `/report-cards`), confirming this 100%-client-rendered app (every page
  is `"use client"` + fetch-on-mount, per earlier phases) genuinely has no
  server-only Next.js feature blocking static export. Root `npm install`
  after the new Capacitor dependencies landed didn't break anything.
  `npm run dev` still boots and serves normally with the new config (`next
  dev` isn't affected by `output: "export"`).

  Explicitly unverified / needs a real Android SDK environment to take
  further: whether `android/` actually gradle-builds, whether it launches
  on an emulator/device, whether the webview correctly loads the exported
  static bundle and talks to the real API, code signing, and everything in
  Phase 4 (store submission) downstream of a working build. 2026-08-11.

- ~~Parent Mobile App — Phase 4 (store submission artifacts, docs only).~~
  Final phase. Cannot actually submit to Play Console (no account access in
  this environment) — deliverable is two grounded documents, not a
  submission. Every claim in both is cited against a real file in the
  codebase rather than generic SaaS-privacy-policy boilerplate; spot-
  checked independently by the orchestrating session against the actual
  `AndroidManifest.xml` (`INTERNET`-only permission, `allowBackup="true"`)
  and a `@DeleteMapping` sweep across the whole API (three total — push
  device, IAM role, timetable slot; none delete an account) — both
  confirmed accurate.

  `apps/parent-app/docs/privacy-policy.md`: correctly frames Schoolsoft's
  multi-tenant model (school/chain is the data controller, not a central
  vendor — schema-per-chain), a real per-feature data inventory (grounded
  in `lib/api.ts` / `packages/api-client/src/{domain,types}.ts`), and
  three things worth a second look even outside the mobile-app context:
  (1) the API base URL defaults to plain `http://localhost:8080` — any
  build shipped to parents needs that confirmed as `https://` before the
  "encrypted in transit" claim is true; (2) session storage (including the
  sign-in email/phone and refresh token) sits in WebView local storage
  rather than Android's encrypted credential store, compounded by the
  generated manifest's `allowBackup="true"`; (3) there is no account- or
  data-deletion endpoint anywhere in the API — Play requires a public
  deletion-request URL for apps with accounts, making this a real
  submission blocker, not just a policy nicety.

  `apps/parent-app/docs/play-data-safety.md`: a section-by-section
  transcription sheet matching Play Console's actual Data Safety form,
  applying Play's real "collected = leaves the device" definition rather
  than "the app can see it" — correctly distinguishes the mostly-read-only
  nature of this app (child/attendance/grades/fees data flows server→
  device to display, which isn't "collected") from the handful of real
  device→server writes (sign-in identifier, message bodies, leave reasons,
  homework answers). Also caught that Phase 2's push infrastructure is
  backend-only — the app has no push plugin and never calls the
  registration endpoint — so today's honest answer for "Device or other
  IDs" is "No," with an explicit note for what three answers flip together
  once a push plugin actually ships.

  Both documents are explicit, in a "Before you publish this" checklist,
  about what's a placeholder for the operating school to fill in (legal
  entity, retention periods, contact details) versus an engineering item
  that needs fixing or honest disclosure before submission (the two
  security caveats and the deletion-URL gap above). 2026-08-11.

## Bugs found and fixed along the way

Worth keeping a record of these since none were caught until something
actually exercised the code path — a reminder that "compiles" and "correct"
are different claims:

- `FeesRepository.createInvoice`: a manually raised invoice recorded no
  `academic_year_id`, alone among the paths that write one (`FeeGenerationService`
  and `FeeChargeRouter` both set it). `CarryForward` therefore fell back to
  "issued before the new year starts" for those rows, so a bill keyed in after
  the new year had begun read as belonging to the new year and the family's
  arrears silently vanished at rollover instead of following them. Latent since
  V020 and invisible until the wall clock crossed a target year's start date,
  which is what turned FEE-16 and YEC-06 red on 2026-09-01 with no code change
  behind it. Now stamps the school's current year, the same resolution
  `FeeChargeRouter` already used. Fixed 2026-09-09.

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
- `GeofenceStatusDto`'s real field names (`insideGeofence`, `distanceMeters`,
  `geofenceRadiusM`) didn't match what school-web's new Transport screen
  client assumed (`inside`, `distanceM`, `radiusM`) — silently rendered
  "NaN" instead of erroring, since both sides were plain TS objects never
  cross-checked against the actual JSON. Caught during live verification,
  not by the type checker. Fixed by correcting the client type to the real
  DTO shape.
- `parent-app/app-shell.tsx` did exact string matches (`pathname ===
  "/login"`, `TITLES[pathname]`, tab active-state) against `usePathname()`.
  Phase 1's `trailingSlash: true` (needed for the Capacitor static export)
  made `usePathname()` return `"/attendance/"` instead of `"/attendance"`
  in dev mode too, not just the export build — every one of those
  comparisons silently missed: the login page kept showing the app
  chrome (topbar/tabbar) while signed out, every other page's title fell
  back to "Schoolsoft" instead of its real name, and the active tab
  highlight never lit up. Found live while spot-checking Phase 3 the day
  after (a stale/expired session on the same page separately surfaced a
  different real gap — see Open items). Fixed with a `normalize()` that
  strips a trailing slash before any path comparison; verified live
  end-to-end after the fix — `/login/` correctly bare-chrome again,
  `/attendance/` shows "Attendance" as both the title and the highlighted
  tab. 2026-08-12.

## Open items

- ~~**Session expiry isn't handled.**~~ Fixed 2026-08-12 as part of Phase 0.
  Two halves. Server: `TenantResolverFilter` answered an expired token with
  **403** (it used `sendError`, which re-dispatches through `/error` and is
  then rejected as an anonymous request) — it now writes a 401 with a
  `token_expired` code, which is what a client can key a refresh on. Client:
  `packages/api-client`'s transport does the 401 → refresh → replay itself,
  single-flighted so a screen firing six requests spends one refresh token,
  and falls back to clearing the session and bouncing to `/login`. All six
  frontends now take their transport from that package instead of six copies
  of `apiFetch` (their DTO and endpoint wrappers still live per app).
  Certified by SEC-02.


- **Remaining frontend surfaces — mostly closed out.** All six frontends
  (`school-web`, `platform-web`, `teacher-app`, `driver-app`, `parent-app`,
  `public-site`) now have at least two slices each, including a new
  Transport screen for `school-web` — see Done above. What's still
  genuinely open:
  - **LMS quiz engine authoring** (question/option/answer UI) — skipped
    everywhere so far as its own distinct UI investment, not scoped to any
    one app.
  - **Report card *content*.** `ReportCardDto` carries no score payload,
    only metadata (locked/generated-at); parent-app's Grades page works
    around this by pulling live assessment marks instead. A real templated
    report-card renderer is still undesigned.
  - **`section_subject_teacher` seed gap** (noted in the parent-app entry
    above) — populate it properly in the seed script instead of by hand.
  - **Public/Admissions microsite — richer content.** School info beyond
    the hero (staff, facilities, calendar) is still just a stub.

- **Parent Mobile App.** Requirements + phased plan drafted:
  https://claude.ai/code/artifact/9a092613-18f6-4f04-8951-85cfd7cd8140.
  Locked: Capacitor wrap of the existing `parent-app` (not a React Native
  rewrite — the "switch off Next.js for native code-sharing" instinct
  doesn't actually buy anything, since React Native doesn't render web JSX
  either way regardless of framework; the real lever is a shared
  `packages/api-client`, not yet extracted), first-class tablet layouts
  (today's shell hard-caps at 560px), Android-first (iOS deferred, addable
  later without rearchitecting), online fee payment and the live bus map
  both deferred to post-launch (fee payment is a real gap — no
  checkout-initiation flow exists at all, see Bugs/gaps above; the bus map
  just needs driver-app's already-streaming GPS pings rendered back to a
  parent, nothing built yet). Still open: which push triggers ship at
  launch —
  decide at Phase 2 kickoff, not blocking the start. 5 phases, ~4–6 weeks
  calendar to store-live once Phase 0 (`packages/api-client` extraction +
  tablet CSS breakpoints) starts.

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

- ~~**A newly provisioned chain has nobody who can hand its first school
  over.**~~ Fixed 2026-09-22. Found walking the vendor onboarding path in the
  browser: the platform console provisioned a chain and opened a school in it,
  and stopped there. Appointing a school's first administrator is
  `POST /v1/tenancy/schools/{id}/first-admin`, reachable only by a **chain
  admin** — and nothing minted a `chain_admin` account. No endpoint, no form,
  no seed path; the only one that existed (`hq@smoketest.test`) was INSERTed
  by hand, and `cert_TEN_01` papered over it by issuing a token for an account
  that does not exist. So every real onboarding stalled at a draft school
  until somebody ran SQL against the customer's schema — the act the console
  exists to replace.

  Closed with the narrow half of the two options: `POST` and `GET
  /v1/platform-admin/chains/{id}/admins`, platform-admin only, creating the
  chain's one HQ account (`ChainHandoverService`) and refusing a second — the
  same "the door shuts behind them" rule `first-admin` already enforces per
  school. The alternative, letting a platform admin call `first-admin`
  directly, was not taken: it would put the vendor inside a school's own
  hiring. The handover writes its audit row into the **customer's** chain,
  naming the operator in `reason`, because that is the row the customer can be
  shown; `@Audited` could not do it, being a web interceptor that runs while
  the request still stands in the `platform` schema. Certified by `TEN-17`,
  which appoints, signs the appointee in through the ordinary OTP door, and
  has them open a school and appoint its keyholder with no operator involved.

- ~~**An audited reason is still asked for with `window.prompt` in six places.**~~
  Fixed 2026-09-26. Found 2026-09-22 walking a school's setup in the browser:
  a driven browser answered the modal itself, so a step was skipped with the
  reason `"not sure"` written into the audit log — the one column an auditor
  reads, filled with something nobody typed. All six (attendance allow and
  refuse, role revoke, assessment reopen, report-card unlock, the parent's
  re-check request) now ask inline with `ReasonField` from `@schoolsoft/ui`,
  in the row or cell the act was asked from; Enter confirms, Escape cancels,
  and confirm stays disabled until something is typed. The Setup screen's
  skip and the chain HQ panel's deactivate, which had hand-rolled copies of
  the same field, use it too. parent-app now depends on `@schoolsoft/ui`.

- ~~**A chain's HQ cannot add a second HQ account.**~~ Fixed 2026-09-26.
  Opened by the fix above: the vendor hands a chain over once, and a chain
  whose single HQ address stopped working needed Schoolsoft to run SQL.
  `GET`/`POST /v1/tenancy/chain/admins` and `POST …/{id}/deactivate`
  (audited, reason required), gated on `chain.admin.manage` — a new
  permission in the chain admin's baseline and in no migration. A school's
  custom role can still *name* it, so `ChainHqService` also insists the caller
  is a `chain_admin`; without that a school's employee could mint an account
  that sees every school in the chain. Deactivating the last active HQ account
  is refused under a row lock on every active one, so two admins removing each
  other at once cannot leave nobody. `/v1/tenancy/chain` joins
  `CHAIN_ADMIN_PREFIXES` as a chain-wide path. The screen is the foot of
  school-web `/chain`. Certified by `TEN-18`. Not built: reactivating a
  deactivated account — its address stays taken, so re-adding it is a 409.

---

## Gaps found during certification scenario design (2026-08-12)

Found while writing `docs/certification-test-scenarios.md` — a full-lifecycle
test catalogue (lead → admissions → daily ops → year closure → transfer /
graduation). Each gap below blocks one or more **P1** certification scenarios;
the `GAP-nn` ids and the scenarios that reference them live in that document
(§23). Ordered roughly by how much of the lifecycle they block.

### Lifecycle-blocking (nothing above them can be certified)

- **GAP-01 — No school calendar / holiday master.** ✅ **Closed 2026-08-12
  (Phase 1).** `working_day_pattern` + `school_calendar` (V016), grade- and
  campus-scoped, with `WorkingDayService` as the single authority; attendance
  refuses a non-working day, closures void the day's marks and notify
  guardians, the timetable day view and fee due dates read the same calendar.
  Original finding: There is no
  `holiday`/`calendar` table anywhere in the chain migrations (only
  `admission_event` and `announcement`). Consequence is wide: attendance
  percentages have no working-day denominator, the timetable renders periods
  on holidays, an unplanned closure can't void a day, term day-counts and fee
  due-date shifting have nothing to compute against. Needs: holiday +
  working-day-pattern master (5/6-day week, alternate Saturdays), scoped to
  school / grade / campus, and a `WorkingDays` service every attendance and
  fee computation calls. Blocks CAL-01..07, ATT-02/04/10, TT-01/09.

- **GAP-02 — No academic year rollover / bulk promotion.**
  ✅ **Closed 2026-08-13 (Phase 6).** `rollover_run` carries the state machine
  (draft → structure_cloned → allocated → committed, with rolled_back reachable
  until the new year is activated), `rollover_allocation` holds one editable row
  per child, and `rollover_artifact` records every row the run created so an
  undo deletes exactly that. Readiness lists the five things that block a
  closure; the structure clone copies sections and fee structures into a
  planning year (teacher assignments deliberately not); allocation reads the
  report card's promotion decision, walks the grade ladder, respects capacity
  and keeps siblings together; commit works in batches and is resumable, carries
  arrears into an opening-balance invoice plus transport and elective
  continuity, and closes the source year only once every child has somewhere to
  go. Original finding: `enrolment.status` already allows `promoted` and
  `graduated`, but nothing ever sets them and there is no rollover code (only a
  comment in `enrolment/package-info.java`). Missing: next-AY structure cloning
  (grades/sections/curriculum bindings), a year-end readiness check
  (unpublished assessments, unlocked report cards, unmarked days, dues),
  bulk promote / detain / graduate, section reshuffle at promotion,
  carry-forward of fee arrears + library dues + transport + guardian links,
  and an idempotent, restartable, reversible-before-activation run. This is
  the single largest missing capability in the product. Blocks all of
  YEC-01..11, GRAD-01/06, FEE-16, ENR-08.

- **GAP-03 — No student exit workflow.** ✅ **Closed 2026-09-09 (Phase 9).**
  Three pieces:

  `withdrawal` + `clearance_item` (V031) are the leavers' desk: a reason, a
  last working date, and a checklist that gates the exit. Each area answers for
  itself through `enrolment/api/ClearanceProbe`, implemented in `fees`,
  `library` and `transport` — so enrolment depends on none of them and a fifth
  area is a new class rather than an edit to the service. `assets` has no
  system of record behind it and so opens `pending` and waits for a person,
  which is the honest answer rather than a clear nobody checked. Completion is
  refused while anything blocks; `withdrawal.override` is a *separate*
  permission from `withdrawal.manage`, held by the heads and the accountant and
  not by the registrar, because forgiving arrears on the way out is a decision
  about money.

  `certificate` (a new module) issues the TC, the school-leaving certificate,
  the transcript and the bonafide certificate. The payload is frozen and hashed
  at issue and the row is append-only by trigger, so the serial a family holds
  still resolves to what it said; a correction is a revocation and a reissue.
  The column is `json` rather than `jsonb` deliberately — jsonb normalises key
  order, and the hash is over bytes. One live certificate of a kind per
  enrolment is what prevents the duplicate TC.

  `enrolment/api/EnrolmentActivity` is the single active-on-date predicate the
  gap asked for. `status` is now the *reason* an enrolment closed and the dates
  are the authority on whether it is open, which is what lets a withdrawal
  filed on the 1st for a last working day of the 30th keep the child on the
  register until the 30th and take them off on the 1st — rosters, the route
  roster, the announcement audience and the directory's "which class", all from
  the date, with nothing scheduled to run. `EnrolmentRepository.transfer` was
  closing the outgoing enrolment on the same day the incoming one opened, so a
  child stood on two registers for a day; V031 corrects the rows and the code
  stops making them.

  Certified by XFER-01/02/03/07/08, COMM-08, TRN-09, LIB-05, GRAD-02, plus
  three cases in `RbacEnforcementTest`. XFER-04 stays disabled on GAP-04 —
  post-exit parent access needs an alumni identity, which is that gap's.

- **GAP-05 — No student-level subject election.** ✅ **Closed 2026-08-12
  (Phase 2).** `elective_group` + `student_subject` (V019) with
  `SubjectSetResolver` as the single rule (section's compulsory subjects + the
  student's elections); marks entry, the student timetable, report cards and
  board exports all read it. Original finding:
  `section_subject_teacher` binds subjects to a *section* only, so IGCSE /
  A-level option blocks and Class 11 streams cannot be represented. Marks
  entry, timetable, report cards, and board exports all currently assume a
  section-wide subject set. For a Cambridge school this is a launch blocker,
  not a nice-to-have. Blocks ACAD-09, ASMT-13, INT-02.

- **GAP-06 — Exam operations missing.** ✅ **Closed 2026-08-13 (Phase 5).**
  `exam_schedule` / `exam_session` / `exam_hall_ticket`: papers are set per
  grade, clash detection runs over each student's own subject set (the clash a
  section timetable cannot see), publication refuses while one stands, and hall
  tickets list the papers that candidate actually sits. `mark.status` replaces
  `is_absent` with `entered | pending | absent | medical_leave | exempt`, so a
  blank, a zero and an absence are three different rows; `mark_revision` and
  `mark_reevaluation` supersede a mark without discarding it; a sealed
  assessment refuses writes, and reopening one needs an authorised role, a
  reason and an audit row. A published exam schedule also suppresses the class
  timetable for that day (TT-09). Original finding:
  `assessment.scheduled_on` is the
  only scheduling field. No exam timetable entity, no per-student paper-clash
  detection, no room or invigilator allocation, no hall tickets. Also missing
  at the marks layer: `absent` / medical-leave semantics (today a blank and a
  zero are indistinguishable in effect), re-evaluation and moderation, and an
  authorised, reason-captured, audited *unlock* of locked marks. Blocks
  ASMT-05/07/08/09, TT-09.

- **GAP-09 — Fee lifecycle holes.** ✅ **Closed 2026-08-12 (Phase 4).**
  `TenantJobRunner` (per-chain fan-out, per-school advisory lock, `job_run`
  records), `fee_schedule_run` idempotent bulk invoicing, `fee_adjustment`
  (credit note / refund / waiver / late fee / charge / reversal) posting to the
  ledger, `family` + sibling concessions + combined family invoices, dunning
  policy with overdue/reminder/late-fee jobs, and transport fees derived from
  the route assignment. Online checkout (FEE-05) stays open on gateway
  credentials, and arrears carry-forward (FEE-16) waits for rollover.
  Original finding: The only scheduled job in the codebase is
  `OutboxPublisher`, which is the tell: there is no invoice-generation run
  from `fee_structure`, no scheduled `open → overdue` transition, no
  reminder/dunning cadence, and no late-fee application. Also absent: refund
  / credit note (status `refunded` exists, nothing sets it), cheque-bounce
  reversal, sibling / family concession and combined family invoice,
  transport-fee linkage to route assignment, and online checkout initiation
  (already noted separately in Open items). Blocks FEE-02/05/08/09/10/11/12,
  ADM-11.

### Correctness and control gaps

- **GAP-08 — Attendance corrections are unaudited silent overwrites.**
  ✅ **Closed 2026-08-12 (Phase 3).** `attendance_policy` gives each school a
  marking window; inside it a mark is still a correction, outside it the upsert
  refuses and `attendance_amendment` carries the request — prior value, reason,
  requester, and an approver who is neither the requester nor a colleague
  without the role. Approved leave now materialises across the covered working
  days (student and staff), and revoking the approval unwinds exactly what it
  created and restores what it changed. Original finding: The
  `AttendanceRepository` upsert on the V010 unique index replaces the prior
  value with no history, no approval step, and no reason. Approved leave also
  does not auto-materialise as `leave` attendance — a teacher must remember
  to mark it. Blocks ATT-05/06, STF-02.

- **GAP-07 — No teacher substitution / cover.** ✅ **Closed 2026-08-12
  (Phase 3).** `timetable_cover` is per slot per date: approved staff leave
  raises the day's cover needs with the teachers free in that period,
  assignment notifies the substitute and the section's primary teacher, the
  substitute's day view gains the period and the absent teacher's loses it, and
  the cover is what authorises the substitute to mark that period's register.
  Original finding: `staff_attendance` records
  the absence and `timetable_slot` names the teacher, but the two are
  unrelated. No cover assignment, no substitute notification to the section,
  and no permission path letting the substitute mark that period's
  attendance. This is a daily-operations blocker for any school. Blocks
  TT-08, STF-03.

- **GAP-14 — No closed-year lock.** ✅ **Closed 2026-08-12 (Phase 1).**
  `academic_year.status` (planning | active | closed) with `AcademicYearGuard`
  on the attendance, marks and fee write paths, and an audited
  reopen-with-reason. Original finding: `academic_year` has only `is_current`;
  prior-year attendance, marks, and invoices remain editable indefinitely,
  with no reopen-with-approval path. Blocks YEC-08, GRAD-05.

- **GAP-10 — `section.capacity` is decorative.** ✅ **Closed 2026-08-12
  (Phase 2).** `SectionCapacity` refuses a seat the section does not have on
  both the enrolment and admission-conversion paths, and records
  `enrolment.over_capacity_reason` when a school admits anyway. Original
  finding: Stored and selected, never checked. `capacity` appears in Java only for `vehicle` and one `SELECT` in
  `SchoolRepository`. Enrolment and admission-offer paths both need to
  enforce it (with an explicit over-capacity override + reason). Blocks
  ACAD-06, YEC-05.

- **GAP-25 — No AY / term date validation.** ✅ **Closed 2026-08-12
  (Phase 1).** Terms are checked against their year and each other (API
  pre-check for the message, trigger + `EXCLUDE USING gist` for the
  guarantee); academic years cannot overlap. Original finding: Terms are not
  constrained to sit
  inside their academic year, and two academic years may overlap. Only
  `ends_on > starts_on` is enforced. Blocks ACAD-02/03.

- **GAP-26 — No admission / roll number policy.** ✅ **Closed 2026-08-12
  (Phase 2).** `number_series` + `NumberSeries` issue admission and roll
  numbers under a row lock, a partial unique index makes a section's roll
  numbers unique, and `POST /v1/enrolment/sections/{id}/renumber` re-sequences
  after a transfer. Original finding: `enrolment.roll_no` is free
  text with no uniqueness constraint and no generator; admission numbers have
  no scheme at all. Renumbering after a section transfer is undefined.
  Blocks ENR-02.

- **GAP-12 — Timetable: room clash unchecked, no bell-schedule master.**
  ✅ **Closed 2026-08-12 (Phase 2).** `bell_schedule` + `bell_period` per grade
  band with slots referencing a period (times follow the bell, breaks refuse
  lessons), a room-clash check alongside the teacher one, and publish-time
  warnings for teachers over `staff.max_weekly_periods`. Original finding:
  `TimetableRepository`'s clash query is teacher-only, so two sections can be
  put in the same room in the same period. Each slot also carries its own
  free-text `starts_at`/`ends_at`, so there is no period/bell master to
  validate against or to re-time the school day from one place. No
  teacher-max-load rule either. Blocks TT-01/03/04.

- **GAP-27 — Audit not wired to the high-risk mutations.** ✅ **Closed
  2026-08-12 (Phase 3).** `@Audited` on the endpoint plus one interceptor:
  enrolment status change, assessment reopen, report-card unlock, fee
  adjustment (waiver included), concession grant, role grant/revoke and both
  attendance decisions now write an entry with actor, the row before and after,
  and a reason the interceptor refuses to proceed without. `audit_log` gained
  `reason` and `request_payload` columns. STF-04 stays open on the staff-exit
  path itself, which is Phase 7's. Original finding: Extends the
  existing audit-retrofit item with a certification-scoped priority: before
  release, `AuditService.record` must at minimum cover enrolment status
  changes, mark unlock, fee waiver / concession grants, and role grants —
  actor, before/after, reason. Blocks SEC-08, ASMT-07, FEE-10, STF-04.

- **GAP-24 — Campus is decorative.** ✅ **Closed 2026-08-12 (Phase 1).**
  `campus_id` on `section`, `staff`, `timetable_slot` and `device`
  (V017 backfill → V018 NOT NULL + same-school triggers), campus-scoped role
  grants, and campus filtering on the section, staff and device lists.
  Original finding: The `campus` table exists, but
  `section`, `staff`, and `timetable_slot` carry no `campus_id`. Campus-scoped
  timetables, attendance, holidays, and campus-admin roles are therefore
  impossible in a multi-campus school. Blocks TEN-07, CAL-06.

- **GAP-15 — No intra-chain school transfer.**
  `POST /enrolments/{id}/transfer` takes `TransferRequest(newSectionId,
  rollNo)` — a section move only. A student moving between two schools of the
  same chain has no path that preserves the profile and history while
  settling the source school's ledger. Blocks XFER-05.

- **The funnel a school runs is configuration.** ✅ **Closed 2026-09-21.**
  `admission_transition` allowed both routes out of `review` and nothing said
  which one *this* school uses, so the office could schedule an entrance test
  at a school that holds none and could skip it at a school that requires one.
  `admission_policy` now answers per school, and each transition row says which
  funnel it belongs to (`requires_entrance_test`: NULL means either).
  `PUT /v1/admissions/policy` behind its own `admission.policy.manage`, with a
  control on the admissions screen. Existing schools were migrated with the
  test on, so nobody's funnel moved on the day it landed.

- **GAP-11 — Admissions funnel automation missing.** ⚠️ **Prerequisite closed
  2026-09-21:** `offer_expires_on` was read everywhere and written nowhere —
  the column, the DTO and the public tracking page's "Offer valid until …" all
  existed while the value stayed NULL for every application ever created, so
  there was nothing for an expiry job to act on. A move to `offered` now sets
  it from `admission_policy.offer_validity_days` (default 14), or from a date
  the caller names, which is how an extension is granted. Still open, and now
  actually buildable: nothing yet *acts* on the date — offers do not expire,
  seats never return to the pool, the waitlist is never promoted on a decline,
  and duplicate leads from the same guardian phone are not deduped. Blocks
  ADM-07/08/09.

- **GAP-30 — Transport operational gaps.** ⚠️ **Fee half closed 2026-08-12
  (Phase 4):** routes carry a `monthly_fee`, assignments are effective-dated
  with an explicit change/end path, the roster answers per date, and invoice
  generation bills transport only for the cycles a student actually rides
  (FEE-12, TRN-06). Still open: capacity against the vehicle and the
  boarded-vs-attendance mismatch alert (Phase 8). Original finding: Route
  capacity is never checked
  against vehicle capacity; a mid-year stop/route change has no
  effective-dated path (and so no fee adjustment); there is no
  boarded-but-absent / present-but-never-boarded mismatch alert. Blocks
  TRN-02/05/06.

### Missing record types (each blocks a real school workflow)

- **GAP-16 — No student document store.** `admission_application.documents`
  was loose JSONB that no code ever read or wrote; V034 dropped the column
  rather than leave a second, empty source of truth beside the store that will
  replace it. An *enrolled* student still has no document set at all — no
  incoming TC, birth certificate, or immunisation record — and neither does an
  applicant. Blocks ADM-12, ENR-07, XFER-06.

- **GAP-17 — No health / emergency data.** Allergies, medical conditions,
  blood group, and prioritised emergency contacts aren't modelled, so they
  cannot reach the class teacher or the driver's trip view — the two people
  who need them in an incident. Blocks ENR-06, OPS-05.

- **GAP-18 — No safety operations.** No gate pass / early-dismissal approval
  chain, no authorised-pickup list, no visitor log, no evacuation roster from
  live attendance. Bus check-in exists; the walk-home and parent-pickup
  dismissal paths do not. Blocks OPS-01/02/03/07, ENR-05.

- **GAP-19 — No discipline or counselling records.** Conduct has no source of
  truth, which also leaves the conduct line on a Transfer Certificate
  unbacked. Counselling notes additionally need restricted access, not
  general staff visibility. Blocks OPS-04/06.

- **GAP-04 — No alumni identity.** What happens to a login after Grade 12 is
  undefined: no scope downgrade to transcript/receipt retrieval, no alumni
  record, no document-request path years later. Blocks GRAD-04/05, XFER-04.

- **GAP-20 — No PTM scheduling.** Message threads exist; publishing meeting
  slots and letting a parent book one (with double-booking prevention) does
  not. Blocks COMM-09.

- **GAP-13 — Report card has no content model.** ✅ **Closed 2026-08-13
  (Phase 5).** Subject rows (`report_card_subject`, each with a result status so
  an absence prints AB rather than a zero), co-scholastic ratings, teacher and
  principal remarks, an attendance summary taken from the attendance module's
  own working-day denominator, the terms a card can honestly speak for, and
  `promotion_decision` ∈ `promote | detain | graduate` — columns and rows, not
  JSON, because "which children have no promotion decision" is Phase 6's first
  question. Generation is idempotent per student/term/template: a locked card
  refuses regeneration and names the unlock endpoint. The templated PDF renderer
  remains on the frontend workstream. Original finding: (Sharpens the existing
  Open item.) Beyond the missing score payload: no attendance summary, no
  co-scholastic ratings, no teacher remarks, no promotion decision — and the
  promotion decision is what YEC-03 reads to run bulk promotion, so this and
  GAP-02 are coupled. Blocks ASMT-10/14.

- **GAP-29 — No rank / percentile / grade-boundary computation.** ✅ **Closed
  2026-08-13 (Phase 5).** `grade_scale` + `grade_band` hold each school's
  boundaries (seeded from its board, editable), and `CurriculumStrategy` holds
  the four things boards actually disagree about: the boundaries, the pass mark,
  what the cohort is ranked on, and what results imply about promotion. CBSE
  prints an aggregate percentage and ranks on it; Cambridge prints none and
  ranks on mean grade point. Ranking is dense with ties sharing a place and is
  recomputed for the whole cohort, so a rerun reproduces it exactly. Original
  finding: Needed on report cards and for board preparation. Blocks ASMT-11.

- **GAP-21 — No notification preferences or delivery management.** No
  per-guardian channel choice, quiet hours, category mute, or opt-out; no
  retry policy or failure surface over `notification_dispatch`. Blocks
  COMM-04/05.

- **GAP-22 — Library has no fines or holds.** ⚠️ **Fee half closed 2026-08-12
  (Phase 4):** `library_charge_policy` drives an overdue fine and a
  lost/damaged replacement charge, both posted to the student's fee account
  through `FeeCharges` and visible in the outstanding-dues report. **Clearance
  closed 2026-09-09 (Phase 9):** `LibraryClearanceProbe` blocks a leaver with a
  copy still out, and the fine it accrues on return reaches the fees line of
  the same checklist rather than being counted twice (LIB-05). Still open:
  per-grade issue limits (LIB-02) and holds/reservations. Original finding: No overdue fine calculation, no
  posting of fines / lost-book charges to the fee ledger, no reservations, no
  per-grade issue limits. Blocks LIB-02/03/04.

- **GAP-23 — No bulk import, no DPDP data lifecycle. Students closed
  2026-09-21:** V038 adds preview-then-commit CSV import for students, their
  families and their enrolments (`/v1/people/imports/students/preview`, then
  `/{id}/commit`), per-row errors, guardians matched on phone or email so
  siblings share a parent, and the whole batch refused until the file is
  clean — the documented choice ENR-09 asks for. ENR-09 now runs.
  **Still open:** no import for staff (there is no staff-write surface at all
  yet — no `staff.manage`, no create endpoint — so it is its own piece of
  work) and none for marks. `consent_record` exists but there is no export,
  erasure, or retention job behind it. Still blocks SEC-09.

_(No GAP-28: session expiry / token refresh is already an Open item above and
is referenced by scenario SEC-02 rather than duplicated here.)_

---

## Gaps found by running the certification suite (2026-08-12)

The Phase 0 harness (`apps/api/src/test/java/com/schoolsoft/certification/`)
turned the catalogue into 205 executable scenarios against a seeded two-school
chain. 43 pass today; the remaining 162 are `@Disabled` naming what blocks
them. Most name one of GAP-01..30 above. The gaps below are what only showed
up once the scenarios were actually run — they are *not* in that list, and
several are security-relevant.

`docs/certification-status.md` (generated) is the full per-scenario map.

### Security-relevant (P1 scenarios, no gap id previously)

- **GAP-31 — Authorization stops at the school boundary.** ✅ **Closed
  2026-09-09.** Three new scopes alongside `CampusScope` and `SelfScope`:

  - `iam/api/TeacherScope` confines a teacher to the sections they teach —
    `section_subject_teacher`, a currently-effective `timetable_slot`, or a
    cover handed to them today. Applied to the section-keyed academic reads:
    the register (`GET /v1/attendance`), a student's attendance history and
    summary, the roster (`GET /v1/enrolment/sections/{id}`), the section's
    assessments, an assessment and its components, and a component's marks
    grid. Plain student lookup is deliberately *not* narrowed — a staffroom
    finds a guardian's number for a child from another class.

    Confinement is derived from grants rather than role names, so a custom
    role lands on the right side without a deploy: a caller holding a teaching
    permission (`mark.enter` or `attendance.mark`) and **not** `teacher.assign`
    is confined. That shape can only ever narrow a teacher — a registrar,
    accountant or librarian holds no teaching permission and reads exactly what
    they read before, and a head of school who also takes a class keeps the
    whole school. A teacher with no duties recorded gets the empty set, not the
    school.

  - `iam/api/DirectoryScope` closes what turned out to be the real guardian
    leak. The original finding named `/v1/people/students`, which V026 had
    already shut (it gates on `student.view`, and a guardian holds only
    `student.view.own`); `SelfScope` covered the rest. What nothing covered was
    `/v1/people/directory` — `directory.view` is in `GUARDIAN_BASELINE` and
    `listDirectory` narrowed by `school_id` alone, so one parent's token
    returned every other parent's and student's name, email and phone. A family
    now sees staff only, and of those the teachers of their children's current
    sections plus the office (staff holding `guardian.view`). Staff callers are
    untouched.

  Certified by SEC-10 and STF-05, both previously `@Disabled`. No migration:
  the vocabulary and the grants are unchanged, which is why `Perm` needed no
  new constant and `no_orphan_permissions` still passes.

  - `iam/api/RouteScope` closes the last of them: the `driver` over-grant. V026
    granted `driver` school-wide `student.view` and said so in a comment,
    because the route roster returned bare student ids and driver-app read
    every rider back out of `/v1/people/students/{id}` — a school's whole
    student directory, on a bus. The roster now returns `RouteRiderDto` (name,
    admission number, section — what the check-in screen shows and nothing
    else), the grant is revoked in `V029`, and the roster is narrowed to the
    routes the caller is rostered to drive today. Confinement is derived from
    grants like `TeacherScope`'s: `transport.drive` without `transport.manage`.
    Read from `route_assignment` and never from `trip`, because trip start
    names its own route — `POST /v1/transport/trips/start` is scoped too, or a
    driver could mint a route by starting a trip on it and then reading its
    roster. `transport.view` stays: picking a route and a vehicle at trip start
    is not student data.

    Certified by three cases in `RbacEnforcementTest`. The certification
    fixture now seeds the driver as a staff account with a `driver.staff_id`
    link, which is what a real driver login looks like.

- **GAP-32 — Screen access is advisory.** `/v1/iam/me/screens` reports what the
  UI should show, but no endpoint checks it: a hand-crafted call to any module
  succeeds for any authenticated staff account regardless of role grants
  (SEC-03).

- **GAP-33 — OTP has no rate limit and a permanent dev bypass.** `OtpStore`
  accepts the literal code `000000` unconditionally — its own doc comment says
  the bypass is gated on a property, and it is not — and nothing throttles
  repeated verify attempts (SEC-01).

- **GAP-34 — Platform-admin actions are unaudited.** `audit_log` lives in the
  chain schema and `ChainAdminController` writes nothing, so chain provisioning
  and cross-chain reads leave no trail (SEC-06).

### Correctness

- **The register was still asked as a status in eighteen more reads.** ✅
  **Closed 2026-09-09.** `EnrolmentActivity` exists to be the only copy of "is
  this child at the school?", but the conversion had stopped at the reads that
  prompted it. Eighteen others still filtered on `enrolment.status = 'active'`,
  which flips the day a withdrawal is *filed* rather than the day it takes
  effect — so through the notice period a child was on the register or not
  depending on who asked. What each did to a family working out that notice:

  - `FeeGenerationService` stopped billing them, so the term they actually
    attended went uninvoiced; the sibling-concession ranking dropped them out
    of the household, promoting a younger sibling to eldest and changing
    everybody else's discount.
  - `FeeReportRepository` moved their outstanding dues to "(unassigned)" — out
    of the grade and section report the office chases from.
  - `DashboardRepository` shrank the denominator of today's attendance
    percentage while the child was still being marked present, so a school
    could read over 100% attendance.
  - `TeacherScope` refused their own class teacher their record — while still
    expecting that teacher to mark them present.
  - `ReportCardService` left them out of the section's cards, and
    `ExamScheduleRepository` left them without a hall ticket for a paper they
    were sitting.
  - `SectionCapacity` freed their seat to be offered to somebody else while
    they were still sitting in it.
  - `RollNumbers` offered their roll number to a new admission, and
    `EnrolmentRepository.renumber` renumbered a different set than it parked,
    which is a unique-index collision rather than a cosmetic problem.
  - `EnrolmentRepository.findActiveByStudent` — the guard against a second
    enrolment — opened, so a child could be enrolled elsewhere while still on
    the old section's register.
  - `StudentSubjectRepository` dropped their subjects, `BoardExportRepository`
    left them out of the UDISE/CIE return, `ChainAdminController` off the HQ
    headcount.

  Three `ORDER BY (status = 'active') DESC` tie-breaks became
  `(ends_on IS NULL) DESC`, matching `EnrolmentActivity.activeEnrolmentOn`:
  `status` is the reason an enrolment closed, so it cannot order them.

  **Deliberately still a status**, and now the documented allowlist on the new
  rule: `WithdrawalRepository` and `RolloverService` *write* it, and rollover
  (`RolloverReadiness`, `AllocationPlanner`) asks "who is continuing into next
  year", which is not "who is on the register today" — a child leaving on the
  last day of the year is on the register until then and is not promoted, and
  only the status separates those. `AllocationPlanner` also counts seats taken
  in *next* year's sections, whose enrolments have not started and which a date
  predicate would count as zero.

  `ArchitectureTest.enrolment_activity_is_asked_as_a_date` is the net: a source
  line holding the literal within four lines of the word "enrolment", outside a
  comment, fails the build unless its class is on that allowlist with a reason.
  Confirmed to fail on a reintroduced offender. `cert_STF_06` had asked the
  dashboard's question with the old predicate and disagreed by one — the single
  withdrawn-but-still-enrolled child in the fixture — and now asserts the gap in
  both directions.

- **The family directory answered two date questions with a status and a
  missing window.** ✅ **Closed 2026-09-09.** `DirectoryScope` decides which
  staff a parent may contact — the teachers of their children's sections, plus
  the office. Both halves of "their children's sections" were wrong. The
  enrolment side read `status = 'active'`, which flips the day a withdrawal is
  *filed*: a family served notice on the 1st for a last working day of the 30th
  lost the school's entire contact list for the month they most needed it, and
  `EnrolmentActivity` exists precisely so nothing spells the question that way.
  The timetable side read `timetable_slot` with no window at all, so next
  term's teacher was already contactable and last term's still was — while
  `TeacherScope` uses today's window to decide whose sections that teacher may
  read. The two disagreeing means a parent handed the address of somebody who
  cannot open their child's record. Both are now the same date, today.
  `cert_SEC_10` covers each half, and each was confirmed to fail on its own
  before the fix.

  **The rest of the sweep followed the same day** — every other read that spelled
  the enrolment question as a status now asks the date. See the entry above.

- **A timetable revision rewrote history instead of superseding it.** ✅
  **Closed 2026-09-09.** `timetable_slot` has carried
  `effective_from`/`effective_to` since V004, and the date-keyed reads
  (`/sections/{id}/day`, `/teachers/{id}/day`, `TeachingDuties.teachesOn`,
  `TeacherScope`) already applied the window — but the *week* reads,
  `TimetableRepository.forSection` and `forTeacher`, selected every row for the
  section or teacher regardless of it. So a school that moved a period mid-year
  had no way to file the change: creating the replacement left both slots in
  the week, and the only way to remove the old one was `DELETE`, which takes
  the period out of the weeks it was actually taught. `publishWarnings` counted
  the same way, so a teacher's load included periods they had stopped teaching.

  The window predicate is now one constant applied by every read, the week
  reads take an optional `onDate` (default today — "the timetable" with no date
  is not a thing a school has), and `POST /v1/timetable/slots/{id}/retire`
  closes a slot from a named last day: one conditional UPDATE, idempotent on a
  retry, refusing to *lengthen* a window because that puts a period back into a
  week already taught. Retiring beside deleting is the distinction — DELETE is
  for a slot authored by mistake, retire is for one the school has taught.
  `V032` adds the `effective_to >= effective_from` check the table never had
  and the two window indexes. Certified by `cert_TT_05`, previously disabled.
  school-web's timetable grid gained an "in force on" date and a Retire action.

- **GAP-35 — Notification producers are unwired.** ✅ **Closed 2026-09-09
  (Phase 8).** Four write paths now send: a public enquiry is acknowledged to
  the applicant (ADM-01) and told the outcome (ADM-14), an absence reaches the
  family the moment the register is saved (ATT-03), a published announcement
  fans out to every guardian of a currently enrolled child and reports what
  went out (COMM-06), and a check-in tells the parent the child is on the bus
  or off it (TRN-03).

  Three things had to exist first. `notification_dispatch` gained a
  `dedupe_key`, unique per (key, recipient, channel) and written with
  `ON CONFLICT DO NOTHING`, so the message is tied to the *event* rather than
  the write: a register re-saved, an announcement re-published or a check-in
  tapped twice sends nothing the second time, and two concurrent saves race on
  the index rather than on a read. `recipient_type` gained `applicant`,
  because a family with no guardian row and no login is reachable only through
  the details they typed on the form. And `announcement` gained `priority`, so
  "emergency broadcast" is something the row can say.

  Each producer is a service in its own module calling `notification.api` —
  `AdmissionsService`, `AttendanceMarking`, `AnnouncementPublisher`,
  `TripService` — with recipient resolution kept in one place behind
  `NotificationService.notifyGuardiansOfStudent(s)`: the
  `guardian_student` join, the `is_communications_recipient` flag and the
  child's name are one rule, not four.

  Two smaller corrections came with it. A push to a recipient with no
  registered device is now a channel they do not have rather than a dispatch
  that failed — recorded as a permanent failure it made every broadcast's
  delivery stats unreadable. And `announcement.publish` is now a conditional
  UPDATE naming the state it moves out of, so a re-publish is a retry that
  stamps nothing and sends nothing rather than a second siren with a fresh
  timestamp.

  Still open, and deliberately: an amendment approved outside the marking
  window changes a register without notifying anyone — the alert fires on the
  marking path only. GAP-21 still owns quiet hours, category mute, the
  emergency override that would read the new `priority` column, and retrying a
  failed dispatch. `DomainEvents` still has no callers; producers call the
  notification module directly, which is what the one existing caller
  (`DunningService`) already did.

- **GAP-36 — Dates are derived in the JVM's zone, not the school's.**
  `school.timezone` is stored and never read; `DeviceController` and
  `EnrolmentRepository.transfer` call `LocalDate.now()`. On a UTC server a
  23:55 IST event lands on the next day (NFR-08).

- **GAP-37 — Assessment lifecycle and report-card locks are not enforced.**
  ✅ **Closed 2026-08-13 (Phase 5).** A sealed assessment refuses marks and
  names the way back in; report cards are one per student per term per template,
  regenerated in place with a version, and a locked one refuses regeneration
  until it is unlocked with a role, a reason and an audit row; component weights
  and marks are checked against the assessment total before marking opens.
  Original finding: `enterMark` upserts regardless of the assessment being
  `locked` (ASMT-06), `generateReportCard` always inserts, so a locked card can
  be superseded silently (ASMT-12), and component weights are never validated
  against the assessment total (ASMT-03).

- **GAP-38 — Invoice totals are computed by read-modify-write.** ✅ **Closed
  2026-08-12 (Phase 4).** `recordPayment` is transactional, takes the invoice
  row with `FOR UPDATE`, applies the balance atomically and holds any
  overpayment as `advance_amount` against a liability account. Original
  finding:
  `recordPayment` reads `invoice.paid` and writes back `paid + amount` with no
  guard, so simultaneous payments lose an update and can over-credit; there is
  no advance-payment path (FEE-17).

- **GAP-39 — Attendance accepts impossible dates.** ⚠️ **Partly closed
  2026-08-12 (Phase 1):** the write now refuses a future date (in the school's
  own timezone) and a date outside the student's enrolment window (ATT-12).
  Still open: a replayed device backlog overwrites a manual correction because
  no source precedence rule exists (ATT-08), and offline teacher marking has no
  conflict surface at all (ATT-09).

- **GAP-40 — Timetable reads ignore effective dates.** Slots carry
  `effective_from`/`effective_to`; `forSection`/`forTeacher` select every row,
  so a mid-year revision rewrites history instead of superseding it from a date
  (TT-05). There is also no day view and no after-hours suppression for the
  parent/student view (TT-07).

- **GAP-41 — No admissions state machine on the server.** ⚠️ **Machine half
  closed 2026-09-20:** `V033` seeds the legal moves from design doc §13 into
  `admission_transition`, and `AdmissionsService.transition` refuses a move the
  funnel does not have, checks the permission that *move* requires on top of the
  endpoint's `admission.decide`, and writes `... WHERE id = ? AND state = ?` so
  a read-then-write no longer overwrites somebody else's move. Re-running a move
  that already landed is a no-op, not a 409. `/enrol` was the other way around
  the machine: it now confirms a seat from `accepted` only, inside the
  transaction the conversion path never had, so a refusal leaves no orphan
  student. ADM-05 enabled and passing. Still open: conversion's guardian link is
  written but ADM-10 wants it proven transactionally end to end, and an
  applicant still cannot be invoiced at `fee_pending` because
  `fee_invoice.student_id` is NOT NULL (ADM-13).

### Missing surfaces (endpoints the scenarios expect and nothing provides)

- **GAP-42 — Fee structure and concession have no API.** ✅ **Closed
  2026-08-12 (Phase 4).** Structures are created, edited and cloned into the
  next year through `/v1/fees/structures`; concessions and sibling policies
  have endpoints and are applied by generation as visible discount lines.
  Original finding: `fee_structure`,
  `fee_structure_line` and `fee_concession` exist in the schema with no
  endpoint, and invoice creation consults neither, so a school cannot define
  next year's fees or apply a concession as a visible line (FEE-01, FEE-03).
  GST is stored per line but never computed from `fee_head.gst_rate_pct`
  (FEE-13).

- **GAP-43 — No operational reports.** ⚠️ **Fee reports closed 2026-08-12
  (Phase 4):** `/v1/fees/reports/day-book` reconciles collections against the
  ledger's bank movement, and `/v1/fees/reports/outstanding` aggregates dues by
  grade, section and student with a per-student clearance predicate. Other
  areas' reports remain open. Original finding: No day-book / collection
  report
  (FEE-14), no outstanding-dues report (FEE-15), no chronic-absence report
  (ATT-11), no admissions funnel analytics (ADM-15), and no setup-readiness
  report for sections missing a primary teacher (ACAD-07).

- **GAP-44 — Staff records are read-only.** No staff-creation endpoint, and
  `assignSectionSubjectTeacher` accepts any staff id without checking for a
  teaching role (STF-01).

- **GAP-45 — Odds and ends surfaced by individual scenarios.** No event/RSVP
  entity (CAL-08); no section-delete endpoint (TT-10); assignment submissions
  carry no late flag (LMS-02); quiz attempts are scored by the caller rather
  than auto-scored, with no reattempt policy (LMS-04); transport check-in
  carries no client event id, so an offline replay cannot be de-duplicated
  (TRN-07); no trip reassignment on breakdown (TRN-08); a failed board-export
  job is terminal because `process()` accepts only `queued` (INT-01); device
  heartbeat is recorded but nothing alerts on an offline device (DEV-03) and
  ingestion trusts the `schoolId` in the request body rather than the device's
  registration (DEV-04); no alerting path carries tenant context (NFR-10).

## Sign-in: what the host resolver still needs

`platform.tenant_host` (V005) and `GET /v1/public/tenant` landed with the
sign-in redesign: a school is *resolved* from the address the browser arrived
on, never *chosen* from a list, because a school picker is a customer list and
a searchable one hands the roster to anyone who opens a login page. What is
still missing around it:

- **Access codes are not issued.** `/login/find-school` offers three ways in —
  the school's address, the invite link, an access code — and only the first
  works. The code needs an issuer (opaque, not derived from the school's name,
  so it cannot be guessed at), a 14-day expiry, and a per-device attempt limit;
  the field is drawn and disabled until it exists.

- **No invite links.** The admission letter and welcome email should carry a
  signed link that names the tenant, so a family types nothing at all. This is
  also what the native apps need: a Capacitor build has no host to resolve.

- **The resolver is not rate-limited.** It answers about one named host at a
  time and never lists, so it is no worse than DNS — but a client hammering it
  with guessed hostnames should be slowed down.

- **Codes have no attempt limit.** `OtpStore` returns 401 for a wrong code
  indefinitely. The design shows "2 tries left" and throws the code away after
  three; that count does not exist yet.

- **Wildcard certificate.** Issuing a per-tenant certificate for every
  `<school>.<chain>.schoolsoft.app` publishes the whole customer list to the
  public Certificate Transparency logs on the day each one is issued. A single
  wildcard for `*.schoolsoft.app` keeps subdomains out of CT. A school on its
  own domain is its own disclosure and its own choice.

- **`GET /v1/public/schools/{chain}/{school}` is an enumeration oracle.** It
  predates the resolver and answers 200 vs 404 on guessed slugs with no token,
  which is the leak the login redesign was careful to avoid. It should serve
  only schools that have opted into a public site, and answer uniformly
  otherwise.

### The three surfaces still on the old form

`school-web` and `platform-web` are redesigned. The three native-shell apps
still ask for a chain slug and take the code in a single text field. Each has
its own reason to differ, so they are listed separately rather than as one
sweep — the designs are on the canvas the redesign came from.

- **`parent-app` — mobile number first, and no host to resolve.** A Capacitor
  build has no hostname, so the tenant has to arrive some other way: the invite
  link or the access code above, or a QR on the admission letter. Until those
  exist this app cannot lose its slug field, which makes it the one blocked on
  backend work rather than on design. The form itself is phone-first (country
  code + mobile, email secondary) with a "stay signed in" that re-verifies
  every 90 days.

- **`teacher-app` — the shared-device switch.** Staffroom tablets are the
  common case and the reason persistence cannot simply default on: the app
  needs an explicit "this is a shared device" that ends the session when the
  app closes and remembers nothing. Off, a teacher stays signed in for 30 days,
  because attendance is taken at the start of every period and nobody should
  type a code to do it. The school is remembered on the device after first
  sign-in.

- **`driver-app` — sunlight and gloves.** Mobile number only (drivers have
  phones, not school email), 56px+ targets, monospaced numerals, high contrast.
  One number maps to one vehicle, so the screen after sign-in has to be
  checkable against the bus actually being driven.

- ~~**Extract the code step.**~~ Done 2026-09-22, before the second copy
  rather than after the fifth: `@schoolsoft/ui` (`packages/ui`, source-exported
  beside `@schoolsoft/api-client`) ships `CodeStep` with its own CSS module, and
  `school-web` and `platform-web` both use it. The extraction found a bug in
  the original on the way: a grid item's automatic minimum is its intrinsic
  width, so the six boxes were pushing out past the form until the input got
  `min-width: 0`. The three remaining apps import it rather than copying it.

### Sign-in states nothing renders yet

The redesign names six ways sign-in goes wrong. `school-web` handles two of
them (a wrong code, and an identifier the office has no record of). The rest
have no rendering, and most have no API shape to render:

- **School not open yet.** `refuseUntilTheSchoolIsOpen` already 403s a staff
  member whose school is still in `draft`, but the body is a generic message.
  It should carry the opening date so the page can say the school opens on it
  and the account works from then — a readiness fact the office knows and the
  person being refused does not.
- **Too many codes.** No rate limit exists, so there is no state to draw.
  Depends on the resolver and OTP limits above.
- **Offline / API unreachable.** The page currently surfaces the raw fetch
  error. It should keep what was typed and say plainly that nothing was sent,
  so nothing was used up.
- **Wrong door.** A guardian landing on the staff sign-in should be pointed at
  the Parent app — in a message shown to *everyone* who lands there, never one
  that confirms which app a given number belongs to. That confirmation is a
  cross-tenant oracle and was cut from the design for that reason.


_Fixed while building the harness: an expired or malformed bearer token
returned **403** rather than 401, because `TenantResolverFilter` used
`sendError`, which re-dispatches through `/error` and is then rejected as an
anonymous request. Clients key their refresh on 401, so this made transparent
refresh impossible. The filter now writes the 401 (and a `token_expired` code)
directly._

---

_Added 2026-08-02, from a conversation reviewing SSO/RBAC plans and noticing
tenant onboarding had no admin surface. Rewritten 2026-08-03 after a session
that built out nearly the entire MVP backend module surface — see git log
for the full sequence of commits. Certification gap list appended 2026-08-12
alongside `docs/certification-test-scenarios.md`._
