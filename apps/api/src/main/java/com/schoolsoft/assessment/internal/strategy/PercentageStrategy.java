package com.schoolsoft.assessment.internal.strategy;

import com.schoolsoft.assessment.api.CurriculumStrategy;
import com.schoolsoft.assessment.api.GradeScaleDto;
import com.schoolsoft.assessment.api.SubjectResult;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The fallback for a board with no implementation of its own (ICSE, IB, a
 * state board): straight percentage, ranked on it, promoted on it.
 *
 * It exists so an unrecognised {@code strategy_code} produces a defensible
 * report card rather than an exception on the day the cards are printed.
 */
@Component
public class PercentageStrategy implements CurriculumStrategy {

    public static final String CODE = "GENERIC-PERCENTAGE";

    @Override public String strategyCode() { return CODE; }

    @Override public boolean reportsAggregatePercentage() { return true; }

    @Override
    public Double rankKey(List<SubjectResult> subjects, GradeScaleDto scale) {
        return StrategyMaths.aggregatePercentage(subjects);
    }

    @Override
    public String promotionFor(List<SubjectResult> subjects, GradeScaleDto scale, boolean terminalGrade) {
        if (terminalGrade) return "graduate";
        Double aggregate = StrategyMaths.aggregatePercentage(subjects);
        if (aggregate == null) return null;
        return aggregate + 1e-9 >= scale.passPct() ? "promote" : "detain";
    }
}
