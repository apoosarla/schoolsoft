package com.schoolsoft.assessment.api;

import java.util.List;
import java.util.UUID;

/**
 * A school's grade boundaries, as the curriculum strategy reads them.
 *
 * The bands are data rather than code because two schools on the same board
 * legitimately disagree about where a B stops — and because a board moving its
 * boundaries mid-year must not need a deploy.
 */
public record GradeScaleDto(
    UUID id,
    UUID schoolId,
    String code,
    String name,
    String strategyCode,
    double passPct,
    List<Band> bands
) {
    /** One boundary: {@code minPct <= pct <= maxPct} earns {@code grade}. */
    public record Band(String grade, double minPct, double maxPct, Double gradePoint) {}

    /**
     * The grade a percentage earns, or null when the scale has no band for it.
     * Bands are matched highest-first so an overlapping pair resolves to the
     * better grade rather than to whichever row the database returned first.
     */
    public String gradeFor(double pct) {
        Band match = bandFor(pct);
        return match == null ? null : match.grade();
    }

    public Double gradePointFor(double pct) {
        Band match = bandFor(pct);
        return match == null ? null : match.gradePoint();
    }

    private Band bandFor(double pct) {
        Band best = null;
        for (Band band : bands) {
            if (pct + 1e-9 >= band.minPct() && pct - 1e-9 <= band.maxPct()) {
                if (best == null || band.minPct() > best.minPct()) best = band;
            }
        }
        return best;
    }
}
