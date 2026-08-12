# Certification suite

Executable form of `docs/certification-test-scenarios.md`. One test method per
scenario id, named `cert_<ID>_<description>`; `CatalogueSyncTest` fails the
build if the catalogue and the suite disagree, and regenerates
`docs/certification-status.md`.

## Running

```bash
cd apps/api

# Everything (Testcontainers starts its own Postgres).
./mvnw test

# The merge gate: P1 scenarios plus the harness's own checks.
./mvnw test -Dgroups=P1,harness

# Report-only tiers.
./mvnw test -Dgroups=P2,P3

# No Docker daemon? Point at a dedicated database instead.
SCHOOLSOFT_TEST_DB_URL=jdbc:postgresql://localhost:5432/schoolsoft_cert \
SCHOOLSOFT_TEST_DB_USER=schoolsoft SCHOOLSOFT_TEST_DB_PASSWORD=schoolsoft \
  ./mvnw test

# NFR scale (slow — seeds a 2,000-student school).
./mvnw test -Dschoolsoft.cert.bulk-students=2000
```

The fixture drops and rebuilds `chain_certchain` on the first test of a run, so
the database it points at must be dedicated to the suite.

## Adding work

A scenario blocked by a missing capability lands as `@Disabled("GAP-nn — …")`
naming what blocks it, never as a weakened assertion. The disabled list is the
remaining work; it shrinks as phases land.
