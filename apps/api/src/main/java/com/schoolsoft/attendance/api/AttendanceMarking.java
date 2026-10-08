package com.schoolsoft.attendance.api;

import com.schoolsoft.attendance.internal.AttendanceRepository;
import com.schoolsoft.notification.api.Notice;
import com.schoolsoft.notification.api.NotificationService;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The attendance module's write surface — for its own controller and for
 * other modules.
 *
 * Devices push gate events that are attendance, not a parallel kind of record,
 * so they land through here rather than through their own INSERT. That is what
 * keeps one rule in one place: the closed-year refusal and the working-day
 * check (GAP-01) apply to a biometric punch exactly as they apply to a class
 * teacher's mark. What differs is precedence, and that is here too: a punch
 * never changes what a person recorded (ATT-08).
 *
 * <p>Telling the family is part of that same rule (ATT-03), which is why it
 * lives here and not in the controller: a gate that reads a card at 08:02 and
 * a teacher who saves the register at 09:15 owe the parent the same message,
 * and exactly one of it.</p>
 */
@Service
public class AttendanceMarking {

    private final AttendanceRepository repo;
    private final NotificationService notifications;

    public AttendanceMarking(AttendanceRepository repo, NotificationService notifications) {
        this.repo = repo;
        this.notifications = notifications;
    }

    /**
     * A gate device saw the student on {@code onDate}. Fills a day nobody has
     * marked and leaves alone one somebody has — see
     * {@link AttendanceRepository#recordGateRead}. No absence alert can follow:
     * a punch only ever says present.
     */
    public AttendanceRecordDto gateRead(
        UUID schoolId, UUID studentId, UUID sectionId, LocalDate onDate, String source
    ) {
        return repo.recordGateRead(schoolId, studentId, sectionId, onDate, source);
    }

    public AttendanceRecordDto mark(
        UUID schoolId, UUID studentId, UUID sectionId, LocalDate onDate, Integer periodNo,
        String status, String source, UUID markedByStaffId, String notes
    ) {
        AttendanceRecordDto record = repo.mark(
            schoolId, studentId, sectionId, onDate, periodNo, status, source, markedByStaffId, notes);
        if ("absent".equals(status)) notifyAbsence(schoolId, studentId, onDate, periodNo, source, record);
        return record;
    }

    /**
     * A mark from a sender that may have been out of touch: written only if
     * the record is still what they last saw. The alert follows a mark that
     * landed, exactly as it follows any other.
     */
    public AttendanceRepository.Synced markIfUnchanged(
        UUID schoolId, UUID studentId, UUID sectionId, LocalDate onDate, Integer periodNo,
        String status, UUID markedByStaffId, String notes, java.time.Instant seenMarkedAt
    ) {
        AttendanceRepository.Synced synced = repo.markIfUnchanged(
            schoolId, studentId, sectionId, onDate, periodNo, status, markedByStaffId, notes, seenMarkedAt);
        if (synced.applied() && "absent".equals(status)) {
            notifyAbsence(schoolId, studentId, onDate, periodNo, "manual", synced.record());
        }
        return synced;
    }

    /**
     * One alert per child per day, whatever route the absence arrived by.
     *
     * <p>The key deliberately leaves the period out. A child absent for four
     * periods is absent once as far as the family is concerned, and a register
     * marked present and then corrected to absent must produce that one
     * message rather than a second one (ATT-03).</p>
     */
    private void notifyAbsence(UUID schoolId, UUID studentId, LocalDate onDate, Integer periodNo,
                               String source, AttendanceRecordDto record) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("onDate", onDate.toString());
        vars.put("status", "absent");
        vars.put("source", source == null ? "manual" : source);
        if (periodNo != null) vars.put("periodNo", periodNo);

        notifications.notifyGuardiansOfStudent(studentId,
            Notice.of(schoolId, "attendance_absent", vars)
                .about("attendance", record.id())
                .oncePer("attendance:absent:" + studentId + ":" + onDate));
    }
}
