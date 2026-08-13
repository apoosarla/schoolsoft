package com.schoolsoft.assessment.internal.strategy;

import com.schoolsoft.assessment.api.CurriculumStrategy;
import com.schoolsoft.assessment.api.GradeScaleDto;
import com.schoolsoft.assessment.api.SubjectResult;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Cambridge. Each subject is its own qualification, so a percentage summed
 * across five of them is a number nobody outside the building uses: the card
 * prints grades per subject, and the cohort is ordered on mean grade point
 * instead. A candidate is held back only when the year as a whole is ungraded,
 * not for one weak paper.
 */
@Component
public class CieStrategy implements CurriculumStrategy {

    @Override public String strategyCode() { return "CIE-IGCSE"; }

    @Override public boolean reportsAggregatePercentage() { return false; }

    @Override
    public Double rankKey(List<SubjectResult> subjects, GradeScaleDto scale) {
        return StrategyMaths.meanGradePoint(subjects, scale);
    }

    @Override
    public String promotionFor(List<SubjectResult> subjects, GradeScaleDto scale, boolean terminalGrade) {
        if (terminalGrade) return "graduate";
        int scored = StrategyMaths.scoredSubjects(subjects);
        if (scored == 0) return null;
        return StrategyMaths.failedSubjects(subjects, scale) * 2 > scored ? "detain" : "promote";
    }
}
