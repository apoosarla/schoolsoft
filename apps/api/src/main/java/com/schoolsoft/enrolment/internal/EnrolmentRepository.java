package com.schoolsoft.enrolment.internal;

import com.schoolsoft.enrolment.api.EnrolmentDto;
import com.schoolsoft.platform.web.NotFoundException;
import com.schoolsoft.tenancy.api.NumberSeries;
import com.schoolsoft.tenancy.api.SectionCapacity;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class EnrolmentRepository {

    private final JdbcTemplate jdbc;
    private final SectionCapacity capacity;
    private final NumberSeries numbers;

    public EnrolmentRepository(JdbcTemplate jdbc, SectionCapacity capacity, NumberSeries numbers) {
        this.jdbc = jdbc;
        this.capacity = capacity;
        this.numbers = numbers;
    }

    /**
     * Roll numbers run per section, so the series is scoped to one. A section
     * that already holds hand-keyed rolls seeds the series past them, and the
     * generator still skips a number someone typed in by hand afterwards —
     * the uniqueness index means a collision is a failed admission, not a
     * cosmetic problem.
     */
    private String rollNumberFor(UUID schoolId, UUID sectionId, String supplied) {
        if (supplied != null && !supplied.isBlank()) return supplied;

        Integer highest = jdbc.queryForObject(
            "SELECT COALESCE(max(NULLIF(regexp_replace(roll_no, '\\D', '', 'g'), '')::int), 0) " +
            "FROM enrolment WHERE section_id = ? AND status = 'active' AND roll_no IS NOT NULL",
            Integer.class, sectionId);
        long startAt = (highest == null ? 0 : highest) + 1L;

        for (int attempt = 0; attempt < 50; attempt++) {
            String candidate = numbers.next(schoolId, NumberSeries.Kind.roll, sectionId, "{SEQ:2}", null, startAt);
            Integer taken = jdbc.queryForObject(
                "SELECT count(*) FROM enrolment WHERE section_id = ? AND roll_no = ? AND status = 'active'",
                Integer.class, sectionId, candidate);
            if (taken == null || taken == 0) return candidate;
        }
        throw new IllegalStateException("Could not find a free roll number in section " + sectionId);
    }

    private static final RowMapper<EnrolmentDto> MAPPER = (rs, i) -> new EnrolmentDto(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("school_id")),
        UUID.fromString(rs.getString("student_id")),
        UUID.fromString(rs.getString("section_id")),
        rs.getString("section_label"),
        UUID.fromString(rs.getString("academic_year_id")),
        rs.getDate("starts_on").toLocalDate(),
        rs.getDate("ends_on") == null ? null : rs.getDate("ends_on").toLocalDate(),
        rs.getString("status"),
        rs.getString("roll_no")
    );

    private static final String SELECT =
        "SELECT e.id, e.school_id, e.student_id, e.section_id, (g.code || '-' || sec.code) AS section_label, " +
        "       e.academic_year_id, e.starts_on, e.ends_on, e.status, e.roll_no " +
        "FROM enrolment e JOIN section sec ON sec.id = e.section_id JOIN grade g ON g.id = sec.grade_id ";

    public List<EnrolmentDto> listByStudent(UUID studentId) {
        return jdbc.query(SELECT + "WHERE e.student_id = ? ORDER BY e.starts_on DESC", MAPPER, studentId);
    }

    public List<EnrolmentDto> listBySection(UUID sectionId, boolean activeOnly) {
        String sql = SELECT + "WHERE e.section_id = ?" + (activeOnly ? " AND e.status = 'active'" : "") + " ORDER BY e.roll_no";
        return jdbc.query(sql, MAPPER, sectionId);
    }

    public Optional<EnrolmentDto> findActiveByStudent(UUID studentId) {
        var rows = jdbc.query(SELECT + "WHERE e.student_id = ? AND e.status = 'active'", MAPPER, studentId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<EnrolmentDto> find(UUID id) {
        var rows = jdbc.query(SELECT + "WHERE e.id = ?", MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public EnrolmentDto enrol(UUID schoolId, UUID studentId, UUID sectionId, UUID academicYearId,
                              LocalDate startsOn, String rollNo, String overCapacityReason) {
        if (findActiveByStudent(studentId).isPresent()) {
            throw new IllegalArgumentException("Student already has an active enrolment; withdraw or transfer first");
        }
        String override = capacity.reserveSeat(sectionId, overCapacityReason);
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO enrolment (id, school_id, student_id, section_id, academic_year_id, starts_on, status, " +
            "  roll_no, over_capacity_reason) VALUES (?, ?, ?, ?, ?, ?, 'active', ?, ?)",
            id, schoolId, studentId, sectionId, academicYearId, Date.valueOf(startsOn),
            rollNumberFor(schoolId, sectionId, rollNo), override
        );
        return find(id).orElseThrow();
    }

    /** Closes the current enrolment and opens a new active one in {@code newSectionId}, same academic year. */
    public EnrolmentDto transfer(UUID enrolmentId, UUID newSectionId, String rollNo, String overCapacityReason) {
        var current = find(enrolmentId).orElseThrow(() -> new NotFoundException("Enrolment not found: " + enrolmentId));
        String override = capacity.reserveSeat(newSectionId, overCapacityReason);
        LocalDate today = LocalDate.now();
        jdbc.update(
            "UPDATE enrolment SET status = 'transferred', ends_on = ? WHERE id = ?",
            Date.valueOf(today), enrolmentId
        );
        UUID newId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO enrolment (id, school_id, student_id, section_id, academic_year_id, starts_on, status, " +
            "  roll_no, over_capacity_reason) VALUES (?, ?, ?, ?, ?, ?, 'active', ?, ?)",
            newId, current.schoolId(), current.studentId(), newSectionId, current.academicYearId(),
            Date.valueOf(today), rollNumberFor(current.schoolId(), newSectionId, rollNo), override
        );
        // The section they left has a hole in its roll numbers; closing it is
        // the registrar's call, not an automatic renumber of every child's book.
        return find(newId).orElseThrow();
    }

    public EnrolmentDto setStatus(UUID enrolmentId, String status, LocalDate endsOn) {
        jdbc.update(
            "UPDATE enrolment SET status = ?, ends_on = ? WHERE id = ?",
            status, endsOn == null ? null : Date.valueOf(endsOn), enrolmentId
        );
        return find(enrolmentId).orElseThrow(() -> new NotFoundException("Enrolment not found: " + enrolmentId));
    }

    /**
     * Re-sequences a section's active roll numbers from 1, in admission-number
     * order, and resets the section's series to follow. Explicit rather than
     * automatic: renumbering changes what is written inside every child's
     * exercise book, so a school does it deliberately (ENR-02).
     */
    public List<EnrolmentDto> renumber(UUID sectionId) {
        var schoolIds = jdbc.query("SELECT school_id FROM section WHERE id = ?",
            (rs, i) -> UUID.fromString(rs.getString("school_id")), sectionId);
        if (schoolIds.isEmpty()) throw new NotFoundException("Section not found: " + sectionId);

        List<UUID> ordered = jdbc.query(
            "SELECT e.id FROM enrolment e JOIN student s ON s.id = e.student_id " +
            "WHERE e.section_id = ? AND e.status = 'active' ORDER BY s.admission_no",
            (rs, i) -> UUID.fromString(rs.getString("id")), sectionId);

        // Park the old numbers first: the uniqueness index would otherwise trip
        // over an intermediate state where two enrolments both hold roll 3.
        jdbc.update("UPDATE enrolment SET roll_no = NULL WHERE section_id = ? AND status = 'active'", sectionId);
        for (int i = 0; i < ordered.size(); i++) {
            jdbc.update("UPDATE enrolment SET roll_no = ? WHERE id = ?",
                String.format("%02d", i + 1), ordered.get(i));
        }
        jdbc.update(
            "UPDATE number_series SET next_value = ? WHERE school_id = ? AND kind = 'roll' AND scope_id = ?",
            ordered.size() + 1, schoolIds.get(0), sectionId);
        return listBySection(sectionId, true);
    }
}
