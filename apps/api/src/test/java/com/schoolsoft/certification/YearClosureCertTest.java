package com.schoolsoft.certification;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.schoolsoft.certification.support.AbstractCertificationTest;
import com.schoolsoft.certification.support.RolloverSandbox;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * CERT-YEC — academic year closure & rollover.
 *
 * Rollover is the one capability that cannot be exercised against the shared
 * fixture: it closes a year and re-enrols every child, which would take the
 * rest of the suite with it. Each scenario therefore builds its own small
 * school ({@link RolloverSandbox}) — three grades, two sections, thirty
 * children, seeded ready to close — and rolls that.
 */
class YearClosureCertTest extends AbstractCertificationTest {

    // ------------------------------------------------------------- YEC-01

    @Test @Tag("P1")
    void cert_YEC_01_readinessCheckListsEverythingBlockingClosure() {
        var sandbox = rolloverSandbox("yec01");
        String token = sandboxToken(sandbox);
        String path = "/v1/rollover/readiness?schoolId=" + sandbox.schoolId()
            + "&academicYearId=" + sandbox.sourceAyId();
        try {
            // Seeded ready: every assessment published, every card locked with a
            // decision, the register complete, nothing owed.
            var clean = get(path, token);
            assertThat(clean.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(clean.getBody().get("ready").asBoolean()).isTrue();
            assertThat(clean.getBody().get("activeEnrolments").asInt()).isEqualTo(30);

            UUID unlockedStudent = sandbox.firstStudent("R1", "A");
            UUID undecidedStudent = sandbox.firstStudent("R1", "B");
            inChainDo(jdbc -> {
                jdbc.update("UPDATE assessment SET status = 'marking' WHERE section_id = ?",
                    sandbox.section("R2", "A"));
                jdbc.update("UPDATE report_card SET status = 'draft', is_locked = FALSE WHERE student_id = ?",
                    unlockedStudent);
                jdbc.update("UPDATE report_card SET promotion_decision = NULL WHERE student_id = ?",
                    undecidedStudent);
                jdbc.update("DELETE FROM attendance_record WHERE section_id = ? AND on_date = '2026-07-07'",
                    sandbox.section("R3", "B"));
            });
            var invoice = post("/v1/fees/invoices", body(
                "schoolId", sandbox.schoolId(), "studentId", sandbox.firstStudent("R2", "B"),
                "invoiceNo", "YEC01-" + UUID.randomUUID().toString().substring(0, 8),
                "cycleLabel", "Term 1", "dueOn", "2026-08-20",
                "lines", List.of(body("feeHeadId", sandbox.feeHeadId(), "description", "Tuition",
                    "amount", 5000.0, "discount", 0.0, "gst", 0.0))), token);
            assertThat(invoice.getStatusCode()).isEqualTo(HttpStatus.OK);

            var blocked = get(path, token).getBody();
            assertThat(blocked.get("ready").asBoolean()).isFalse();
            assertThat(blocked.get("unpublishedAssessments").asInt()).isEqualTo(1);
            assertThat(blocked.get("unlockedReportCards").asInt()).isEqualTo(1);
            assertThat(blocked.get("missingPromotionDecisions").asInt()).isEqualTo(1);
            assertThat(blocked.get("unmarkedAttendanceDays").asInt()).isEqualTo(1);
            assertThat(blocked.get("studentsWithDues").asInt()).isEqualTo(1);
            assertThat(blocked.get("outstandingTotal").asDouble()).isEqualTo(5000.0);

            // Every count is also a list: the office needs the names, not a number.
            assertThat(kinds(blocked.get("items"))).contains(
                "unpublished_assessment", "unlocked_report_card", "missing_promotion_decision",
                "unmarked_attendance_day", "outstanding_dues");
        } finally {
            dropSandbox(sandbox);
        }
    }

    // ------------------------------------------------------------- YEC-02

    @Test @Tag("P1")
    void cert_YEC_02_nextYearStructureIsClonedAndEditableBeforeActivation() {
        var sandbox = rolloverSandbox("yec02");
        String token = sandboxToken(sandbox);
        try {
            UUID runId = startRun(sandbox, token);
            var cloned = post("/v1/rollover/runs/" + runId + "/clone-structure", null, token);
            assertThat(cloned.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(cloned.getBody().get("state").asText()).isEqualTo("structure_cloned");
            assertThat(cloned.getBody().get("stats").get("sectionsCloned").asInt()).isEqualTo(6);
            assertThat(cloned.getBody().get("stats").get("feeStructuresCloned").asInt()).isEqualTo(3);

            assertThat(count("SELECT count(*) FROM section WHERE academic_year_id = ?", sandbox.targetAyId()))
                .isEqualTo(6);
            assertThat(count("SELECT count(*) FROM section WHERE academic_year_id = ? " +
                "  AND source_section_id IS NOT NULL", sandbox.targetAyId())).isEqualTo(6);
            // Capacity and curriculum come across; the name follows the new year.
            assertThat(queryOne("SELECT capacity FROM section WHERE academic_year_id = ? AND code = 'A' " +
                "  AND grade_id = (SELECT id FROM grade WHERE code = 'R1' AND school_id = ?)",
                Integer.class, sandbox.targetAyId(), sandbox.schoolId()))
                .isEqualTo(RolloverSandbox.SECTION_CAPACITY);
            assertThat(queryOne("SELECT name FROM section WHERE academic_year_id = ? AND code = 'A' " +
                "  AND grade_id = (SELECT id FROM grade WHERE code = 'R1' AND school_id = ?)",
                String.class, sandbox.targetAyId(), sandbox.schoolId())).contains("S2");

            // Still in planning, and still editable: a school reorganises before
            // the children arrive, not after.
            assertThat(queryOne("SELECT status FROM academic_year WHERE id = ?", String.class,
                sandbox.targetAyId())).isEqualTo("planning");
            var extra = post("/v1/tenancy/schools/" + sandbox.schoolId() + "/sections", body(
                "gradeId", sandbox.grades().get("R2"), "academicYearId", sandbox.targetAyId(),
                "code", "C", "name", "Grade R2-C S2", "strategyCode", "CBSE-CCE-2024",
                "capacity", 10), token);
            assertThat(extra.getStatusCode()).isEqualTo(HttpStatus.OK);

            // Cloning twice adds nothing — the wizard's button gets pressed twice.
            var again = post("/v1/rollover/runs/" + runId + "/clone-structure", null, token);
            assertThat(again.getBody().get("stats").get("sectionsCloned").asInt()).isZero();
            assertThat(count("SELECT count(*) FROM section WHERE academic_year_id = ?", sandbox.targetAyId()))
                .isEqualTo(7);
        } finally {
            dropSandbox(sandbox);
        }
    }

    // ------------------------------------------------------------- YEC-03

    @Test @Tag("P1")
    void cert_YEC_03_bulkPromotionMovesTheCohortAndClosesOldEnrolments() {
        var sandbox = rolloverSandbox("yec03");
        String token = sandboxToken(sandbox);
        try {
            UUID runId = rollTo(sandbox, token, "allocated");
            var run = get("/v1/rollover/runs/" + runId, token).getBody();
            assertThat(run.get("stats").get("promoting").asInt()).isEqualTo(20);   // R1 and R2
            assertThat(run.get("stats").get("graduating").asInt()).isEqualTo(10);  // the top grade
            assertThat(run.get("stats").get("unplaced").asInt()).isZero();

            var committed = post("/v1/rollover/runs/" + runId + "/commit", body(), token);
            assertThat(committed.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(committed.getBody().get("applied").asInt()).isEqualTo(30);
            assertThat(committed.getBody().get("sourceYearClosed").asBoolean()).isTrue();

            UUID student = sandbox.firstStudent("R1", "A");
            assertThat(queryOne(
                "SELECT g.code FROM enrolment e JOIN section s ON s.id = e.section_id " +
                "JOIN grade g ON g.id = s.grade_id WHERE e.student_id = ? AND e.status = 'active'",
                String.class, student)).isEqualTo("R2");
            assertThat(queryOne("SELECT academic_year_id FROM enrolment WHERE student_id = ? AND status = 'active'",
                UUID.class, student)).isEqualTo(sandbox.targetAyId());

            // The class stays together: 5A's children are 6A's children.
            assertThat(queryOne(
                "SELECT s.code FROM enrolment e JOIN section s ON s.id = e.section_id " +
                "WHERE e.student_id = ? AND e.status = 'active'", String.class, student)).isEqualTo("A");

            // The old enrolment says what happened to it, and when.
            assertThat(queryOne("SELECT status FROM enrolment WHERE student_id = ? AND academic_year_id = ?",
                String.class, student, sandbox.sourceAyId())).isEqualTo("promoted");
            assertThat(queryOne("SELECT ends_on::text FROM enrolment WHERE student_id = ? AND academic_year_id = ?",
                String.class, student, sandbox.sourceAyId())).isEqualTo("2026-08-31");

            assertThat(count("SELECT count(*) FROM enrolment WHERE academic_year_id = ? AND status = 'active'",
                sandbox.targetAyId())).isEqualTo(20);
            assertThat(queryOne("SELECT status FROM academic_year WHERE id = ?", String.class,
                sandbox.sourceAyId())).isEqualTo("closed");
        } finally {
            dropSandbox(sandbox);
        }
    }

    // ------------------------------------------------------------- YEC-04

    @Test @Tag("P1")
    void cert_YEC_04_detainedStudentStaysInGradeWithPreservedHistory() {
        var sandbox = rolloverSandbox("yec04");
        String token = sandboxToken(sandbox);
        UUID detained = sandbox.firstStudent("R2", "A");
        try {
            inChainDo(jdbc -> jdbc.update(
                "UPDATE report_card SET promotion_decision = 'detain' WHERE student_id = ?", detained));

            UUID runId = rollTo(sandbox, token, "allocated");
            post("/v1/rollover/runs/" + runId + "/commit", body(), token);

            assertThat(queryOne(
                "SELECT g.code FROM enrolment e JOIN section s ON s.id = e.section_id " +
                "JOIN grade g ON g.id = s.grade_id WHERE e.student_id = ? AND e.status = 'active'",
                String.class, detained)).isEqualTo("R2");
            assertThat(queryOne(
                "SELECT status FROM enrolment WHERE student_id = ? AND academic_year_id = ?",
                String.class, detained, sandbox.sourceAyId())).isEqualTo("detained");

            // Repeating a year is not the same as being promoted, and the history
            // has to say so a year later when somebody asks.
            var history = get("/v1/enrolment/students/" + detained, token).getBody();
            assertThat(history).hasSize(2);
            assertThat(queryOne("SELECT count(*) FROM student WHERE id = ?", Integer.class, detained))
                .isEqualTo(1);

            // Their classmates went up; only they stayed.
            UUID classmate = sandbox.students("R2", "A").get(1);
            assertThat(queryOne(
                "SELECT g.code FROM enrolment e JOIN section s ON s.id = e.section_id " +
                "JOIN grade g ON g.id = s.grade_id WHERE e.student_id = ? AND e.status = 'active'",
                String.class, classmate)).isEqualTo("R3");
        } finally {
            dropSandbox(sandbox);
        }
    }

    // ------------------------------------------------------------- YEC-05

    @Test @Tag("P2")
    void cert_YEC_05_sectionReshuffleRespectsCapacityAndSiblingPolicy() {
        var sandbox = rolloverSandbox("yec05");
        String token = sandboxToken(sandbox);
        try {
            UUID runId = startRun(sandbox, token);
            post("/v1/rollover/runs/" + runId + "/clone-structure", null, token);

            // Next year's R2 is reorganised: A holds three, the rest go to B.
            // Ten children are coming up from R1.
            inChainDo(jdbc -> jdbc.update(
                "UPDATE section SET capacity = 3 WHERE academic_year_id = ? AND code = 'A' " +
                "  AND grade_id = (SELECT id FROM grade WHERE code = 'R2' AND school_id = ?)",
                sandbox.targetAyId(), sandbox.schoolId()));

            post("/v1/rollover/runs/" + runId + "/allocate", null, token);
            post("/v1/rollover/runs/" + runId + "/commit", body(), token);

            long inA = count(
                "SELECT count(*) FROM enrolment e JOIN section s ON s.id = e.section_id " +
                "WHERE e.academic_year_id = ? AND e.status = 'active' AND s.code = 'A' " +
                "  AND s.grade_id = (SELECT id FROM grade WHERE code = 'R2' AND school_id = ?)",
                sandbox.targetAyId(), sandbox.schoolId());
            assertThat(inA).isLessThanOrEqualTo(3);              // capacity is respected
            assertThat(count(
                "SELECT count(*) FROM enrolment e JOIN section s ON s.id = e.section_id " +
                "WHERE e.academic_year_id = ? AND e.status = 'active' " +
                "  AND s.grade_id = (SELECT id FROM grade WHERE code = 'R2' AND school_id = ?)",
                sandbox.targetAyId(), sandbox.schoolId())).isEqualTo(10);   // everybody still placed

            // The twins came from different sections and land in the same one:
            // a household organises one school run, not two.
            UUID twinA = sandbox.firstStudent("R1", "A");
            UUID twinB = sandbox.firstStudent("R1", "B");
            UUID sectionA = queryOne(
                "SELECT section_id FROM enrolment WHERE student_id = ? AND status = 'active'",
                UUID.class, twinA);
            UUID sectionB = queryOne(
                "SELECT section_id FROM enrolment WHERE student_id = ? AND status = 'active'",
                UUID.class, twinB);
            assertThat(sectionA).isEqualTo(sectionB);
        } finally {
            dropSandbox(sandbox);
        }
    }

    // ------------------------------------------------------------- YEC-06

    @Test @Tag("P1")
    void cert_YEC_06_rolloverCarriesForwardDuesAssignmentsAndLinks() {
        var sandbox = rolloverSandbox("yec06");
        String token = sandboxToken(sandbox);
        UUID student = sandbox.firstStudent("R1", "A");
        try {
            // An unpaid bill, a bus seat, an elective, and a guardian.
            var invoice = post("/v1/fees/invoices", body(
                "schoolId", sandbox.schoolId(), "studentId", student,
                "invoiceNo", "YEC06-" + UUID.randomUUID().toString().substring(0, 8),
                "cycleLabel", "Term 1", "dueOn", "2026-08-20",
                "lines", List.of(body("feeHeadId", sandbox.feeHeadId(), "description", "Tuition",
                    "amount", 4000.0, "discount", 0.0, "gst", 0.0))), token).getBody();
            UUID invoiceId = UUID.fromString(invoice.get("id").asText());
            post("/v1/fees/payments", body(
                "schoolId", sandbox.schoolId(), "feeInvoiceId", invoiceId, "amount", 1500.0,
                "gateway", "manual", "method", "cash", "idempotencyKey", "yec06-" + UUID.randomUUID()), token);

            post("/v1/transport/student-assignments", body(
                "schoolId", sandbox.schoolId(), "studentId", student, "routeId", sandbox.routeId(),
                "stopId", sandbox.stopId(), "startsOn", "2026-06-01"), token);

            UUID enrolmentId = queryOne(
                "SELECT id FROM enrolment WHERE student_id = ? AND status = 'active'", UUID.class, student);
            inChainDo(jdbc -> jdbc.update(
                "INSERT INTO student_subject (id, school_id, enrolment_id, subject_id, status, effective_from) " +
                "VALUES (gen_random_uuid(), ?, ?, ?, 'elected', '2026-06-01')",
                sandbox.schoolId(), enrolmentId, sandbox.electiveSubjectId()));

            UUID runId = rollTo(sandbox, token, "allocated");
            var result = post("/v1/rollover/runs/" + runId + "/commit", body(), token).getBody();
            assertThat(result.get("arrearsCarried").asDouble()).isEqualTo(2500.0);

            // The arrear is now next year's opening balance, and last year's
            // invoice is marked as moved rather than left to be counted twice.
            UUID opening = queryOne(
                "SELECT id FROM fee_invoice WHERE student_id = ? AND academic_year_id = ?",
                UUID.class, student, sandbox.targetAyId());
            assertThat(queryOne("SELECT total FROM fee_invoice WHERE id = ?", Double.class, opening))
                .isEqualTo(2500.0);
            assertThat(queryOne("SELECT cycle_label FROM fee_invoice WHERE id = ?", String.class, opening))
                .isEqualTo("Opening balance S1");
            assertThat(queryOne("SELECT status FROM fee_invoice WHERE id = ?", String.class, invoiceId))
                .isEqualTo("carried_forward");

            UUID newEnrolment = queryOne(
                "SELECT id FROM enrolment WHERE student_id = ? AND status = 'active'", UUID.class, student);
            assertThat(newEnrolment).isNotEqualTo(enrolmentId);

            // The bus seat and the elective follow the child into the new year.
            assertThat(count(
                "SELECT count(*) FROM student_transport WHERE student_id = ? AND starts_on = '2026-09-01' " +
                "  AND ends_on IS NULL", student)).isEqualTo(1);
            assertThat(count(
                "SELECT count(*) FROM student_subject WHERE enrolment_id = ? AND subject_id = ? " +
                "  AND status = 'elected'", newEnrolment, sandbox.electiveSubjectId())).isEqualTo(1);

            // Guardian links hang off the student, so a year boundary never
            // touches them — the test is here because the family notices if it does.
            assertThat(count("SELECT count(*) FROM guardian_student WHERE student_id = ?", student))
                .isEqualTo(1);
        } finally {
            dropSandbox(sandbox);
        }
    }

    // ------------------------------------------------------------- YEC-07

    @Test @Tag("P1")
    void cert_YEC_07_rolloverIsIdempotentAndReversible() {
        var sandbox = rolloverSandbox("yec07");
        String token = sandboxToken(sandbox);
        try {
            // A small batch size, so the first commit deliberately stops half way.
            var run = post("/v1/rollover/runs", body(
                "schoolId", sandbox.schoolId(), "fromAcademicYearId", sandbox.sourceAyId(),
                "toAcademicYearId", sandbox.targetAyId(), "runKey", "yec07",
                "batchSize", 5, "startedByStaffId", sandbox.principalStaffId()), token);
            UUID runId = UUID.fromString(run.getBody().get("id").asText());

            // Starting again with the same key is the same run, not a second one.
            var again = post("/v1/rollover/runs", body(
                "schoolId", sandbox.schoolId(), "fromAcademicYearId", sandbox.sourceAyId(),
                "toAcademicYearId", sandbox.targetAyId(), "runKey", "yec07"), token);
            assertThat(UUID.fromString(again.getBody().get("id").asText())).isEqualTo(runId);

            post("/v1/rollover/runs/" + runId + "/clone-structure", null, token);
            post("/v1/rollover/runs/" + runId + "/allocate", null, token);

            // An interrupted run: one batch applied, the rest still to do, and
            // the year it came from still open.
            var partial = post("/v1/rollover/runs/" + runId + "/commit",
                body("maxBatches", 1), token).getBody();
            assertThat(partial.get("applied").asInt()).isEqualTo(5);
            assertThat(partial.get("remaining").asInt()).isEqualTo(25);
            assertThat(partial.get("sourceYearClosed").asBoolean()).isFalse();
            assertThat(queryOne("SELECT status FROM academic_year WHERE id = ?", String.class,
                sandbox.sourceAyId())).isEqualTo("active");

            // Resuming picks up where it stopped rather than starting over.
            var rest = post("/v1/rollover/runs/" + runId + "/commit", body(), token).getBody();
            assertThat(rest.get("applied").asInt()).isEqualTo(25);
            assertThat(rest.get("sourceYearClosed").asBoolean()).isTrue();
            assertThat(count("SELECT count(*) FROM enrolment WHERE academic_year_id = ? AND status = 'active'",
                sandbox.targetAyId())).isEqualTo(20);

            // And committing a third time moves nobody: no child is enrolled twice.
            var repeat = post("/v1/rollover/runs/" + runId + "/commit", body(), token).getBody();
            assertThat(repeat.get("applied").asInt()).isZero();
            assertThat(count("SELECT count(*) FROM enrolment WHERE academic_year_id = ? AND status = 'active'",
                sandbox.targetAyId())).isEqualTo(20);

            // A mistaken rollover is undone while the new year is still planned:
            // the new enrolments go, the old ones come back, the year reopens.
            var rolledBack = post("/v1/rollover/runs/" + runId + "/rollback", body(
                "reason", "Wrong promotion list", "actingStaffId", sandbox.principalStaffId()), token);
            assertThat(rolledBack.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(rolledBack.getBody().get("state").asText()).isEqualTo("rolled_back");
            assertThat(count("SELECT count(*) FROM enrolment WHERE academic_year_id = ?",
                sandbox.targetAyId())).isZero();
            assertThat(count("SELECT count(*) FROM enrolment WHERE academic_year_id = ? AND status = 'active'",
                sandbox.sourceAyId())).isEqualTo(30);
            assertThat(queryOne("SELECT status FROM academic_year WHERE id = ?", String.class,
                sandbox.sourceAyId())).isEqualTo("active");

            // A rolled-back run is spent: it cannot be activated afterwards.
            assertThat(post("/v1/rollover/runs/" + runId + "/activate", body(), token).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

            // Second attempt, done properly, and then activated — after which
            // undoing it is refused, because a live year is being written to.
            var second = post("/v1/rollover/runs", body(
                "schoolId", sandbox.schoolId(), "fromAcademicYearId", sandbox.sourceAyId(),
                "toAcademicYearId", sandbox.targetAyId(), "runKey", "yec07-second",
                "startedByStaffId", sandbox.principalStaffId()), token);
            UUID secondId = UUID.fromString(second.getBody().get("id").asText());
            post("/v1/rollover/runs/" + secondId + "/clone-structure", null, token);
            post("/v1/rollover/runs/" + secondId + "/allocate", null, token);
            post("/v1/rollover/runs/" + secondId + "/commit", body(), token);

            var activated = post("/v1/rollover/runs/" + secondId + "/activate",
                body("actingStaffId", sandbox.principalStaffId()), token);
            assertThat(activated.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(queryOne("SELECT is_current FROM academic_year WHERE id = ?", Boolean.class,
                sandbox.targetAyId())).isTrue();

            var tooLate = post("/v1/rollover/runs/" + secondId + "/rollback", body(
                "reason", "Changed our minds", "actingStaffId", sandbox.principalStaffId()), token);
            assertThat(tooLate.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        } finally {
            dropSandbox(sandbox);
        }
    }

    // ------------------------------------------------------------- YEC-08

    @Test @Tag("P1")
    void cert_YEC_08_closedYearIsReadOnlyWithoutAnAuthorisedReopen() {
        String token = principalToken(cbse());
        UUID priorAy = cbse().priorAy().id();
        UUID priorSection = priorFocusSection(cbse());
        UUID studentId = firstStudentIn(priorSection);
        String priorDate = "2025-07-07";                        // a Monday in the prior year's history
        String statusPath = "/v1/tenancy/academic-years/" + priorAy + "/status";

        UUID priorComponent = queryOne(
            "SELECT ac.id FROM assessment_component ac JOIN assessment a ON a.id = ac.assessment_id " +
            "WHERE a.section_id = ? LIMIT 1", UUID.class, priorSection);
        UUID priorInvoice = queryOne(
            "SELECT fi.id FROM fee_invoice fi JOIN academic_year ay ON ay.school_id = fi.school_id " +
            "WHERE ay.id = ? AND fi.issued_on BETWEEN ay.starts_on AND ay.ends_on " +
            "  AND fi.student_id = ? LIMIT 1", UUID.class, priorAy, studentId);

        var closed = post(statusPath, body(
            "status", "closed", "actingStaffId", cbse().principalStaffId(),
            "reason", "Year-end closure"), token);
        assertThat(closed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(closed.getBody().get("status").asText()).isEqualTo("closed");

        try {
            // Attendance, marks and fees for the closed year all refuse the write.
            var attendance = post("/v1/attendance/mark", body(
                "schoolId", cbse().id(), "studentId", studentId, "sectionId", priorSection,
                "onDate", priorDate, "status", "absent"), token);
            assertThat(attendance.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

            var mark = post("/v1/assessment/components/" + priorComponent + "/marks", body(
                "schoolId", cbse().id(), "studentId", studentId, "rawMarks", 91.0,
                "enteredByStaffId", cbse().teacherStaffIds().get(0)), token);
            assertThat(mark.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

            var payment = post("/v1/fees/payments", body(
                "schoolId", cbse().id(), "feeInvoiceId", priorInvoice, "amount", 100.0,
                "gateway", "manual", "method", "cash",
                "idempotencyKey", "yec08-" + UUID.randomUUID()), accountantToken(cbse()));
            assertThat(payment.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

            // Reading it is still fine — closure is read-only, not invisible.
            assertThat(get("/v1/attendance/students/" + studentId
                + "?from=2025-07-01&to=2025-07-31", token).getStatusCode()).isEqualTo(HttpStatus.OK);

            // Reopening demands a reason, and records who did it.
            var unreasoned = post(statusPath, body(
                "status", "active", "actingStaffId", cbse().principalStaffId()), token);
            assertThat(unreasoned.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

            var reopened = post(statusPath, body(
                "status", "active", "actingStaffId", cbse().principalStaffId(),
                "reason", "Board asked for a corrected mark"), token);
            assertThat(reopened.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(reopened.getBody().get("status").asText()).isEqualTo("active");
            assertThat(reopened.getBody().get("reopenReason").asText())
                .isEqualTo("Board asked for a corrected mark");
            assertThat(queryOne("SELECT reopened_by_staff_id FROM academic_year WHERE id = ?",
                UUID.class, priorAy)).isEqualTo(cbse().principalStaffId());

            var afterReopen = post("/v1/attendance/mark", body(
                "schoolId", cbse().id(), "studentId", studentId, "sectionId", priorSection,
                "onDate", priorDate, "status", "absent"), token);
            assertThat(afterReopen.getStatusCode()).isEqualTo(HttpStatus.OK);

            assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'academic_year.status_changed' "
                + "AND target_id = ?", priorAy)).isGreaterThanOrEqualTo(2);
        } finally {
            inChainDo(jdbc -> jdbc.update(
                "UPDATE academic_year SET status = 'active', closed_at = NULL, closed_by_staff_id = NULL, " +
                "  reopened_at = NULL, reopened_by_staff_id = NULL, reopen_reason = NULL WHERE id = ?", priorAy));
        }
    }

    // ------------------------------------------------------------- YEC-09

    @Test @Tag("P1")
    void cert_YEC_09_historicalReportingSurvivesTwoRollovers() {
        var sandbox = rolloverSandbox("yec09");
        String token = sandboxToken(sandbox);
        UUID student = sandbox.firstStudent("R1", "A");
        try {
            // Year one → year two.
            UUID first = rollTo(sandbox, token, "allocated");
            post("/v1/rollover/runs/" + first + "/commit", body(), token);
            post("/v1/rollover/runs/" + first + "/activate",
                body("actingStaffId", sandbox.principalStaffId()), token);

            // Year two's cards, so the second rollover has decisions to act on.
            inChainDo(jdbc -> jdbc.update(
                "INSERT INTO report_card (id, school_id, student_id, academic_year_id, strategy_code, " +
                "  template_code, payload, promotion_decision, status, is_locked) " +
                "SELECT gen_random_uuid(), e.school_id, e.student_id, e.academic_year_id, 'CBSE-CCE-2024', " +
                "       'annual', '{}'::jsonb, 'promote', 'locked', TRUE " +
                "FROM enrolment e WHERE e.academic_year_id = ? AND e.status = 'active'",
                sandbox.targetAyId()));

            // Year two → year three.
            var second = post("/v1/rollover/runs", body(
                "schoolId", sandbox.schoolId(), "fromAcademicYearId", sandbox.targetAyId(),
                "toAcademicYearId", sandbox.thirdAyId(), "runKey", "yec09-second",
                "startedByStaffId", sandbox.principalStaffId()), token);
            UUID secondId = UUID.fromString(second.getBody().get("id").asText());
            post("/v1/rollover/runs/" + secondId + "/clone-structure", null, token);
            post("/v1/rollover/runs/" + secondId + "/allocate", null, token);
            var committed = post("/v1/rollover/runs/" + secondId + "/commit", body(), token);
            assertThat(committed.getBody().get("sourceYearClosed").asBoolean()).isTrue();
            post("/v1/rollover/runs/" + secondId + "/activate",
                body("actingStaffId", sandbox.principalStaffId()), token);

            // Two boundaries later, the first year still reads: the child's
            // history, their report card, and that year's attendance.
            var history = get("/v1/enrolment/students/" + student, token).getBody();
            assertThat(history).hasSize(3);
            assertThat(count("SELECT count(*) FROM report_card WHERE student_id = ? AND academic_year_id = ?",
                student, sandbox.sourceAyId())).isEqualTo(1);

            var cards = get("/v1/assessment/report-cards/students/" + student, token);
            assertThat(cards.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(cards.getBody().size()).isGreaterThanOrEqualTo(2);

            var attendance = get("/v1/attendance/students/" + student
                + "/summary?from=2026-06-01&to=2026-08-31", token);
            assertThat(attendance.getStatusCode()).isEqualTo(HttpStatus.OK);

            var dayBook = get("/v1/fees/reports/day-book?schoolId=" + sandbox.schoolId()
                + "&from=2026-06-01&to=2026-08-31", token);
            assertThat(dayBook.getStatusCode()).isEqualTo(HttpStatus.OK);

            // Both closed years stayed closed; only the newest is live.
            assertThat(queryOne("SELECT status FROM academic_year WHERE id = ?", String.class,
                sandbox.sourceAyId())).isEqualTo("closed");
            assertThat(queryOne("SELECT status FROM academic_year WHERE id = ?", String.class,
                sandbox.targetAyId())).isEqualTo("closed");
            assertThat(queryOne("SELECT is_current FROM academic_year WHERE id = ?", Boolean.class,
                sandbox.thirdAyId())).isTrue();
        } finally {
            dropSandbox(sandbox);
        }
    }

    // ------------------------------------------------------------- YEC-10

    @Test @Tag("P2")
    void cert_YEC_10_teacherAssignmentsDoNotSilentlyCarryForward() {
        var sandbox = rolloverSandbox("yec10");
        String token = sandboxToken(sandbox);
        try {
            assertThat(count(
                "SELECT count(*) FROM section_subject_teacher sst JOIN section s ON s.id = sst.section_id " +
                "WHERE s.academic_year_id = ?", sandbox.sourceAyId())).isEqualTo(6);

            UUID runId = rollTo(sandbox, token, "allocated");
            post("/v1/rollover/runs/" + runId + "/commit", body(), token);

            // Who teaches next year's R2 is a decision somebody has to make.
            assertThat(count(
                "SELECT count(*) FROM section_subject_teacher sst JOIN section s ON s.id = sst.section_id " +
                "WHERE s.academic_year_id = ?", sandbox.targetAyId())).isZero();

            // And making it is an ordinary assignment, not a rollover feature.
            UUID targetSection = queryOne(
                "SELECT s.id FROM section s JOIN grade g ON g.id = s.grade_id " +
                "WHERE s.academic_year_id = ? AND g.code = 'R2' AND s.code = 'A'",
                UUID.class, sandbox.targetAyId());
            var assigned = post("/v1/tenancy/sections/" + targetSection + "/teachers", body(
                "subjectId", sandbox.subjectId(), "teacherStaffId", sandbox.teacherStaffId(),
                "isPrimary", true, "isElective", false), token);
            assertThat(assigned.getStatusCode()).isEqualTo(HttpStatus.OK);
        } finally {
            dropSandbox(sandbox);
        }
    }

    // ------------------------------------------------------------- YEC-11

    @Test @Tag("P2")
    @Disabled("Perf profile: rollover is restartable as of Phase 6 (proved batch-by-batch in YEC-07), but "
        + "the timing half needs the bulk seed (-Dschoolsoft.cert.bulk-students=2000) and an agreed window.")
    void cert_YEC_11_twoThousandStudentRolloverCompletesAndIsRestartable() {
    }

    // ------------------------------------------------------------- helpers

    /** Starts a run from the sandbox's first year into its second. */
    private UUID startRun(RolloverSandbox.Sandbox sandbox, String token) {
        var run = post("/v1/rollover/runs", body(
            "schoolId", sandbox.schoolId(), "fromAcademicYearId", sandbox.sourceAyId(),
            "toAcademicYearId", sandbox.targetAyId(), "runKey", "cert-" + sandbox.slug(),
            "startedByStaffId", sandbox.principalStaffId()), token);
        assertThat(run.getStatusCode()).isEqualTo(HttpStatus.OK);
        return UUID.fromString(run.getBody().get("id").asText());
    }

    /** Start → clone → allocate, stopping at the state asked for. */
    private UUID rollTo(RolloverSandbox.Sandbox sandbox, String token, String state) {
        UUID runId = startRun(sandbox, token);
        post("/v1/rollover/runs/" + runId + "/clone-structure", null, token);
        if ("structure_cloned".equals(state)) return runId;
        var allocated = post("/v1/rollover/runs/" + runId + "/allocate", null, token);
        assertThat(allocated.getStatusCode()).isEqualTo(HttpStatus.OK);
        return runId;
    }

    private List<String> kinds(JsonNode items) {
        List<String> kinds = new java.util.ArrayList<>();
        items.forEach(item -> kinds.add(item.get("kind").asText()));
        return kinds;
    }
}
