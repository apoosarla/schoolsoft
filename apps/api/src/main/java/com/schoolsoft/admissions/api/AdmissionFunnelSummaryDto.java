package com.schoolsoft.admissions.api;

import java.util.List;
import java.util.UUID;

/**
 * The funnel as counts, which is what the pipeline screen opens with.
 *
 * <p>A season is four hundred applications and a closed year is several
 * thousand; listing them to draw a board means the browser holds the whole
 * intake to show a dozen numbers. This is one {@code GROUP BY} over an indexed
 * column, and the rows for one stage are fetched only when somebody asks for
 * that stage.</p>
 *
 * <p>{@code byState} carries a row for every state the funnel has, including
 * the empty ones: a stage with nothing in it is a fact worth showing, and a
 * board with a lane that silently disappears is harder to read than one with a
 * zero in it.</p>
 */
public record AdmissionFunnelSummaryDto(
    UUID schoolId,
    long total,
    List<StateCount> byState,
    /** Offers that lapse within the next week unless somebody calls the family. */
    long offersExpiringSoon,
    /** Offers already past their date — the seat is still held and should not be. */
    long offersExpired
) {
    public record StateCount(String state, long count) {}
}
