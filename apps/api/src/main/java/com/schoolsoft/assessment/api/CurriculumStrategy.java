package com.schoolsoft.assessment.api;

import java.util.List;

/**
 * Where a board's academic rules live (GAP-29).
 *
 * CBSE and Cambridge disagree about four things and agree about everything
 * else: what a percentage is called, what counts as a pass, what "promoted"
 * means, and what a cohort is ranked on. Each of those is a method here, so a
 * third board is a new implementation rather than another branch inside the
 * report card generator — the shape the design doc calls a curriculum strategy.
 *
 * Anything not board-specific — averaging, excluding absences, ordering
 * subjects — stays in the generator, because duplicating it per board is how
 * two boards' report cards start disagreeing about the same arithmetic.
 */
public interface CurriculumStrategy {

    /** The {@code strategy_code} this implementation answers for. */
    String strategyCode();

    /** The grade a subject percentage earns on the school's scale. */
    default String gradeFor(double pct, GradeScaleDto scale) {
        return scale.gradeFor(pct);
    }

    default boolean isPassing(double pct, GradeScaleDto scale) {
        return pct + 1e-9 >= scale.passPct();
    }

    /**
     * Whether an aggregate percentage across subjects means anything for this
     * board. CBSE prints one on every card; Cambridge subjects are separate
     * qualifications and a mean across them is a number nobody uses.
     */
    boolean reportsAggregatePercentage();

    /**
     * What the cohort is ordered on, higher being better, or null when this
     * board does not rank. Ranking has to be reproducible, so it is a pure
     * function of the subject rows (ASMT-11).
     */
    Double rankKey(List<SubjectResult> subjects, GradeScaleDto scale);

    /**
     * The promotion the results support. A human can override it — the
     * decision is the school's — but the default must not be blank, because a
     * missing promotion decision is what stops a year from closing.
     */
    String promotionFor(List<SubjectResult> subjects, GradeScaleDto scale, boolean terminalGrade);
}
