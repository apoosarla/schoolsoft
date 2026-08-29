# ADR 0001 — Authorization model

**Status:** accepted
**Date:** 2026-08-30

## Context

The API had 29 controllers, ~230 endpoints and **no authorization**.
`SecurityConfig` ended at `.anyRequest().authenticated()`, and not one
controller method carried `@PreAuthorize` — `@EnableMethodSecurity` was not on
either, so an annotation would have been inert had anybody written one.

Any valid token could call any endpoint. The sharpest instance:
`POST /v1/iam/staff-roles/assign` was reachable by a guardian's token, which
means any parent could grant themselves `it_admin`.

`role.screen_keys` existed and looked like access control, but it drives
admin-web's navigation and nothing else. It is a per-tenant, UI-mutable list —
useful for hiding a tab, no use at all as a security boundary.

Meanwhile authorization decisions that *did* exist had leaked downward:
`AttendanceRepository`, `SchoolRepository`, `AssessmentRepository` and
`PeopleRepository` all import `Authz` and make access decisions inside SQL
helpers, where nobody reviewing "who may do this" would look.

## Decision

Three layers, each answering a different question.

### 1. May this caller use this endpoint at all? — `Perm` + `@PreAuthorize`

`platform/security/Perm.java` is the permission **vocabulary**: a fixed enum of
dotted codes (`fee.invoice.view`, `attendance.mark`, `role.manage`). Every
HTTP-mapped controller method declares exactly one gate:

```java
@PreAuthorize("@perm.can('fee.invoice.view')")
@PreAuthorize("@perm.canAny('transport.view', 'transport.track')")
@PreAuthorize("@perm.canAnyOf('fee.invoice.view', 'fee.invoice.view.own')")
@PreAuthorize("isAuthenticated()")
@PreAuthorize("permitAll()")            // only under /v1/auth/, /v1/public/, /v1/webhooks/
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
```

Nothing else is legal — `RbacArchitectureTest` fails the build on any other
shape, on a missing annotation, and on a code that does not resolve in `Perm`.

**Why the vocabulary is code and the grants are data.** A permission only means
something if an endpoint checks it, so the set of permissions is a property of
the source tree, and a gate naming an undefined one is a hole rather than a
typo. Who *holds* a permission is a different kind of fact: a school may define
custom roles through the roles UI, and those must work without a deploy. So
grants live in `role_perm` (seeded per system role by
`V026__role_perms.sql`), keyed by `role.code`.

This differs from the approach in the sibling HMS codebase, where the matrix is
a checked-in YAML mirrored by hand into Java and TypeScript. That mirror is
unverified there and drifts. Here the *codes* are single-sourced in the enum and
both the annotations and the SQL are checked against it mechanically.

### 2. Whose row is it? — `SelfScope`

A code ending in `.own` (`fee.invoice.view.own`, `attendance.view.own`)
authorises a caller to read **their own** slice. It is deliberately never a
complete gate. `canAnyOf(staffCode, ownCode)` is the promise that the handler
narrows the read, and `SelfScope` is how:

- `requireStudent(id, unrestricted)` — a guardian's child, a student themselves
- `requireGuardian` / `requireStaff` / `requireSection` / `requireUserAccount`
- `narrowToOwnStudents(rows, …)` — for list reads keyed by something else (a
  component's marks are the whole class; the parent gets their own row and
  learns nothing about the rest)

A caller holding the unrestricted permission passes straight through — the
office is not restricted to its own children.

`RbacArchitectureTest` fails the build if a controller uses `canAnyOf` without
holding a `SelfScope`. That is a structural check, not a proof the call is
made; `RbacEnforcementTest` is the proof, over HTTP, per endpoint shape.

### 3. Which campus / section / period? — the authorizers

Deliberately separate, and run **after** the permission gate has let the call
through. A permission says "may you use this endpoint", never "for whom".

`Authz` used to hold both kinds of question, which is what let it be reached
from inside four repositories. It is now split:

- **`CampusScope.ofCurrentUser()`** answers "of what". Its result is a `WHERE`
  clause, so it belongs next to the SQL — a list read that forgets to narrow
  itself is a leak, and the safest place to make that impossible to forget is
  in the query. Repositories may depend on it.
