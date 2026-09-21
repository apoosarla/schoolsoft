package com.schoolsoft.admissions.api;

import java.util.List;

/**
 * A page of search results, with the number of matches behind it.
 *
 * <p>Search carries its total where the stage list does not: a stage's total is
 * already on the tile that opened it, but nobody knows how many applications
 * match "Kabir" until the search runs, and "showing 25 of 3" and "showing 25 of
 * 300" are different answers to "did I find the right child?".</p>
 */
public record AdmissionSearchResultDto(
    long total,
    List<AdmissionApplicationDto> rows
) {}
