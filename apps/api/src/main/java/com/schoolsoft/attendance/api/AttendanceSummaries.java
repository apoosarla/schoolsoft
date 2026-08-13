package com.schoolsoft.attendance.api;

import com.schoolsoft.attendance.internal.AttendanceRepository;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The attendance module's read surface for other modules.
 *
 * A report card prints an attendance line, and it has to be the same number
 * the attendance screen shows: same working-day denominator, same treatment of
 * approved leave, same handling of a mid-year joiner's enrolment window. That
 * is guaranteed by asking here rather than by counting rows again (ASMT-10).
 */
@Service
public class AttendanceSummaries {

    private final AttendanceRepository repo;

    public AttendanceSummaries(AttendanceRepository repo) { this.repo = repo; }

    public AttendanceSummaryDto forStudent(UUID studentId, LocalDate from, LocalDate to) {
        return repo.summaryForStudent(studentId, from, to);
    }
}
