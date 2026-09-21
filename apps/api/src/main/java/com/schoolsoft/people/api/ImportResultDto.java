package com.schoolsoft.people.api;

import java.util.UUID;

/**
 * What a commit actually wrote. {@code guardiansReused} is the interesting
 * one: siblings share a parent, and a file of 500 children carries far fewer
 * than 500 families.
 */
public record ImportResultDto(
    UUID batchId,
    int studentsCreated,
    int guardiansCreated,
    int guardiansReused,
    int enrolled
) {}
