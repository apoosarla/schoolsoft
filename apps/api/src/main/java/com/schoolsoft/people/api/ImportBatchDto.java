package com.schoolsoft.people.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A parsed spreadsheet, waiting to be committed or corrected.
 *
 * <p>{@code canCommit} is the whole of the documented choice ENR-09 asks for:
 * a batch with an error in it does not go in at all. A register assembled out
 * of the rows that happened to parse is worse than no register, because
 * nobody can tell afterwards which children are missing from it.</p>
 */
public record ImportBatchDto(
    UUID id,
    UUID schoolId,
    String filename,
    /** previewed · committed · superseded */
    String status,
    int rowCount,
    int errorCount,
    boolean canCommit,
    List<ImportRowDto> rows,
    Instant createdAt,
    Instant committedAt
) {}
