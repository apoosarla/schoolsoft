package com.schoolsoft.attendance.internal;

import com.schoolsoft.attendance.api.AttendanceRecordDto;
import com.schoolsoft.platform.time.SchoolClock;
import com.schoolsoft.attendance.api.AttendanceSummaryDto;
import com.schoolsoft.attendance.api.LeaveApplicationDto;
import com.schoolsoft.platform.tenancy.TenantContext;
import com.schoolsoft.platform.web.ConflictException;
import com.schoolsoft.platform.web.ForbiddenException;
import com.schoolsoft.platform.web.NotFoundException;
import com.schoolsoft.schoolcalendar.api.WorkingDayService;
import com.schoolsoft.tenancy.api.AcademicYearGuard;
import java.sql.Date;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AttendanceRepository {

    private final JdbcTemplate jdbc;
    private final WorkingDayService workingDays;
    private final AcademicYearGuard academicYears;
    private final AttendanceAmendmentService amendments;
    private final LeaveMaterialisationService leaveDays;
    private final SchoolClock clock;
    private final LeaveAuthorizer leaveAuthorizer;

    public AttendanceRepository(JdbcTemplate jdbc, WorkingDayService workingDays,
                                AcademicYearGuard academicYears, AttendanceAmendmentService amendments,
                                LeaveMaterialisationService leaveDays, LeaveAuthorizer leaveAuthorizer, SchoolClock clock) {
        this.jdbc = jdbc;
        this.workingDays = workingDays;
        this.academicYears = academicYears;
        this.amendments = amendments;
        this.leaveDays = leaveDays;
        this.leaveAuthorizer = leaveAuthorizer;
        this.clock = clock;
    }

    /** A section's cohort scope, which is what a calendar entry can be narrowed to. */
    private record SectionScope(UUID gradeId, UUID campusId) {}

    /** Refuses a mark for a date the student was not enrolled in that section on (ATT-12). */
    private void requireEnrolledOn(UUID studentId, UUID sectionId, LocalDate onDate) {
        Integer enrolled = jdbc.queryForObject(
            "SELECT count(*) FROM enrolment WHERE student_id = ? AND section_id = ? " +
            "  AND starts_on <= ? AND COALESCE(ends_on, 'infinity'::date) >= ?",
            Integer.class, studentId, sectionId, Date.valueOf(onDate), Date.valueOf(onDate));
        if (enrolled == null || enrolled == 0) {
            throw new IllegalArgumentException(
                "Student " + studentId + " was not enrolled in section " + sectionId + " on " + onDate);
        }
    }

    private SectionScope scopeOf(UUID sectionId) {
        var rows = jdbc.query(
            "SELECT grade_id, campus_id FROM section WHERE id = ?",
            (rs, i) -> new SectionScope(
                UUID.fromString(rs.getString("grade_id")),
                rs.getString("campus_id") == null ? null : UUID.fromString(rs.getString("campus_id"))),
            sectionId);
        if (rows.isEmpty()) throw new NotFoundException("Section not found: " + sectionId);
        return rows.get(0);
    }

    private static final RowMapper<AttendanceRecordDto> RECORD_MAPPER = (rs, i) -> new AttendanceRecordDto(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("school_id")),
        UUID.fromString(rs.getString("student_id")),
        UUID.fromString(rs.getString("section_id")),
        rs.getDate("on_date").toLocalDate(),
        (Integer) rs.getObject("period_no"),
        rs.getString("status"),
        rs.getString("source"),
        rs.getString("notes"),
        rs.getTimestamp("gate_seen_at") == null ? null : rs.getTimestamp("gate_seen_at").toInstant(),
        rs.getString("gate_source"),
        rs.getTimestamp("marked_at").toInstant()
    );

    private static final String RECORD_COLS =
        "id, school_id, student_id, section_id, on_date, period_no, status, source, notes, " +
        "gate_seen_at, gate_source, marked_at";

    /**
     * Upserts on {@code (student_id, on_date, period_no)}. Period-level marks
     * use the table's plain unique constraint; day-level marks
     * ({@code periodNo == null}) use the partial unique index from
     * V010, since Postgres does not treat two NULLs as conflicting under a
     * plain unique constraint.
     */
    public AttendanceRecordDto mark(
        UUID schoolId, UUID studentId, UUID sectionId, LocalDate onDate, Integer periodNo,
        String status, String source, UUID markedByStaffId, String notes
    ) {
        requireMarkable(schoolId, studentId, sectionId, onDate);
        requireInsideMarkingWindow(schoolId, studentId, onDate, periodNo, status);

        UUID id = UUID.randomUUID();
        String conflictClause = periodNo == null
            ? "ON CONFLICT (student_id, on_date) WHERE period_no IS NULL AND voided_at IS NULL DO UPDATE SET "
            : "ON CONFLICT (student_id, on_date, period_no) WHERE period_no IS NOT NULL AND voided_at IS NULL DO UPDATE SET ";
        jdbc.update(
            "INSERT INTO attendance_record (id, school_id, student_id, section_id, on_date, period_no, status, source, marked_by_staff_id, notes) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
            conflictClause +
            "  status = EXCLUDED.status, source = EXCLUDED.source, marked_by_staff_id = EXCLUDED.marked_by_staff_id, " +
            "  marked_at = now(), notes = EXCLUDED.notes",
            id, schoolId, studentId, sectionId, Date.valueOf(onDate), periodNo, status, source, markedByStaffId, notes
        );
        return jdbc.queryForObject(
            "SELECT " + RECORD_COLS + " FROM attendance_record WHERE student_id = ? AND on_date = ? " +
            "  AND period_no IS NOT DISTINCT FROM ? AND voided_at IS NULL",
            RECORD_MAPPER, studentId, Date.valueOf(onDate), periodNo
        );
    }

    /** What {@link #markIfUnchanged} did: wrote the mark, or found the record had moved. */
    public record Synced(boolean applied, AttendanceRecordDto record) {}

    /**
     * Marks, but only if the record is still what the sender last saw
     * (ATT-09).
     *
     * <p>{@code seenMarkedAt} is the {@code markedAt} the sender read, or null
     * if they saw no record at all. A teacher who marked a register on a phone
     * with no signal sends it an hour later; in that hour the office may have
     * recorded a leave for one of the children. Written blind, the stale
     * 'absent' replaces the leave and nobody is told. Here the write is
     * conditional on the token still matching, in the same statement, so there
     * is no gap between checking and writing for a third save to land in.</p>
     *
     * <p>Two things are not conflicts. A record a device filled in and nobody
     * has touched since yields to the teacher, by the same precedence that
     * stops the device overwriting the teacher. And a record that already says
     * what the sender wants is left exactly as it is — agreeing with somebody
     * is not a reason to take their name off the mark.</p>
     */
    public Synced markIfUnchanged(
        UUID schoolId, UUID studentId, UUID sectionId, LocalDate onDate, Integer periodNo,
        String status, UUID markedByStaffId, String notes, java.time.Instant seenMarkedAt
    ) {
        requireMarkable(schoolId, studentId, sectionId, onDate);
        requireInsideMarkingWindow(schoolId, studentId, onDate, periodNo, status);

        String conflictClause = periodNo == null
            ? "ON CONFLICT (student_id, on_date) WHERE period_no IS NULL AND voided_at IS NULL DO UPDATE SET "
            : "ON CONFLICT (student_id, on_date, period_no) WHERE period_no IS NOT NULL AND voided_at IS NULL DO UPDATE SET ";
        int written = jdbc.update(
            "INSERT INTO attendance_record (id, school_id, student_id, section_id, on_date, period_no, status, source, marked_by_staff_id, notes) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, 'manual', ?, ?) " +
            conflictClause +
            "  status = EXCLUDED.status, source = EXCLUDED.source, marked_by_staff_id = EXCLUDED.marked_by_staff_id, " +
            "  marked_at = now(), notes = EXCLUDED.notes " +
            "WHERE attendance_record.status <> EXCLUDED.status AND (" +
            "    attendance_record.marked_at = ? " +
            "    OR (attendance_record.source IN ('biometric','rfid') AND attendance_record.status = 'present' " +
            "        AND attendance_record.leave_application_id IS NULL))",
            UUID.randomUUID(), schoolId, studentId, sectionId, Date.valueOf(onDate), periodNo, status,
            markedByStaffId, notes, seenMarkedAt == null ? null : java.sql.Timestamp.from(seenMarkedAt));
        AttendanceRecordDto now = jdbc.queryForObject(
            "SELECT " + RECORD_COLS + " FROM attendance_record WHERE student_id = ? AND on_date = ? " +
            "  AND period_no IS NOT DISTINCT FROM ? AND voided_at IS NULL",
            RECORD_MAPPER, studentId, Date.valueOf(onDate), periodNo);
        return new Synced(written == 1 || now.status().equals(status), now);
    }

    /** The record as it stands, for telling a sender what their mark ran into. */
    public java.util.Optional<AttendanceRecordDto> find(UUID studentId, LocalDate onDate, Integer periodNo) {
        return jdbc.query(
            "SELECT " + RECORD_COLS + " FROM attendance_record WHERE student_id = ? AND on_date = ? " +
            "  AND period_no IS NOT DISTINCT FROM ? AND voided_at IS NULL",
            RECORD_MAPPER, studentId, Date.valueOf(onDate), periodNo).stream().findFirst();
    }

    /**
     * Days in a range where a gate device reported a student the register has
     * down as not there. {@code sectionIds} null means every section the
     * school has; a list confines the read to those, which is how a teacher
     * sees their own classes and no others.
     */
    public List<com.schoolsoft.attendance.api.GateDisagreementDto> gateDisagreements(
        UUID schoolId, LocalDate from, LocalDate to, List<UUID> sectionIds
    ) {
        if (sectionIds != null && sectionIds.isEmpty()) return List.of();
        StringBuilder sql = new StringBuilder(
            "SELECT ar.id, ar.on_date, ar.student_id, ar.section_id, ar.status, ar.source, ar.gate_source, " +
            "       ar.gate_seen_at, st.admission_no, " +
            "       trim(concat_ws(' ', st.first_name, st.last_name)) AS student_name, " +
            "       (g.name || '-' || sec.code) AS section_label " +
            "FROM attendance_record ar " +
            "JOIN student st ON st.id = ar.student_id " +
            "JOIN section sec ON sec.id = ar.section_id JOIN grade g ON g.id = sec.grade_id " +
            "WHERE ar.school_id = ? AND ar.on_date BETWEEN ? AND ? AND ar.period_no IS NULL " +
            "  AND ar.voided_at IS NULL AND ar.gate_seen_at IS NOT NULL " +
            "  AND ar.status IN ('absent','leave','excused')");
        List<Object> args = new java.util.ArrayList<>(List.of(schoolId, Date.valueOf(from), Date.valueOf(to)));
        if (sectionIds != null) {
            sql.append(" AND ar.section_id IN (")
                .append(String.join(",", java.util.Collections.nCopies(sectionIds.size(), "?"))).append(")");
            args.addAll(sectionIds);
        }
        sql.append(" ORDER BY ar.on_date DESC, section_label, student_name");
        return jdbc.query(sql.toString(), (rs, i) -> new com.schoolsoft.attendance.api.GateDisagreementDto(
            UUID.fromString(rs.getString("id")), rs.getDate("on_date").toLocalDate(),
            UUID.fromString(rs.getString("student_id")), rs.getString("student_name"), rs.getString("admission_no"),
            UUID.fromString(rs.getString("section_id")), rs.getString("section_label"),
            rs.getString("status"), rs.getString("source"), rs.getString("gate_source"),
            rs.getTimestamp("gate_seen_at").toInstant()), args.toArray());
    }

    private static final java.util.Set<String> GATE_SOURCES = java.util.Set.of("biometric", "rfid");

    /**
     * A device reporting that it saw the student on {@code onDate} (ATT-07,
     * ATT-08).
     *
     * <p>Not {@link #mark}, because a punch is evidence and a mark is a
     * decision. On a day nobody has recorded, the evidence is all there is and
     * it becomes the record: present, by the device. On a day somebody has —
     * a teacher's absent, an approved leave, an amendment — the record is
     * theirs and stays exactly as it is, status, author and timestamp. A
     * replayed backlog rewriting {@code marked_at} would reopen a signed-off
     * register for editing, which is the amendment workflow's whole point
     * undone by a device coming back online.</p>
     *
     * <p>One statement, so there is no window between looking and writing for
     * a teacher's save to fall into. And never a refusal for what is already
     * there: a bridge whose replay is rejected retries it, and a backlog must
     * drain. The punch is kept either way in {@code gate_seen_at}, first one
     * wins, so a child the register calls absent and the gate saw arrive is
     * visible as that.</p>
     */
    public AttendanceRecordDto recordGateRead(
        UUID schoolId, UUID studentId, UUID sectionId, LocalDate onDate, String source
    ) {
        if (source == null || !GATE_SOURCES.contains(source)) {
            // The source decides who owns the record. A bridge that could send
            // 'manual' could write a mark no later replay would ever yield to.
            throw new IllegalArgumentException(
                "A device event comes from 'biometric' or 'rfid', not '" + source + "'");
        }
        requireMarkable(schoolId, studentId, sectionId, onDate);

        jdbc.update(
            "INSERT INTO attendance_record (id, school_id, student_id, section_id, on_date, period_no, status, " +
            "  source, gate_seen_at, gate_source) VALUES (?, ?, ?, ?, ?, NULL, 'present', ?, now(), ?) " +
            "ON CONFLICT (student_id, on_date) WHERE period_no IS NULL AND voided_at IS NULL DO UPDATE SET " +
            "  gate_seen_at = COALESCE(attendance_record.gate_seen_at, EXCLUDED.gate_seen_at), " +
            "  gate_source = COALESCE(attendance_record.gate_source, EXCLUDED.gate_source)",
            UUID.randomUUID(), schoolId, studentId, sectionId, Date.valueOf(onDate), source, source);
        return jdbc.queryForObject(
            "SELECT " + RECORD_COLS + " FROM attendance_record WHERE student_id = ? AND on_date = ? " +
            "  AND period_no IS NULL AND voided_at IS NULL",
            RECORD_MAPPER, studentId, Date.valueOf(onDate));
    }

    /**
     * What has to hold for any attendance to be written against this student
     * and date, whoever is writing it — a teacher or a gate.
     */
    private void requireMarkable(UUID schoolId, UUID studentId, UUID sectionId, LocalDate onDate) {
        academicYears.requireOpenOn(schoolId, onDate);

        // Attendance is a record of something that happened. A date the school
        // has not reached yet, or one outside the student's own enrolment
        // window, is a mis-keyed form rather than a fact (ATT-12).
        LocalDate today = clock.today(schoolId);
        if (onDate.isAfter(today)) {
            throw new IllegalArgumentException(
                "Attendance cannot be marked for a future date: " + onDate + " (today is " + today + ")");
        }
        requireEnrolledOn(studentId, sectionId, onDate);

        // A day the school is not open on has no attendance to take, and letting
        // one through would corrupt every percentage computed off the same
        // calendar (GAP-01).
        SectionScope scope = scopeOf(sectionId);
        var day = workingDays.statusOf(schoolId, onDate, scope.gradeId(), scope.campusId());
        if (!day.working()) {
            throw new IllegalArgumentException(
                "Attendance cannot be marked on " + onDate + ": " + day.reason());
        }
    }

    /**
     * A mark that changes a register the school has already signed off is an
     * amendment, not a correction (ATT-06). Inside the window the teacher who
     * mistyped it fixes it; outside, the upsert refuses and points at the
     * workflow that keeps the prior value.
     *
     * Re-marking the same status is always allowed. A device never reaches
     * this: its events go through {@link #recordGateRead}, which changes
     * nothing a person wrote and so has nothing to be refused for.
     */
    private void requireInsideMarkingWindow(UUID schoolId, UUID studentId, LocalDate onDate,
                                            Integer periodNo, String status) {
        record Existing(String status, java.time.Instant markedAt) {}
        var rows = jdbc.query(
            "SELECT status, marked_at FROM attendance_record WHERE student_id = ? AND on_date = ? " +
            "  AND period_no IS NOT DISTINCT FROM ? AND voided_at IS NULL",
            (rs, i) -> new Existing(rs.getString("status"), rs.getTimestamp("marked_at").toInstant()),
            studentId, Date.valueOf(onDate), periodNo);
        if (rows.isEmpty()) return;

        Existing existing = rows.get(0);
        if (existing.status().equals(status)) return;
        if (amendments.withinEditWindow(schoolId, existing.markedAt())) return;

        throw new ConflictException(
            "Attendance for " + onDate + " was signed off and can no longer be overwritten. "
                + "Raise an amendment (POST /v1/attendance/amendments) with a reason; "
                + "it keeps the prior value of '" + existing.status() + "' and needs approval.");
    }

    public List<AttendanceRecordDto> forSectionOnDate(UUID sectionId, LocalDate onDate) {
        return jdbc.query(
            "SELECT " + RECORD_COLS + " FROM attendance_record WHERE section_id = ? AND on_date = ? " +
            "  AND voided_at IS NULL ORDER BY student_id",
            RECORD_MAPPER, sectionId, Date.valueOf(onDate)
        );
    }

    public List<AttendanceRecordDto> forStudent(UUID studentId, LocalDate from, LocalDate to) {
        return jdbc.query(
            "SELECT " + RECORD_COLS + " FROM attendance_record WHERE student_id = ? AND on_date BETWEEN ? AND ? " +
            "  AND voided_at IS NULL ORDER BY on_date, period_no",
            RECORD_MAPPER, studentId, Date.valueOf(from), Date.valueOf(to)
        );
    }

    /**
     * Attendance percentage over a range (ATT-04, ATT-10).
     *
     * Two things make this different from counting rows. The denominator is
     * working days from {@link WorkingDayService}, not calendar days, so
     * holidays and weekends never count against a child. And it is computed per
     * enrolment segment, so a mid-year joiner is measured from the day they
     * joined and a leaver up to the day they left, each against their own
     * section's calendar scope.
     *
     * Half-days count as half a day present; late counts as present (the
     * student was there); leave and excused are removed from the denominator
     * rather than counted as absence.
     */
    public AttendanceSummaryDto summaryForStudent(UUID studentId, LocalDate from, LocalDate to) {
        record Segment(UUID schoolId, UUID sectionId, UUID gradeId, UUID campusId,
                       LocalDate startsOn, LocalDate endsOn) {}

        List<Segment> segments = jdbc.query(
            "SELECT e.school_id, e.section_id, s.grade_id, s.campus_id, e.starts_on, e.ends_on " +
            "FROM enrolment e JOIN section s ON s.id = e.section_id " +
            "WHERE e.student_id = ? ORDER BY e.starts_on",
            (rs, i) -> new Segment(
                UUID.fromString(rs.getString("school_id")),
                UUID.fromString(rs.getString("section_id")),
                UUID.fromString(rs.getString("grade_id")),
                rs.getString("campus_id") == null ? null : UUID.fromString(rs.getString("campus_id")),
                rs.getDate("starts_on").toLocalDate(),
                rs.getDate("ends_on") == null ? null : rs.getDate("ends_on").toLocalDate()),
            studentId);

        int workingDayCount = 0;
        LocalDate enrolledFrom = null;
        LocalDate enrolledTo = null;
        for (Segment segment : segments) {
            LocalDate windowStart = segment.startsOn().isAfter(from) ? segment.startsOn() : from;
            LocalDate windowEnd = segment.endsOn() == null || segment.endsOn().isAfter(to) ? to : segment.endsOn();
            if (windowEnd.isBefore(windowStart)) continue;      // segment outside the asked range
            workingDayCount += workingDays.countWorkingDays(
                segment.schoolId(), windowStart, windowEnd, segment.gradeId(), segment.campusId());
            if (enrolledFrom == null || windowStart.isBefore(enrolledFrom)) enrolledFrom = windowStart;
            if (enrolledTo == null || windowEnd.isAfter(enrolledTo)) enrolledTo = windowEnd;
        }

        Map<String, Integer> counts = new LinkedHashMap<>();
        jdbc.query(
            "SELECT status, count(*) AS n FROM attendance_record " +
            "WHERE student_id = ? AND on_date BETWEEN ? AND ? AND period_no IS NULL AND voided_at IS NULL " +
            "GROUP BY status",
            rs -> { counts.put(rs.getString("status"), rs.getInt("n")); },
            studentId, Date.valueOf(from), Date.valueOf(to));

        int present = counts.getOrDefault("present", 0);
        int late = counts.getOrDefault("late", 0);
        int halfDay = counts.getOrDefault("half_day", 0);
        int absent = counts.getOrDefault("absent", 0);
        int leave = counts.getOrDefault("leave", 0);
        int excused = counts.getOrDefault("excused", 0);

        // Leave and excused days are neither present nor held against the student.
        int consideredDays = Math.max(0, workingDayCount - leave - excused);
        double attended = present + late + (halfDay * 0.5);
        Double percentage = consideredDays == 0 ? null
            : Math.round(attended * 10000.0 / consideredDays) / 100.0;

        return new AttendanceSummaryDto(
            studentId, from, to, enrolledFrom, enrolledTo,
            workingDayCount, consideredDays,
            present, absent, late, halfDay, leave, excused, percentage);
    }

    // -------------------------- Leave --------------------------

    private static final RowMapper<LeaveApplicationDto> LEAVE_MAPPER = (rs, i) -> new LeaveApplicationDto(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("school_id")),
        rs.getString("subject_type"),
        UUID.fromString(rs.getString("subject_id")),
        rs.getDate("from_date").toLocalDate(),
        rs.getDate("to_date").toLocalDate(),
        rs.getString("reason"),
        rs.getString("status"),
        rs.getString("approver_staff_id") == null ? null : UUID.fromString(rs.getString("approver_staff_id"))
    );

    private static final String LEAVE_COLS =
        "id, school_id, subject_type, subject_id, from_date, to_date, reason, status, approver_staff_id";

    public LeaveApplicationDto applyLeave(
        UUID schoolId, String subjectType, UUID subjectId, LocalDate fromDate, LocalDate toDate, String reason
    ) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO leave_application (id, school_id, subject_type, subject_id, from_date, to_date, reason) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            id, schoolId, subjectType, subjectId, Date.valueOf(fromDate), Date.valueOf(toDate), reason
        );
        return jdbc.queryForObject("SELECT " + LEAVE_COLS + " FROM leave_application WHERE id = ?", LEAVE_MAPPER, id);
    }

    public List<LeaveApplicationDto> listLeave(UUID schoolId, String status) {
        String sql = "SELECT " + LEAVE_COLS + " FROM leave_application WHERE school_id = ?" +
            (status == null ? "" : " AND status = ?") + " ORDER BY from_date DESC";
        return status == null ? jdbc.query(sql, LEAVE_MAPPER, schoolId) : jdbc.query(sql, LEAVE_MAPPER, schoolId, status);
    }

    /**
     * Deciding a leave is what makes it real: approval writes the days into the
     * register (ATT-05, ATT-13) and withdrawing the approval takes them back
     * out again.
     *
     * The approver is checked rather than accepted — before this, any staff id
     * could be passed as {@code approverStaffId} and a teacher could approve
     * their own leave (STF-02).
     */
    @Transactional
    public LeaveApplicationDto decideLeave(UUID id, String status, UUID approverStaffId) {
        var existing = jdbc.query(
            "SELECT school_id, subject_type, subject_id, status FROM leave_application WHERE id = ?",
            (rs, i) -> new Object[]{
                UUID.fromString(rs.getString("school_id")), rs.getString("subject_type"),
                UUID.fromString(rs.getString("subject_id")), rs.getString("status")},
            id);
        if (existing.isEmpty()) throw new NotFoundException("Leave application not found: " + id);
        UUID schoolId = (UUID) existing.get(0)[0];
        String subjectType = (String) existing.get(0)[1];
        UUID subjectId = (UUID) existing.get(0)[2];
        String previous = (String) existing.get(0)[3];

        UUID approver = leaveAuthorizer.requireApprover(subjectType, subjectId, approverStaffId);
        var snap = TenantContext.get();

        // Two approved leaves over the same day would each claim it, and
        // withdrawing either would take the day away from the other. The
        // second approval is refused instead; the office withdraws or narrows
        // the first.
        if ("approved".equals(status) && !"approved".equals(previous)) {
            var overlapping = jdbc.query(
                "SELECT o.from_date, o.to_date FROM leave_application o, leave_application l " +
                "WHERE l.id = ? AND o.id <> l.id AND o.status = 'approved' " +
                "  AND o.subject_type = l.subject_type AND o.subject_id = l.subject_id " +
                "  AND o.from_date <= l.to_date AND o.to_date >= l.from_date LIMIT 1",
                (rs, i) -> rs.getDate("from_date").toLocalDate() + " to " + rs.getDate("to_date").toLocalDate(),
                id);
            if (!overlapping.isEmpty()) {
                throw new ConflictException(
                    "An approved leave already covers " + overlapping.get(0) + "; withdraw or change it first");
            }
        }

        jdbc.update(
            "UPDATE leave_application SET status = ?, approver_staff_id = ?, decided_by_user_id = ?, " +
            "  decided_at = now() WHERE id = ?",
            status, approver, snap == null ? null : snap.userAccountId(), id);

        if ("approved".equals(status) && !"approved".equals(previous)) {
            leaveDays.materialise(id);
        } else if (!"approved".equals(status) && "approved".equals(previous)) {
            leaveDays.unwind(id);
        }
        return jdbc.queryForObject("SELECT " + LEAVE_COLS + " FROM leave_application WHERE id = ?", LEAVE_MAPPER, id);
    }

}
