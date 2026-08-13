package com.schoolsoft.assessment.internal.strategy;

import com.schoolsoft.assessment.api.CurriculumStrategy;
import com.schoolsoft.assessment.api.GradeScaleDto;
import com.schoolsoft.assessment.api.SubjectResult;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * CBSE. An aggregate percentage across subjects is meaningful and printed, the
 * cohort is ranked on it, and a single failed subject is a compartment rather
 * than a detention — the board expects the school to re-examine, not to hold
 * the child back for one paper.
 */
@Component
public class CbseStrategy implements CurriculumStrategy {

    @Override public String strategyCode() { return "CBSE-CCE-2024"; }

    @Override public boolean reportsAggregatePercentage() { return true; }

    @Override
    public Double rankKey(List<SubjectResult> subjects, GradeScaleDto scale) {
        return StrategyMaths.aggregatePercentage(subjects);
    }

    @Override
    public String promotionFor(List<SubjectResult> subjects, GradeScaleDto scale, boolean terminalGrade) {
        if (terminalGrade) return "graduate";
        if (StrategyMaths.scoredSubjects(subjects) == 0) return null;   // nothing to decide on yet
        Double aggregate = StrategyMaths.aggregatePercentage(subjects);
        if (aggregate != null && aggregate + 1e-9 < scale.passPct()) return "detain";
        return StrategyMaths.failedSubjects(subjects, scale) >= 2 ? "detain" : "promote";
    }
}
