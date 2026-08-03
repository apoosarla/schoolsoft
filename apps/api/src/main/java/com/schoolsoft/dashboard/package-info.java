/**
 * Operational Dashboards (per design doc §7 Layer 6.1, "MVP-lite"). Read-only
 * aggregate queries over data already owned by other modules — attendance %,
 * fee collection, admissions funnel, comms reach. Deliberately has no writes
 * and no domain tables of its own; it is a reporting facade, following the
 * same direct-SQL-across-shared-tables convention used elsewhere in this
 * codebase (e.g. PeopleRepository joining section/grade) rather than adding
 * Java dependencies on five other modules' internal packages.
 */
package com.schoolsoft.dashboard;
