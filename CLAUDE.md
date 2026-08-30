# CLAUDE.md

Guidance for Claude Code (claude.ai/code) working in this repository.

## Commands

### Backend

```sh
cd apps/api

./mvnw spring-boot:run                    # boot the API on :8080
./mvnw -DskipTests package                # build
./mvnw test -Dgroups=P1,harness           # the blocking gate — what CI runs on a PR
./mvnw test -Dgroups=P2,P3                # report-only scenarios
./mvnw test -Dtest=FeesCertTest           # one scenario class
./mvnw test -Dtest='*ArchitectureTest'    # the structural rules alone, no database
```

From the repo root: `npm run api:dev`, `npm run api:build`, `npm run db:seed`.

### Frontends

Six Next.js apps in one npm workspace: `admin-web`, `hq-web`, `public-site`,
`parent-app`, `teacher-app`, `driver-app`.

```sh
npm run admin:dev      # also hq:dev, teacher:dev, parent:dev, driver:dev, public:dev
npm run dev:web        # admin + hq + public together
```

### Infrastructure

```sh
npm run infra:up       # redis, opensearch, emqx, minio
npm run infra:down
```

**Postgres is not containerised.** It runs on the host at `localhost:5432` with
`schoolsoft/schoolsoft` — the compose file says so in a comment and starts no
database.

## Running the certification suite locally

`CertDatabase` prefers Testcontainers, which needs a Docker daemon. Where there
isn't one, point it at the local Postgres instead:

```sh
cd apps/api
SCHOOLSOFT_TEST_DB_URL=jdbc:postgresql://localhost:5432/schoolsoft_cert \
SCHOOLSOFT_TEST_DB_USER=schoolsoft SCHOOLSOFT_TEST_DB_PASSWORD=schoolsoft \
./mvnw --batch-mode test -Dgroups=P1,harness
```

The suite owns that database — it drops and rebuilds its chain on every run —
so it must be dedicated to the suite, never the dev database.

**After editing or adding a chain migration, drop the stale chain schemas
first.** Flyway tracks each `chain_*` schema's version independently, so a
schema left at the previous head is not re-migrated and the run fails in ways
that look like application bugs:

```sh
for s in $(PGPASSWORD=schoolsoft psql -U schoolsoft -d schoolsoft_cert -tAc \
    "SELECT nspname FROM pg_namespace WHERE nspname LIKE 'chain_%'"); do
  PGPASSWORD=schoolsoft psql -q -U schoolsoft -d schoolsoft_cert \
    -c "DROP SCHEMA \"$s\" CASCADE"
done
```

## Architecture

### Overview

Multi-tenant K-12 school OS. Modular monolith (Spring Modulith) + six Next.js
apps. Java 25 + Spring Boot, Postgres 16, `JdbcTemplate` (no ORM), Flyway,
Redis, OpenSearch, EMQX, MinIO. Design doc: `schoolsoft-design.md`.

### Module structure

Each bounded context is `com.schoolsoft.<module>`, split in two:

| Package | Contents |
|---|---|
| `<module>.api` | `@RestController`, DTO records, and the interfaces other modules may call. The module's published surface. |
| `<module>.internal` | Repositories (`JdbcTemplate`, SQL as string constants) and services. Nobody outside the module may touch these. |

Modules: `admissions`, `assessment`, `attendance`, `audit`, `boardintegration`,
`comms`, `curriculum`, `dashboard`, `device`, `enrolment`, `eventbus`,
`featureflags`, `fees`, `file`, `iam`, `jobs`, `library`, `lms`, `notification`,
`people`, `publicsite`, `rollover`, `schoolcalendar`, `search`, `tenancy`,
`theming`, `timetable`, `transport`.

`package-info.java` in each declares the Modulith module and, more usefully,
says in prose what the module is for and what is deliberately out of its scope.
Read it before adding to a module.

`com.schoolsoft.platform` is the floor everything stands on: `security`
(`SecurityConfig`, `JwtService`, `TenantResolverFilter`, `Perm`), `tenancy`
(`TenantContext`, `TenantAwareDataSource`), `db` (`ChainSchemaMigrator`,
`DataSourceConfig`), `web` (exception handling). It depends on no business
module, and `ArchitectureTest` enforces that.

