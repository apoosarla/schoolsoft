package com.schoolsoft.tenancy.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A school as the platform console sees it, from outside the chain: enough to
 * tell an open school from one still being set up, and how many children are
 * on its register today.
 *
 * <p>Deliberately not the whole checklist. Readiness is ten counting queries
 * per school, and a chain with fifty schools would pay five hundred of them to
 * draw one table — the operator asks for a single school's checklist when they
 * want it.</p>
 */
public record ChainSchoolDto(
    UUID id,
    String slug,
    String name,
    String boardCode,
    String lifecycle,
    Instant wentLiveAt,
    long activeEnrolments
) {}
