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

- ~~Modern responsive design for admin-web.~~ Prototyped a redesign first as
  a standalone artifact (four persona dashboards — Principal, Class Teacher,
  Accountant, Librarian — demonstrating the RBAC nav filtering visually)
  before touching the real app; design plan: cool "paper" neutral (not the
  cliché warm-cream/terracotta AI look), Oxford-indigo accent with brass/gold
  used sparingly, semantic status colors kept separate from the accent,
  Georgia serif for titles/headings paired with system-sans for
  everything operational, tabular numerals throughout.

  Ported into `apps/admin-web` as a **CSS-and-shell-only** change — zero
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

- ~~Same design system ported into hq-web.~~ hq-web (`/`, `/chains`) used
  the exact same original bare-bones dark CSS and class contract as
  admin-web pre-redesign, so the same zero-page-edit trick applied:
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

- ~~Platform-admin login flow.~~ hq-web's paste-a-bearer-token workaround is
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
  `platform/V003`. hq-web: new `/login` page (mirrors admin-web's OTP login
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
  `teacher:dev` script). Mobile-first, not admin-web's sidebar shell:
  single scrolling column under a fixed bottom tab bar (Today, Attendance —
  written so a third tab drops in without restructuring), 44px+ touch
  targets, same design tokens as admin-web/hq-web (Oxford-indigo,
  Georgia/system-sans, light-default with a dark media-query override).
  Reuses the existing chain OTP login flow. New backend endpoint
  `GET /v1/iam/me` (`RoleController` + `RoleRepository.subjectIdForUserAccount`)
  resolves the caller's `staff.id` from `user_account.subject_id` — the JWT
  only carries `user_account.id`, and "my timetable" needs the staff row.
  Today pulls `GET /v1/timetable/teachers/{staffId}`, filters to
  `dayOfWeek === new Date().getDay()`, and links each period into
  Attendance with the section pre-selected via a `?section=` query param;
  Attendance itself is the same roster/mark-bulk flow as admin-web's page,
  scoped to the distinct sections the teacher's timetable actually covers.

  Found and fixed a real bug during live verification, present in
  **both** teacher-app and admin-web's attendance pages: the existing-marks
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
  funnel admin-web's Admissions screen already reads.

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
  (mirrors admin-web's, scoped down). Bottom tab bar grew from 2 to 5 tabs.

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
  admin-web resolves `screens`/`roleCodes` while parent/teacher/driver
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
- `GeofenceStatusDto`'s real field names (`insideGeofence`, `distanceMeters`,
  `geofenceRadiusM`) didn't match what admin-web's new Transport screen
  client assumed (`inside`, `distanceM`, `radiusM`) — silently rendered
  "NaN" instead of erroring, since both sides were plain TS objects never
  cross-checked against the actual JSON. Caught during live verification,
  not by the type checker. Fixed by correcting the client type to the real
  DTO shape.

## Open items

- **Remaining frontend surfaces — mostly closed out.** All six frontends
  (`admin-web`, `hq-web`, `teacher-app`, `driver-app`, `parent-app`,
  `public-site`) now have at least two slices each, including a new
  Transport screen for `admin-web` — see Done above. What's still
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

---

_Added 2026-08-02, from a conversation reviewing SSO/RBAC plans and noticing
tenant onboarding had no admin surface. Rewritten 2026-08-03 after a session
that built out nearly the entire MVP backend module surface — see git log
for the full sequence of commits._
