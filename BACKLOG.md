# MCB — Backlog

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
  `apps/hq-web` (Next.js App Router + TS, matches the `@mcb/hq-web` workspace
  already in root `package.json`). `/chains` page lists chains and provisions
  new ones against the endpoint above. Dashboard/KPI views from design doc
  §15 are still just the landing page stub. 2026-08-02.

## Open items

- **Platform-admin login flow.** The HQ Console's `/chains` page currently
  requires pasting in a bearer token by hand — `AuthController` /
  `UserLookupService` only resolve identities that live inside a chain schema
  (staff/guardian/student); nothing resolves against `platform.platform_user`
  or issues a `platform_admin` JWT. Needs an OTP-or-password flow scoped to
  the platform schema before the HQ Console is usable by anyone but a dev
  with direct DB/token access. Surfaced while building the chain onboarding
  endpoint above.

- **Build verification gap.** The new backend controller and `hq-web` app
  were written and reviewed against existing code patterns but not actually
  compiled/built — this sandbox has no Maven (only JDK 11, pom requires 21)
  and the npm/Maven registries are blocked by the network allowlist. Run
  `./mvnw -DskipTests compile` in `apps/api` and `npm install && npm run
  build` in `apps/hq-web` in an environment with registry access before
  treating either as verified.

- **Chain HQ Console — dashboards.** Multi-school KPIs, policy/template
  push-down, centralised reporting, identity governance (design doc §15) are
  unbuilt — only tenant onboarding exists so far.

---

_Added 2026-08-02, from a conversation reviewing SSO/RBAC plans and noticing
tenant onboarding had no admin surface. Updated same day once the endpoint +
console scaffold landed._
