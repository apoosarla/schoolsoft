package com.schoolsoft.certification.support;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A whole school of its own, for the scenarios that move a whole school.
 *
 * Every other certification scenario makes its own row inside the shared
 * fixture. Rollover cannot: it closes a year, re-enrols every child and marks
 * the old enrolments spent, so running it against Oakridge would break the
 * fifty scenarios that read Oakridge. Each rollover scenario therefore builds
 * a small school of its own — three grades, two sections each, ten children —
 * and rolls that.
 *
 * The school is seeded <b>ready to close</b>: assessments published, report
 * cards locked with a promotion decision, the register complete, nothing owed.
 * A scenario that wants a blocker introduces it, which keeps each test's
 * premise visible in the test rather than buried here.
 *
 * Years are deliberately short (a month, then a month, then the rest) so the
 * readiness check's day-by-day sweep is a real sweep over a small calendar.
 */
public class RolloverSandbox {

    /** Grade codes, lowest first: promotion walks this ladder. */
    public static final List<String> GRADES = List.of("R1", "R2", "R3");
    public static final List<String> SECTIONS = List.of("A", "B");
    public static final int STUDENTS_PER_SECTION = 5;
    public static final int SECTION_CAPACITY = 12;

    /**
     * The closing year straddles the fixture's "today" on purpose: a school
     * rolls over at the end of the year it is living in, and an invoice keyed
     * in this morning has to land inside it.
     */
    public static final LocalDate AY1_START = LocalDate.of(2026, 6, 1);
    public static final LocalDate AY1_END   = LocalDate.of(2026, 8, 31);
    public static final LocalDate AY2_START = LocalDate.of(2026, 9, 1);
    public static final LocalDate AY2_END   = LocalDate.of(2027, 3, 31);
    public static final LocalDate AY3_START = LocalDate.of(2027, 4, 1);
    public static final LocalDate AY3_END   = LocalDate.of(2028, 3, 31);

    public record Sandbox(
        String slug,
        UUID schoolId,
        UUID campusId,
        UUID sourceAyId,
        UUID targetAyId,
        UUID thirdAyId,
        UUID principalStaffId,
        UUID principalUserId,
        UUID teacherStaffId,
        UUID subjectId,
        UUID electiveSubjectId,
        UUID feeHeadId,
        UUID routeId,
        UUID stopId,
        /** grade code → id */
        Map<String, UUID> grades,
        /** "R1-A" → section id, in the source year */
        Map<String, UUID> sections,
        /** admission-order student ids of the source year, by section label */
        Map<String, List<UUID>> studentsBySection
    ) {
        public UUID section(String gradeCode, String sectionCode) {
            return sections.get(gradeCode + "-" + sectionCode);
        }
        public List<UUID> students(String gradeCode, String sectionCode) {
            return studentsBySection.get(gradeCode + "-" + sectionCode);
        }
        public UUID firstStudent(String gradeCode, String sectionCode) {
            return students(gradeCode, sectionCode).get(0);
        }
    }

    private final JdbcTemplate jdbc;
    private final String slug;

    public RolloverSandbox(JdbcTemplate jdbc, String slug) {
        this.jdbc = jdbc;
        this.slug = slug;
    }

