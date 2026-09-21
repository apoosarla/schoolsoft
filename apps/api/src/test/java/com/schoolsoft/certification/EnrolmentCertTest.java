package com.schoolsoft.certification;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolsoft.certification.support.AbstractCertificationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** CERT-ENR — enrolment & student records. */
class EnrolmentCertTest extends AbstractCertificationTest {

    @Test @Tag("P1")
    void cert_ENR_01_studentHasExactlyOneActiveEnrolment() {
        String token = principalToken(cbse());
        UUID studentId = createStudent("ENR01-" + UUID.randomUUID().toString().substring(0, 8));
        UUID sectionId = currentFocusSection(cbse());

        var enrolled = post("/v1/enrolment", Map.of("schoolId", cbse().id(), "studentId", studentId,
            "sectionId", sectionId, "academicYearId", cbse().currentAy().id(),
            "startsOn", "2026-08-01", "rollNo", "99"), token);
        assertThat(enrolled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(enrolled.getBody().get("status").asText()).isEqualTo("active");

        // A second active enrolment is refused rather than silently created.
        var second = post("/v1/enrolment", Map.of("schoolId", cbse().id(), "studentId", studentId,
            "sectionId", sectionOf(cbse(), cbse().currentAy().code(), "5", "B"),
            "academicYearId", cbse().currentAy().id(), "startsOn", "2026-08-01", "rollNo", "98"), token);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(count("SELECT count(*) FROM enrolment WHERE student_id = ? AND status = 'active'", studentId))
            .isEqualTo(1);
    }

    @Test @Tag("P1")
    void cert_ENR_02_admissionAndRollNumbersFollowSchoolPolicy() {
        String token = principalToken(cbse());
        UUID sectionId = UUID.fromString(post("/v1/tenancy/schools/" + cbse().id() + "/sections", body(
            "gradeId", gradeOf(cbse(), cbse().focusGradeCode()), "academicYearId", cbse().currentAy().id(),
            "code", "NUM", "name", "Grade 5-NUM", "strategyCode", cbse().strategyCode(),
            "capacity", 30), token).getBody().get("id").asText());
        UUID otherSectionId = UUID.fromString(post("/v1/tenancy/schools/" + cbse().id() + "/sections", body(
            "gradeId", gradeOf(cbse(), cbse().focusGradeCode()), "academicYearId", cbse().currentAy().id(),
            "code", "NUM2", "name", "Grade 5-NUM2", "strategyCode", cbse().strategyCode(),
            "capacity", 30), token).getBody().get("id").asText());

        // No admission number supplied: the school's series issues one.
        var created = post("/v1/people/students", body(
            "schoolId", cbse().id(), "firstName", "Series", "lastName", "Candidate",
            "dob", "2015-06-06", "gender", "male"), token);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID first = UUID.fromString(created.getBody().get("id").asText());
        String admissionNo = created.getBody().get("admissionNo").asText();
        assertThat(admissionNo).matches("ADM\\d{6}");

        UUID second = createStudentWithoutNumber("Second");
        UUID third = createStudentWithoutNumber("Third");
        assertThat(count("SELECT count(DISTINCT admission_no) FROM student WHERE id IN (?, ?, ?)",
            first, second, third)).isEqualTo(3);

        try {
            // No roll number supplied either: sequential within the section.
            String rollOne = enrolFor(first, sectionId, null);
            String rollTwo = enrolFor(second, sectionId, null);
            assertThat(rollOne).isEqualTo("01");
            assertThat(rollTwo).isEqualTo("02");

            // The same roll number twice in one section is refused by the index,
            // not just by convention.
            var duplicate = post("/v1/enrolment", body(
                "schoolId", cbse().id(), "studentId", third, "sectionId", sectionId,
                "academicYearId", cbse().currentAy().id(), "startsOn", "2026-08-01", "rollNo", rollOne), token);
            assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

            // The same roll number in a different section is fine.
            assertThat(enrolFor(third, otherSectionId, "01")).isEqualTo("01");

            // A transfer takes the next roll number in the receiving section
            // rather than carrying the old one across.
            UUID enrolmentId = queryOne("SELECT id FROM enrolment WHERE student_id = ? AND status = 'active'",
                UUID.class, first);
            var transferred = post("/v1/enrolment/" + enrolmentId + "/transfer",
                body("newSectionId", otherSectionId), token);
            assertThat(transferred.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(transferred.getBody().get("rollNo").asText()).isEqualTo("02");

            // And the section they left can be renumbered from 1, in admission order.
            var renumbered = post("/v1/enrolment/sections/" + sectionId + "/renumber", null, token);
            assertThat(renumbered.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(renumbered.getBody()).hasSize(1);
            assertThat(renumbered.getBody().get(0).get("rollNo").asText()).isEqualTo("01");
        } finally {
            inChainDo(jdbc -> {
                jdbc.update("DELETE FROM enrolment WHERE section_id IN (?, ?)", sectionId, otherSectionId);
                jdbc.update("DELETE FROM student WHERE id IN (?, ?, ?)", first, second, third);
                jdbc.update("DELETE FROM number_series WHERE scope_id IN (?, ?)", sectionId, otherSectionId);
                jdbc.update("DELETE FROM section WHERE id IN (?, ?)", sectionId, otherSectionId);
            });
        }
    }

    private UUID createStudentWithoutNumber(String firstName) {
        var created = post("/v1/people/students", body(
            "schoolId", cbse().id(), "firstName", firstName, "lastName", "Candidate",
            "dob", "2015-06-06", "gender", "female"), principalToken(cbse()));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        return UUID.fromString(created.getBody().get("id").asText());
    }

    private String enrolFor(UUID studentId, UUID sectionId, String rollNo) {
        var enrolled = post("/v1/enrolment", body(
            "schoolId", cbse().id(), "studentId", studentId, "sectionId", sectionId,
            "academicYearId", cbse().currentAy().id(), "startsOn", "2026-08-01", "rollNo", rollNo),
            principalToken(cbse()));
        assertThat(enrolled.getStatusCode()).isEqualTo(HttpStatus.OK);
        return enrolled.getBody().get("rollNo").asText();
    }

    @Test @Tag("P1")
    void cert_ENR_03_midYearSectionChangeKeepsHistoryAttributedPerDate() {
        String token = principalToken(cbse());
        UUID studentId = createStudent("ENR03-" + UUID.randomUUID().toString().substring(0, 8));
        UUID sectionA = currentFocusSection(cbse());
        UUID sectionB = sectionOf(cbse(), cbse().currentAy().code(), cbse().focusGradeCode(), "B");

        var enrolled = post("/v1/enrolment", Map.of("schoolId", cbse().id(), "studentId", studentId,
            "sectionId", sectionA, "academicYearId", cbse().currentAy().id(),
            "startsOn", "2026-04-01", "rollNo", "97"), token);
        UUID enrolmentId = UUID.fromString(enrolled.getBody().get("id").asText());

        // Attendance marked while in section A.
        post("/v1/attendance/mark", Map.of("schoolId", cbse().id(), "studentId", studentId,
            "sectionId", sectionA, "onDate", "2026-07-06", "status", "present"), token);

        var transferred = post("/v1/enrolment/" + enrolmentId + "/transfer",
            Map.of("newSectionId", sectionB, "rollNo", "12"), token);
        assertThat(transferred.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(transferred.getBody().get("sectionId").asText()).isEqualTo(sectionB.toString());

        var history = get("/v1/enrolment/students/" + studentId, token).getBody();
        assertThat(history).hasSize(2);
        assertThat(queryOne("SELECT status FROM enrolment WHERE id = ?", String.class, enrolmentId))
            .isEqualTo("transferred");
        assertThat(queryOne("SELECT ends_on IS NOT NULL FROM enrolment WHERE id = ?", Boolean.class, enrolmentId))
            .isTrue();

        // The old mark stays attached to the student and to the section it was taken in.
        var marks = get("/v1/attendance/students/" + studentId + "?from=2026-07-01&to=2026-07-31", token).getBody();
        assertThat(marks).hasSize(1);
        assertThat(marks.get(0).get("sectionId").asText()).isEqualTo(sectionA.toString());
    }

    @Test @Tag("P1")
    @Disabled("GAP-18 — guardian_student distinguishes primary/secondary and custody, but a non-guardian "
        + "authorised pickup contact cannot be modelled at all (Phase 8).")
    void cert_ENR_04_guardianAndPickupContactRolesAreDistinguishable() {
    }

    @Test @Tag("P2")
    @Disabled("GAP-18 — custody restrictions on pickup have nowhere to live (Phase 8).")
    void cert_ENR_05_separatedParentsBothReceiveCommsWithCustodyRulesEnforced() {
    }

    @Test @Tag("P1")
    @Disabled("GAP-17 — no health/emergency model: conditions, allergies and prioritised emergency contacts "
        + "are not stored, so they cannot reach the class teacher or the driver view (Phase 8).")
    void cert_ENR_06_healthAndEmergencyDataReachesTeacherAndDriver() {
    }

    @Test @Tag("P3")
    @Disabled("GAP-16 — no student document/photo store, so no ID-card generation for a section (Phase 8).")
    void cert_ENR_07_studentPhotoAndIdCardGenerationForASection() {
    }

    @Test @Tag("P2")
    void cert_ENR_08_withdrawnStudentIsReadmittedWithoutDuplicating() {
        var sandbox = rolloverSandbox("enr08");
        String token = sandboxToken(sandbox);
        UUID student = sandbox.firstStudent("R1", "A");
        try {
            UUID oldEnrolment = queryOne(
                "SELECT id FROM enrolment WHERE student_id = ? AND status = 'active'", UUID.class, student);
            var withdrawn = post("/v1/enrolment/" + oldEnrolment + "/status", body(
                "status", "withdrawn", "endsOn", "2026-08-15", "reason", "Family relocating"), token);
            assertThat(withdrawn.getStatusCode()).isEqualTo(HttpStatus.OK);

            // The year rolls over without them: a withdrawn child is not part of
            // the cohort being moved.
            var run = post("/v1/rollover/runs", body(
                "schoolId", sandbox.schoolId(), "fromAcademicYearId", sandbox.sourceAyId(),
                "toAcademicYearId", sandbox.targetAyId(), "runKey", "enr08",
                "startedByStaffId", sandbox.principalStaffId()), token);
            UUID runId = UUID.fromString(run.getBody().get("id").asText());
            post("/v1/rollover/runs/" + runId + "/clone-structure", null, token);
            post("/v1/rollover/runs/" + runId + "/allocate", null, token);
            post("/v1/rollover/runs/" + runId + "/commit", body(), token);
            post("/v1/rollover/runs/" + runId + "/activate",
                body("actingStaffId", sandbox.principalStaffId()), token);
            assertThat(count("SELECT count(*) FROM enrolment WHERE student_id = ? AND academic_year_id = ?",
                student, sandbox.targetAyId())).isZero();

            // They come back the next year. The family is re-admitted, not
            // re-created: same student row, same admission number, one history.
            UUID section = queryOne(
                "SELECT s.id FROM section s JOIN grade g ON g.id = s.grade_id " +
                "WHERE s.academic_year_id = ? AND g.code = 'R1' AND s.code = 'A'",
                UUID.class, sandbox.targetAyId());
            String admissionNo = queryOne("SELECT admission_no FROM student WHERE id = ?",
                String.class, student);

            var readmitted = post("/v1/enrolment", body(
                "schoolId", sandbox.schoolId(), "studentId", student, "sectionId", section,
                "academicYearId", sandbox.targetAyId(), "startsOn", "2026-09-01"), token);
            assertThat(readmitted.getStatusCode()).isEqualTo(HttpStatus.OK);

            assertThat(count("SELECT count(*) FROM student WHERE admission_no = ? AND school_id = ?",
                admissionNo, sandbox.schoolId())).isEqualTo(1);
            var history = get("/v1/enrolment/students/" + student, token).getBody();
            assertThat(history).hasSize(2);
            assertThat(queryOne("SELECT status FROM enrolment WHERE id = ?", String.class, oldEnrolment))
                .isEqualTo("withdrawn");
            assertThat(count("SELECT count(*) FROM enrolment WHERE student_id = ? AND status = 'active'",
                student)).isEqualTo(1);
        } finally {
            dropSandbox(sandbox);
        }
    }

    /**
     * The spreadsheet a school actually arrives with. Five hundred children,
     * two to a family, and a handful of rows wrong in the ways rows are wrong:
     * a section that does not exist, a date nobody can parse, an admission
     * number already taken, a parent with a name and no way to be reached.
     *
     * <p>Run against a school of its own. Five hundred children on Oakridge's
     * register would move every count the other fifty scenarios read.</p>
     */
    @Test @Tag("P1")
    void cert_ENR_09_bulkImportOf500StudentsValidatesPerRow() {
        var school = importSandbox();
        try {
            String token = school.token();

            // The file as it first arrives: right for 500 children, wrong in
            // four places.
            var broken = post("/v1/people/imports/students/preview", Map.of(
                "schoolId", school.schoolId(), "filename", "register.csv",
                "csv", registerCsv(school, true)), token);
            assertThat(broken.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(broken.getBody().get("rowCount").asInt()).isEqualTo(504);
            assertThat(broken.getBody().get("errorCount").asInt()).isEqualTo(4);
            assertThat(broken.getBody().get("canCommit").asBoolean()).isFalse();

            // Each bad row says what is wrong with it, and names its line.
            Map<Integer, String> problems = new java.util.HashMap<>();
            broken.getBody().get("rows").forEach(row -> {
                if (row.get("errors").size() > 0) {
                    problems.put(row.get("line").asInt(), row.get("errors").get(0).asText());
                }
            });
            assertThat(problems).hasSize(4);
            assertThat(String.join(" | ", problems.values()))
                .contains("no section 'Z'")
                .contains("is not a date")
                .contains("already belongs to a student here")
                .contains("phone or an email");

            // Nothing was written by a preview, however bad or good.
            assertThat(count("SELECT count(*) FROM student WHERE school_id = ?", school.schoolId())).isZero();

            UUID brokenBatch = UUID.fromString(broken.getBody().get("id").asText());
            var refused = post("/v1/people/imports/" + brokenBatch + "/commit", null, token);
            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(refused.getBody().get("message").asText()).contains("4 rows to fix");
            assertThat(count("SELECT count(*) FROM student WHERE school_id = ?", school.schoolId())).isZero();

            // The office fixes the file and previews again.
            var fixed = post("/v1/people/imports/students/preview", Map.of(
                "schoolId", school.schoolId(), "filename", "register.csv",
                "csv", registerCsv(school, false)), token);
            assertThat(fixed.getBody().get("rowCount").asInt()).isEqualTo(500);
            assertThat(fixed.getBody().get("errorCount").asInt()).isZero();
            assertThat(fixed.getBody().get("canCommit").asBoolean()).isTrue();

            UUID batchId = UUID.fromString(fixed.getBody().get("id").asText());
            var committed = post("/v1/people/imports/" + batchId + "/commit", null, token);
            assertThat(committed.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(committed.getBody().get("studentsCreated").asInt()).isEqualTo(500);
            assertThat(committed.getBody().get("enrolled").asInt()).isEqualTo(500);
            // Two children to a family: 250 parents, and the second child of
            // each is matched to the parent the first one created.
            assertThat(committed.getBody().get("guardiansCreated").asInt()).isEqualTo(250);
            assertThat(committed.getBody().get("guardiansReused").asInt()).isEqualTo(250);

            // No partial guardian orphans: every child is on the register and
            // has a family, and every family has a login.
            assertThat(count("SELECT count(*) FROM student WHERE school_id = ?", school.schoolId()))
                .isEqualTo(500);
            assertThat(count("SELECT count(*) FROM enrolment e JOIN student s ON s.id = e.student_id "
                + "WHERE s.school_id = ?", school.schoolId())).isEqualTo(500);
            assertThat(count("SELECT count(*) FROM student s WHERE s.school_id = ? AND NOT EXISTS ("
                + "SELECT 1 FROM guardian_student gs WHERE gs.student_id = s.id)", school.schoolId()))
                .isZero();
            assertThat(count("SELECT count(*) FROM guardian WHERE school_id = ?", school.schoolId()))
                .isEqualTo(250);
            assertThat(count("SELECT count(*) FROM user_account WHERE school_id = ? AND "
                + "subject_type = 'guardian'", school.schoolId())).isEqualTo(250);

            // Committing the same batch twice imports one register, not two.
            var again = post("/v1/people/imports/" + batchId + "/commit", null, token);
            assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(count("SELECT count(*) FROM student WHERE school_id = ?", school.schoolId()))
                .isEqualTo(500);
        } finally {
            dropImportSandbox(school);
        }
    }

    @Test @Tag("P2")
    void cert_ENR_10_studentSearchIsScopedToTheCallersSchool() {
        String cbseToken = principalToken(cbse());
        var hits = get("/v1/people/students?schoolId=" + cbse().id() + "&q=Student1", cbseToken).getBody();
        assertThat(hits).isNotEmpty();

        // Same query, but pointed at the other school in the chain: row-level security answers with nothing.
        var crossSchool = get("/v1/people/students?schoolId=" + cie().id() + "&q=Student1", cbseToken).getBody();
        assertThat(crossSchool).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    /** A school of its own, with room for five hundred children. */
    private record ImportSandbox(UUID schoolId, UUID staffId, UUID userId, String token) {}

    private ImportSandbox importSandbox() {
        var created = post("/v1/tenancy/schools", Map.of(
            "slug", "cert-import", "name", "Import Probe School", "boardCode", "CBSE", "stateCode", "KA"),
            chainAdminToken());
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID schoolId = UUID.fromString(created.getBody().get("id").asText());
        UUID staffId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        inChainDo(jdbc -> {
            jdbc.update("INSERT INTO campus (school_id, name, is_primary) VALUES (?, 'Main', TRUE)", schoolId);
            jdbc.update("INSERT INTO academic_year (school_id, code, starts_on, ends_on, is_current) " +
                "VALUES (?, '2026-27', '2026-06-01', '2027-03-31', TRUE)", schoolId);
            jdbc.update("INSERT INTO grade (school_id, code, name, sort_order) VALUES (?, '1', 'Grade 1', 1)",
                schoolId);
            // Two sections with room to spare: capacity is checked, and a file
            // that overfills one is a different scenario.
            for (String code : java.util.List.of("A", "B")) {
                jdbc.update("INSERT INTO section (school_id, grade_id, academic_year_id, code, name, " +
                    "  strategy_code, capacity) " +
                    "SELECT ?, g.id, ay.id, ?, 'Grade 1-' || ?, 'CBSE-CCE-2024', 300 " +
                    "FROM grade g, academic_year ay " +
                    "WHERE g.school_id = ? AND g.code = '1' AND ay.school_id = ? AND ay.is_current",
                    schoolId, code, code, schoolId, schoolId);
            }
            jdbc.update("INSERT INTO staff (id, school_id, employee_no, first_name, last_name, email, " +
                "  employment_type, joined_on) " +
                "VALUES (?, ?, 'EMP-IMPORT', 'Import', 'Registrar', ?, 'permanent', '2026-05-01')",
                staffId, schoolId, "import.registrar+" + staffId + "@oakridge.test");
            jdbc.update("INSERT INTO user_account (id, school_id, subject_type, subject_id, email) " +
                "VALUES (?, ?, 'staff', ?, ?)",
                userId, schoolId, staffId, "import.registrar+" + staffId + "@oakridge.test");
            jdbc.update("INSERT INTO staff_role (staff_id, role_code, scope_type, scope_id) " +
                "VALUES (?, 'registrar', 'school', ?)", staffId, schoolId);
        });

        String token = jwt.issueAccess(userId, seed.chainId().toString(), seed.chainSchema(), schoolId, "staff");
        return new ImportSandbox(schoolId, staffId, userId, token);
    }

    /**
     * Five hundred children, two to a family. {@code withErrors} adds the four
     * bad rows a real file arrives with.
     */
    private String registerCsv(ImportSandbox school, boolean withErrors) {
        StringBuilder csv = new StringBuilder(
            "admission_no,First Name,last_name,dob,gender,grade_code,section_code,"
            + "guardian_name,guardian_relation,guardian_phone,guardian_email\n");
        for (int i = 1; i <= 500; i++) {
            int family = (i + 1) / 2;
            csv.append("IMP-").append(String.format("%04d", i)).append(',')
               .append("Child").append(i).append(',')
               // A comma inside a quoted field, which is what a spreadsheet exports.
               .append("\"Kumar, ").append(family).append("\"").append(',')
               .append("2015-0").append((i % 9) + 1).append("-1").append(i % 9).append(',')
               .append(i % 2 == 0 ? "female" : "male").append(',')
               .append("1,").append(i <= 250 ? "A" : "B").append(',')
               .append("Parent ").append(family).append(",mother,")
               .append("+9199000").append(String.format("%04d", family)).append(',')
               .append("import.parent").append(family).append("@probe.test")
               .append('\n');
        }
        if (withErrors) {
            csv.append("IMP-9001,Wrong Section,Kumar,2015-01-01,male,1,Z,Parent X,mother,+919900109001,x1@probe.test\n");
            csv.append("IMP-9002,Bad Date,Kumar,05/06/2015,male,1,A,Parent Y,mother,+919900109002,x2@probe.test\n");
            csv.append("IMP-0001,Duplicate Number,Kumar,2015-01-01,male,1,A,Parent Z,mother,+919900109003,x3@probe.test\n");
            csv.append("IMP-9004,No Way To Reach,Kumar,2015-01-01,male,1,A,Parent W,mother,,\n");
        }
        return csv.toString();
    }

    private void dropImportSandbox(ImportSandbox school) {
        inChainDo(jdbc -> {
            jdbc.update("DELETE FROM enrolment WHERE school_id = ?", school.schoolId());
            jdbc.update("DELETE FROM guardian_student WHERE student_id IN "
                + "(SELECT id FROM student WHERE school_id = ?)", school.schoolId());
            jdbc.update("DELETE FROM user_account WHERE school_id = ?", school.schoolId());
            jdbc.update("DELETE FROM guardian WHERE school_id = ?", school.schoolId());
            jdbc.update("DELETE FROM student WHERE school_id = ?", school.schoolId());
            jdbc.update("DELETE FROM import_batch WHERE school_id = ?", school.schoolId());
            jdbc.update("DELETE FROM number_series WHERE school_id = ?", school.schoolId());
            jdbc.update("DELETE FROM staff_role WHERE staff_id = ?", school.staffId());
            jdbc.update("DELETE FROM staff WHERE school_id = ?", school.schoolId());
            jdbc.update("DELETE FROM school WHERE id = ?", school.schoolId());
        });
    }

    private UUID createStudent(String admissionNo) {
        var created = post("/v1/people/students", Map.of(
            "schoolId", cbse().id(), "admissionNo", admissionNo,
            "firstName", "Certification", "lastName", "Candidate",
            "dob", "2015-05-05", "gender", "male"), principalToken(cbse()));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        return UUID.fromString(created.getBody().get("id").asText());
    }
}