- **`Authz.rolesOfCurrentUser()` / `currentStaffId()`** answer "may they", and
  an answer of "no" is a refusal. Those live in named authorizers —
  `AttendanceAuthorizer` (who may mark a register), `LeaveAuthorizer` (who may
  decide leave, and never their own), `AssessmentAuthorizer` (who may reopen
  something sealed). `ArchitectureTest` fails the build on a repository that
  reaches for `Authz`.

## Principals

| Subject type | Permissions |
|---|---|
| `platform_admin` | all (Schoolsoft staff) |
| trusted job | all (`TenantContext.trustedJob`) |
| `chain_admin` | every unrestricted read, no writes — derived from `Perm.isUnrestrictedRead()`, so a permission added later lands on the right side of the line without anybody remembering to come back |
| `staff` | union of `role_perm` across unrevoked `staff_role` grants |
| `guardian` | fixed `GUARDIAN_BASELINE` in code |
| `student` | fixed `STUDENT_BASELINE` in code |

Guardians and students get a built-in set rather than role rows because they
have no `staff_role`, and making their access editable would let a school hand
a parent the fee ledger by accident.

## Consequences

- `@EnableMethodSecurity` on `SecurityConfig` is load-bearing. Remove it and
  every one of the 268 annotations silently stops being enforced, with no test
  failure from the annotations themselves. `RbacEnforcementTest` is what would
  catch it.
- `AccessDeniedException` needed an explicit handler in
  `GlobalExceptionHandler`. Method security throws it from inside the
  controller invocation, so the `@RestControllerAdvice` sees it — and without a
  handler the catch-all turned every refusal into a 500.
- Permission resolution is one query per request, memoised into the request
  attributes. Outside a request (jobs, tests) it resolves fresh.
- A `driver` role now exists. driver-app had been shipping against a login with
  no role at all, which was fine when nothing was gated.

## Known gaps

Recorded rather than papered over.

1. **`driver` holds school-wide `student.view`.** A driver needs the students on
   their own route; this grants them the school. Narrowing it needs a
   route-scoped student read that transport does not have. The alternative
   today was either a broken check-in screen or a silent over-grant nobody
   wrote down.
2. **Exam schedule reads do not filter unpublished.** `exam.view.own` lets a
   family read `/v1/exams/schedules`, and the repository does not restrict to
   published schedules. Pre-existing; the gate did not introduce it.
3. **Teacher grants are school-wide.** `class_teacher` and `subject_teacher`
   hold `attendance.mark` and `mark.enter` across the school. Narrowing to
   their own sections is STF-05 and is enforced today only where a contextual
   authorizer exists (`AttendanceAuthorizer`).
4. **`AuditInterceptor` runs ahead of method security.** It is a web
   interceptor, so an `@Audited(requireReason = true)` endpoint called without
   a reason returns 400 about the payload even when the caller would have been
   refused with 403. Minor information leak; the ordering is the cause.
5. **The frontends are ungated.** `screen_keys` still drives navigation, which
   is fine — frontend gates are UX, and the backend is now the boundary. But
   the apps have no per-route perm check and no tests.

## Guards

`RbacArchitectureTest` and `ArchitectureTest` run in the `harness` group, which
is part of the blocking P1 CI job. They fail the build on:

1. an HTTP-mapped method with no `@PreAuthorize`
2. a gate outside the allowed grammar
3. a gate naming a permission `Perm` does not define
4. a permission granted in SQL that `Perm` no longer defines
5. a permission in `Perm` that no endpoint checks
6. `permitAll()` outside the anonymous path prefixes
7. a `canAnyOf` controller with no `SelfScope`
8. a cross-module read of another module's `internal` package
9. `platform` depending on a business module
10. a `@RestController` outside an `api` package
11. a new repository declaring its own `@Transactional`
12. a repository reaching for `Authz`

## Adding a permission

1. Add the constant to `Perm`.
2. Gate the endpoint with it.
3. Add a **new** migration granting it to the roles that should hold it — never
   edit `V026__role_perms.sql`.
4. If it ends in `.own`, call `SelfScope` in the handler.
5. Add positive and negative cases to `RbacEnforcementTest`.

Steps 1–3 are enforced: skip any of them and one of the eleven rules above
fails.