    public Sandbox build() {
        UUID schoolId = id("school");
        jdbc.update(
            "INSERT INTO school (id, slug, name, board_code, state_code) VALUES (?, ?, ?, 'CBSE', '36')",
            schoolId, slug, "Rollover Sandbox " + slug);

        UUID campusId = id("campus");
        jdbc.update("INSERT INTO campus (id, school_id, name, is_primary) VALUES (?, ?, 'Main', TRUE)",
            campusId, schoolId);

        UUID ay1 = academicYear(schoolId, "S1", AY1_START, AY1_END, true, "active");
        UUID ay2 = academicYear(schoolId, "S2", AY2_START, AY2_END, false, "planning");
        UUID ay3 = academicYear(schoolId, "S3", AY3_START, AY3_END, false, "planning");

        UUID curriculumId = id("curriculum");
        jdbc.update(
            "INSERT INTO curriculum (id, school_id, board_code, strategy_code, name, version, is_published) " +
            "VALUES (?, ?, 'CBSE', 'CBSE-CCE-2024', 'Sandbox Curriculum', '1', TRUE)",
            curriculumId, schoolId);

        UUID subjectId = id("subject:MATH");
        jdbc.update("INSERT INTO subject (id, school_id, code, name) VALUES (?, ?, 'RMATH', 'Mathematics')",
            subjectId, schoolId);
        UUID electiveSubjectId = id("subject:MUS");
        jdbc.update("INSERT INTO subject (id, school_id, code, name) VALUES (?, ?, 'RMUS', 'Music')",
            electiveSubjectId, schoolId);

        UUID principalStaffId = id("staff:principal");
        UUID principalUserId = id("user:principal");
        jdbc.update(
            "INSERT INTO staff (id, school_id, employee_no, first_name, last_name, employment_type, joined_on) " +
            "VALUES (?, ?, ?, 'Sandbox', 'Principal', 'permanent', ?)",
            principalStaffId, schoolId, "EMP-" + slug, AY1_START.minusYears(1));
        jdbc.update(
            "INSERT INTO staff_role (id, staff_id, role_code, scope_type, scope_id) " +
            "VALUES (?, ?, 'principal', 'school', ?)",
            id("role:principal"), principalStaffId, schoolId);
        jdbc.update(
            "INSERT INTO user_account (id, school_id, subject_type, subject_id, email) " +
            "VALUES (?, ?, 'staff', ?, ?)",
            principalUserId, schoolId, principalStaffId, "principal@" + slug + ".test");

        UUID teacherStaffId = id("staff:teacher");
        jdbc.update(
            "INSERT INTO staff (id, school_id, employee_no, first_name, last_name, employment_type, joined_on) " +
            "VALUES (?, ?, ?, 'Sandbox', 'Teacher', 'permanent', ?)",
            teacherStaffId, schoolId, "EMPT-" + slug, AY1_START.minusYears(1));

        Map<String, UUID> grades = new LinkedHashMap<>();
        for (int i = 0; i < GRADES.size(); i++) {
            UUID gradeId = id("grade:" + GRADES.get(i));
            grades.put(GRADES.get(i), gradeId);
            jdbc.update("INSERT INTO grade (id, school_id, code, name, sort_order) VALUES (?, ?, ?, ?, ?)",
                gradeId, schoolId, GRADES.get(i), "Grade " + GRADES.get(i), i + 1);
        }

        Map<String, UUID> sections = new LinkedHashMap<>();
        for (String gradeCode : GRADES) {
            for (String sectionCode : SECTIONS) {
                UUID sectionId = id("section:" + gradeCode + ":" + sectionCode);
                sections.put(gradeCode + "-" + sectionCode, sectionId);
                jdbc.update(
                    "INSERT INTO section (id, school_id, grade_id, academic_year_id, code, name, " +
                    "  curriculum_id, strategy_code, capacity, campus_id) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, 'CBSE-CCE-2024', ?, ?)",
                    sectionId, schoolId, grades.get(gradeCode), ay1, sectionCode,
                    "Grade " + gradeCode + "-" + sectionCode + " S1", curriculumId,
                    SECTION_CAPACITY, campusId);
                // The subject is taught, so the section has something to assess —
                // and so YEC-10 has a teacher assignment that must *not* travel.
                jdbc.update(
                    "INSERT INTO section_subject_teacher (id, section_id, subject_id, teacher_staff_id, is_primary) " +
                    "VALUES (?, ?, ?, ?, TRUE)",
                    UUID.randomUUID(), sectionId, subjectId, teacherStaffId);
            }
        }

        UUID feeHeadId = id("fee-head:tuition");
        jdbc.update(
            "INSERT INTO fee_head (id, school_id, code, name, is_recurring, gst_rate_pct) " +
            "VALUES (?, ?, 'RTUIT', 'Tuition', TRUE, 0)", feeHeadId, schoolId);
        for (String gradeCode : GRADES) {
            UUID structureId = id("fee-structure:" + gradeCode);
            jdbc.update(
                "INSERT INTO fee_structure (id, school_id, grade_id, academic_year_id, name, schedule) " +
                "VALUES (?, ?, ?, ?, ?, '{\"cycles\":[\"Term 1\"]}'::jsonb)",
                structureId, schoolId, grades.get(gradeCode), ay1, "Grade " + gradeCode + " fees");
            jdbc.update(
                "INSERT INTO fee_structure_line (id, fee_structure_id, fee_head_id, amount) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), structureId, feeHeadId, 5000);
        }

        UUID routeId = id("route");
        UUID stopId = id("stop");
        jdbc.update(
            "INSERT INTO transport_route (id, school_id, code, name, direction) " +
            "VALUES (?, ?, 'RT1', 'Sandbox Route', 'both')", routeId, schoolId);
        jdbc.update(
            "INSERT INTO transport_stop (id, school_id, route_id, name, sort_order) " +
            "VALUES (?, ?, ?, 'Sandbox Stop', 1)",
            stopId, schoolId, routeId);

        Map<String, List<UUID>> studentsBySection =
            seedStudents(schoolId, grades, sections, ay1);

        // The register, one row per section per working day: readiness asks
        // whether a day was marked, not who was in it.
        seedAttendance(schoolId, sections.values(), AY1_START, AY1_END);
        seedAssessmentAndCards(schoolId, ay1, subjectId, sections, studentsBySection);

        return new Sandbox(slug, schoolId, campusId, ay1, ay2, ay3, principalStaffId, principalUserId,
            teacherStaffId, subjectId, electiveSubjectId, feeHeadId, routeId, stopId,
            grades, sections, studentsBySection);
    }

    private UUID academicYear(UUID schoolId, String code, LocalDate from, LocalDate to,
                              boolean isCurrent, String status) {
        UUID ayId = id("ay:" + code);
        jdbc.update(
            "INSERT INTO academic_year (id, school_id, code, starts_on, ends_on, is_current, status) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            ayId, schoolId, code, from, to, isCurrent, status);
        jdbc.update(
            "INSERT INTO term (id, academic_year_id, code, name, starts_on, ends_on) " +
            "VALUES (?, ?, 'T1', 'Term 1', ?, ?)",
            id("term:" + code), ayId, from, to);
        return ayId;
    }

    private Map<String, List<UUID>> seedStudents(UUID schoolId, Map<String, UUID> grades,
                                                 Map<String, UUID> sections, UUID ayId) {
        Map<String, List<UUID>> bySection = new LinkedHashMap<>();
        List<Object[]> students = new ArrayList<>();
        List<Object[]> guardians = new ArrayList<>();
        List<Object[]> links = new ArrayList<>();
        List<Object[]> enrolments = new ArrayList<>();
        int seq = 0;

        for (String gradeCode : GRADES) {
            for (String sectionCode : SECTIONS) {
                String label = gradeCode + "-" + sectionCode;
                List<UUID> ids = new ArrayList<>();
                for (int i = 0; i < STUDENTS_PER_SECTION; i++) {
                    seq++;
                    UUID studentId = id("student:" + label + ":" + i);
                    ids.add(studentId);
                    students.add(new Object[]{
                        studentId, schoolId, slug.toUpperCase() + "-" + String.format("%03d", seq),
                        "Child" + (i + 1), label, LocalDate.of(2015, 1 + (i % 12), 1 + (i % 27))
                    });

                    // Twins: one child in R1-A and one in R1-B, same household.
                    // They are the pair the reshuffle rule has to keep together
                    // when both are promoted into a reorganised R2.
                    boolean sharesFamily = i == 0 && gradeCode.equals("R1");
                    UUID guardianId = sharesFamily ? id("guardian:twins") : id("guardian:" + label + ":" + i);
                    if (!sharesFamily || sectionCode.equals("A")) {
                        guardians.add(new Object[]{
                            guardianId, schoolId, "Guardian", label + i,
                            "+9199" + String.format("%06d", Math.abs((slug + label + i).hashCode() % 1000000))
                        });
                    }
                    links.add(new Object[]{ guardianId, studentId, "father", true });

                    enrolments.add(new Object[]{
                        id("enrolment:" + label + ":" + i), schoolId, studentId,
                        sections.get(label), ayId, AY1_START, "active", String.format("%02d", i + 1)
                    });
                }
                bySection.put(label, ids);
            }
        }

        jdbc.batchUpdate(
            "INSERT INTO student (id, school_id, admission_no, first_name, last_name, dob) " +
            "VALUES (?, ?, ?, ?, ?, ?)", students);
        jdbc.batchUpdate(
            "INSERT INTO guardian (id, school_id, first_name, last_name, phone) VALUES (?, ?, ?, ?, ?)",
            guardians);
        jdbc.batchUpdate(
            "INSERT INTO guardian_student (guardian_id, student_id, relation, is_primary) VALUES (?, ?, ?, ?)",
            links);
        jdbc.batchUpdate(
            "INSERT INTO enrolment (id, school_id, student_id, section_id, academic_year_id, starts_on, " +
            "  status, roll_no) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", enrolments);

        // The twins' household, as the fee module models one. Rollover reads
        // family_id to keep them in the same section, so the pair has to be a
        // family and not merely two children who share a phone number.
        UUID familyId = id("family:twins");
        jdbc.update(
            "INSERT INTO family (id, school_id, code, name, primary_guardian_id) VALUES (?, ?, ?, ?, ?)",
            familyId, schoolId, "TWINS", "Twin household", id("guardian:twins"));
        jdbc.update("UPDATE student SET family_id = ? WHERE id IN (?, ?)",
            familyId, bySection.get("R1-A").get(0), bySection.get("R1-B").get(0));
        return bySection;
    }

    private void seedAttendance(UUID schoolId, Iterable<UUID> sections, LocalDate from, LocalDate to) {
        List<Object[]> rows = new ArrayList<>();
        for (UUID sectionId : sections) {
            UUID studentId = jdbc.queryForObject(
                "SELECT student_id FROM enrolment WHERE section_id = ? ORDER BY roll_no LIMIT 1",
                UUID.class, sectionId);
            for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
                if (day.getDayOfWeek().getValue() > 5) continue;      // default Mon–Fri week
                rows.add(new Object[]{ UUID.randomUUID(), schoolId, studentId, sectionId, day });
            }
        }
        jdbc.batchUpdate(
            "INSERT INTO attendance_record (id, school_id, student_id, section_id, on_date, status) " +
            "VALUES (?, ?, ?, ?, ?, 'present')", rows);
    }

    /**
     * One published assessment per section and a locked report card per child,
     * carrying the promotion decision rollover reads: everybody promotes,
     * except the top grade, which graduates.
     */
    private void seedAssessmentAndCards(UUID schoolId, UUID ayId, UUID subjectId,
                                        Map<String, UUID> sections,
                                        Map<String, List<UUID>> studentsBySection) {
        List<Object[]> cards = new ArrayList<>();
        for (var entry : sections.entrySet()) {
            String gradeCode = entry.getKey().substring(0, entry.getKey().indexOf('-'));
            UUID assessmentId = UUID.randomUUID();
            jdbc.update(
                "INSERT INTO assessment (id, school_id, section_id, subject_id, strategy_code, name, " +
                "  assessment_type, max_marks, status) " +
                "VALUES (?, ?, ?, ?, 'CBSE-CCE-2024', 'Annual', 'Annual', 100, 'published')",
                assessmentId, schoolId, entry.getValue(), subjectId);

            String decision = GRADES.get(GRADES.size() - 1).equals(gradeCode) ? "graduate" : "promote";
            for (UUID studentId : studentsBySection.get(entry.getKey())) {
                cards.add(new Object[]{
                    UUID.randomUUID(), schoolId, studentId, ayId, "CBSE-CCE-2024", "annual",
                    entry.getValue(), decision
                });
            }
        }
        jdbc.batchUpdate(
            "INSERT INTO report_card (id, school_id, student_id, academic_year_id, strategy_code, " +
            "  template_code, payload, section_id, promotion_decision, status, is_locked) " +
            "VALUES (?, ?, ?, ?, ?, ?, '{}'::jsonb, ?, ?, 'locked', TRUE)", cards);
    }

    /** Deterministic ids, so a failing run is reproducible and cleanup is exact. */
    private UUID id(String key) {
        return CertificationFixture.id(slug + ":" + key);
    }
}
