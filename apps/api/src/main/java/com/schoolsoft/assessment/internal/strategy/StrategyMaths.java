package com.schoolsoft.assessment.internal.strategy;

import com.schoolsoft.assessment.api.GradeScaleDto;
import com.schoolsoft.assessment.api.SubjectResult;
import java.util.List;

/**
 * The arithmetic every board shares. It lives in one place so that CBSE and
 * Cambridge cannot drift into computing "the same" average two ways — the
 * boards differ in what they do with the number, not in how it is reached.
 *
 * Absences, exemptions and unassessed subjects are excluded from every
 * denominator here rather than counted as zero (ASMT-05).
 */
final class StrategyMaths {

    private StrategyMaths() {}

    /** Marks-weighted percentage across the subjects that carry a score. */
    static Double aggregatePercentage(List<SubjectResult> subjects) {
        double obtained = 0;
        double max = 0;
        for (SubjectResult subject : subjects) {
            if (!subject.counted() || subject.maxMarks() == null || subject.marksObtained() == null) continue;
            obtained += subject.marksObtained();
            max += subject.maxMarks();
        }
        return max <= 0 ? null : round(obtained * 100.0 / max);
    }

    /** Mean grade point across scored subjects — Cambridge's comparable number. */
    static Double meanGradePoint(List<SubjectResult> subjects, GradeScaleDto scale) {
        double total = 0;
        int counted = 0;
        for (SubjectResult subject : subjects) {
            if (!subject.counted()) continue;
            Double point = scale.gradePointFor(subject.percentage());
            if (point == null) continue;
            total += point;
            counted++;
        }
        return counted == 0 ? null : round(total / counted);
    }

    static int failedSubjects(List<SubjectResult> subjects, GradeScaleDto scale) {
        int failed = 0;
        for (SubjectResult subject : subjects) {
            if (!subject.counted()) continue;
            if (subject.percentage() + 1e-9 < scale.passPct()) failed++;
        }
        return failed;
    }

    static int scoredSubjects(List<SubjectResult> subjects) {
        return (int) subjects.stream().filter(SubjectResult::counted).count();
    }

    static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
