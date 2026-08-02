/**
 * Jobs — scheduled and retryable background work. Spring's @Scheduled covers
 * cron-style runs; for long-lived workflows (admissions, year-end rollover,
 * board exports) Phase 2 will introduce Temporal as called out in §17.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Jobs")
package com.schoolsoft.jobs;
