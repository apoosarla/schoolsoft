package com.schoolsoft.attendance.api;

import com.schoolsoft.attendance.internal.AttendanceRepository;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The attendance module's write surface for other modules.
 *
 * Devices push gate events that are attendance, not a parallel kind of record,
 * so they land through here rather than through their own INSERT. That is what
 * keeps one rule in one place: the closed-year refusal and the working-day
 * check (GAP-01) apply to a biometric punch exactly as they apply to a class
 * teacher's mark.
 */
@Service
public class AttendanceMarking {

    private final AttendanceRepository repo;

    public AttendanceMarking(AttendanceRepository repo) { this.repo = repo; }

    /** Marks a day-level record, upserting on (student, date). */
    public AttendanceRecordDto markDay(
        UUID schoolId, UUID studentId, UUID sectionId, LocalDate onDate, String status, String source
    ) {
        return repo.mark(schoolId, studentId, sectionId, onDate, null, status, source, null, null);
    }
}