Cross-module: call the other module's `api` package. When that means exposing a
repository, publish a narrow interface instead — see
`admissions/api/PublicAdmissions.java`, which exists so `publicsite` (which
serves unauthenticated traffic) cannot reach `transition` or `convertToStudent`.

### Multi-tenancy

Schema-per-**chain**, not per school. A chain is a group of schools under one
operator; `chain_<slug>` holds every table, and `school_id` separates the
schools inside it. `platform` holds the chain registry and platform admins.

`TenantContext` is a ThreadLocal `Snapshot(chainSchema, chainId, schoolId,
userAccountId, subjectType, trusted)` set by `TenantResolverFilter` from the
JWT's `cs`/`cid`/`sid`/`st` claims. `TenantAwareDataSource` sets `search_path`
from it. Async hand-offs must propagate it explicitly.

Subject types: `staff`, `guardian`, `student`, `chain_admin` (the customer's HQ),
`platform_admin` (Schoolsoft staff). `trusted` bypasses tenant filtering for
jobs and migrations.

### Migrations

Two independent sets, both Flyway:

- `db/migration/platform` — the platform schema, run once by `spring.flyway.*`.
- `db/migration/chain` — applied to **every** `chain_*` schema separately by
  `ChainSchemaMigrator`, with per-schema versions in
  `platform.chain_schema_version`.

`schoolsoft.chain-migrations.auto-apply-on-startup` is true in dev. Production
should disable it and drive `migrateChain(chainId)` from the deploy pipeline so
a failure is visible and retryable.

Never edit a migration that has been applied anywhere — add a new one.

## Authorization

Read `docs/adr/0001-authorization-model.md` before touching a controller or a
role. The short version:

- `platform/security/Perm.java` is the permission vocabulary — a fixed enum.
- `role_perm` holds the grants, so a school's custom role works without a
  deploy.
- Every HTTP-mapped method declares `@PreAuthorize` in one of six allowed
  shapes; `RbacArchitectureTest` fails the build on anything else.
- A `.own` permission is half a gate. The handler must narrow the read with
  `SelfScope`.
- Campus/section/period scoping is a separate axis (`CampusScope`,
  `AttendanceAuthorizer`, `LeaveAuthorizer`, `AssessmentAuthorizer`) that runs
  *after* the permission check.
- A role check never lives in a repository — `ArchitectureTest` fails the build
  on one. `CampusScope` is the exception and is not a decision: it answers "of
  what", its result is a `WHERE` clause, and that belongs next to the SQL
  because a list read that forgets to narrow itself is a leak.

`@EnableMethodSecurity` on `SecurityConfig` is load-bearing: without it all 268
annotations are inert and nothing in the annotations themselves would fail.
`RbacEnforcementTest` is what catches its removal.

### Adding a controller

1. Put it in `<module>.api`.
2. Every HTTP-mapped method gets `@PreAuthorize`.
3. A public path also needs registering in `SecurityConfig` permitAll **and**
   `TenantResolverFilter.PUBLIC_PREFIXES` — the annotation alone does not make
   a path anonymous, and the filter alone does not make it authorized.
4. Add positive and negative cases to `RbacEnforcementTest`.

### Adding a permission

1. Add the constant to `Perm`.
2. Gate the endpoint with it.
3. Add a **new** migration granting it to the roles that should hold it — never
   edit `V026__role_perms.sql`.
4. If it ends in `.own`, call `SelfScope` in the handler.
5. Positive and negative cases in `RbacEnforcementTest`.

Skip 1, 2 or 3 and one of the twelve structural rules fails the build.

## Tests

### The certification suite

`docs/certification-test-scenarios.md` is the contract. Every id in it has
exactly one `cert_<AREA>_<NN>_<description>` method carrying the priority tag
the catalogue assigns, and `CatalogueSyncTest` fails the build on any
mismatch — adding a row to the document breaks the build until somebody writes
the test, even if that test lands `@Disabled`.

A scenario blocked by a product gap is `@Disabled("GAP-nn — …")`. **A red build
means a regression, not unfinished work** — the unfinished work is the disabled
list, published as `target/certification-status.md`.

Groups: `P1` blocks the merge; `P2`/`P3` report only; `harness` is the
structural tests and runs with P1.

Scenarios share one Spring context and one seeded fixture per JVM. A scenario
that mutates shared rows makes its own row rather than editing a neighbour's —
the fixture (`CertificationFixture`, two schools, ~a month of history) is broad
enough that it can.

### Structural tests

`src/test/java/com/schoolsoft/architecture/` — ArchUnit, no database, fast.
`ArchitectureTest` holds the module boundaries; `RbacArchitectureTest` holds the
authorization annotations. Both tagged `harness`.

`ArchitectureTest.transactions_are_declared_at_the_use_case` carries a list of
known offenders (repositories that declare their own `@Transactional`). **That
list only shrinks.** Adding to it takes a deliberate edit and should be argued
for in review.

### What is not tested

Worth knowing before trusting a green build:

- There are no unit tests. Everything runs through HTTP against a real
  database, so pure logic (fee generation, grading bands, rollover date maths)
  has no fast test and cannot be exercised without Postgres.
- The six frontends have **zero** tests.

## Conventions

- `@Transactional` belongs on the service that owns the use case, never on a
  controller or a repository. Four repositories predate this rule; see above.
- Money moves by reversal, never deletion. A bounced cheque is a `reversal`
  adjustment, not a deleted payment — every payment writes balanced
  `ledger_entry` rows and the ledger is append-only in practice.
- Payments are idempotent on `idempotency_key` so a gateway webhook retry never
  double-posts.
- High-risk mutations carry `@Audited(action, targetType, idParam, snapshot)`.
  `requireReason` defaults true. The interceptor is a **web** interceptor, so it
  runs ahead of method security: an audited endpoint called without a reason
  answers 400 about the payload even when the caller would have been refused
  403.
- `NotFoundException`, `ForbiddenException` and `ConflictException` from
  `platform.web` map to 404/403/409 in `GlobalExceptionHandler`. A
  `DataIntegrityViolationException` becomes a 409 carrying the constraint's own
  message rather than a 500.
- Number series (admission numbers, roll numbers, invoice numbers) are issued by
  `NumberSeries`, not by the caller. Passing one explicitly is the exception.
- **A record written back whole carries a `version`**, the client sends back
  what it read, and the UPDATE is `... version = version + 1 WHERE id = ? AND
  version = ?`. Zero rows means somebody saved first: 409, never a silent
  overwrite. `role` and `fee_structure` are versioned; `V028` explains why the
  other contended tables deliberately are not, and `ArchitectureTest` fails the
  build on an unversioned write to a versioned table.
- **A state transition is one conditional UPDATE that names the state it moves
  out of**, and zero rows affected is a conflict, not a success. Reading the
  status and then writing leaves a window; `ReportCardService.publish` used to
  check for `draft` and then write unconditionally, so a card unlocked in
  between got published anyway. Re-running a transition that already happened
  is *not* an error — a retry must not fail because the first attempt worked.
  `ReportCardTransitionTest` is the worked example.
- `audit_log` is a hash chain (V027): a trigger stamps every row with the hash
  of the row before it, `UPDATE` is refused outright, and
  `GET /v1/audit/chain` walks the chain and names the first break. Never
  `DELETE` a subset of it — that forks the chain, which is what the chain is
  there to expose. `TRUNCATE` restarts it cleanly and is what a fixture should
  use.

## Known gaps

`BACKLOG.md` is the live list. Two structural ones worth knowing up front:

- **No unit tests, and no frontend tests.** See above — everything runs through
  HTTP against a real database.
- **`mark` is unversioned.** Concurrent mark entry is last-write-wins, but every
  change writes a `mark_revision` row, so an overwrite is recorded and
  recoverable rather than silent. Versioning it would mean a version on every
  cell of a marks grid; the revision trail is the cheaper guarantee.
