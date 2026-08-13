package com.schoolsoft.assessment.api;

import com.schoolsoft.assessment.internal.ExamScheduleRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The assessment module's read surface for other modules.
 *
 * During an exam week the class timetable is not what happens — the exam
 * timetable is (TT-09). The timetable module asks here rather than querying
 * {@code exam_session} itself, so "published schedules only" stays one rule in
 * one place: an exams officer's draft must never blank a section's day.
 */
@Service
public class ExamSchedules {

    private final ExamScheduleRepository repo;

    public ExamSchedules(ExamScheduleRepository repo) { this.repo = repo; }

    /** Published papers a grade sits on a date; empty on an ordinary school day. */
    public List<ExamSessionDto> publishedSessionsForGradeOn(UUID gradeId, LocalDate date) {
        return repo.publishedSessionsForGradeOn(gradeId, date);
    }
}
