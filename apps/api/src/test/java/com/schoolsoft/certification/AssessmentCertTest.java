package com.schoolsoft.certification;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.schoolsoft.certification.support.AbstractCertificationTest;
import com.schoolsoft.certification.support.CertificationFixture.SchoolSeed;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** CERT-ASMT — assessment, marks & report cards. */
class AssessmentCertTest extends AbstractCertificationTest {

    @Test @Tag("P1")
    void cert_ASMT_01_cbseAssessmentsAreCreatedWithComponentsWeightedToOneHundred() {
        String token = principalToken(cbse());
        UUID sectionId = currentFocusSection(cbse());

        var assessment = post("/v1/assessment", body(
            "schoolId", cbse().id(), "sectionId", sectionId, "subjectId", subjectOf(cbse(), "MATH"),
            "termId", termOf(cbse(), cbse().currentAy().code(), "T2"),
            "strategyCode", "CBSE-CCE-2024", "name", "Half Yearly — Mathematics",
            "assessmentType", "HY", "maxMarks", 80.0, "weightPct", 30.0,
            "scheduledOn", "2026-09-20"), token);
        assertThat(assessment.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID assessmentId = UUID.fromString(assessment.getBody().get("id").asText());

        post("/v1/assessment/" + assessmentId + "/components",
            body("code", "THEORY", "name", "Theory paper", "maxMarks", 60.0, "weightPct", 75.0, "sortOrder", 1), token);
        post("/v1/assessment/" + assessmentId + "/components",
            body("code", "PRACT", "name", "Practical", "maxMarks", 20.0, "weightPct", 25.0, "sortOrder", 2), token);

        var components = get("/v1/assessment/" + assessmentId + "/components", token).getBody();
        assertThat(components).hasSize(2);
        double totalWeight = 0;
        double totalMarks = 0;
        for (var component : components) {
            totalWeight += component.get("weightPct").asDouble();
            totalMarks += component.get("maxMarks").asDouble();
        }
        assertThat(totalWeight).isEqualTo(100.0);
        assertThat(totalMarks).isEqualTo(assessment.getBody().get("maxMarks").asDouble());
    }

    @Test @Tag("P1")
    void cert_ASMT_02_cambridgeComponentsGradeOnTheCieScale() {
        // The same 85% has to earn a Cambridge letter at the Cambridge school
        // and a CBSE letter at the CBSE one, from the same code path.
        Paper cie = createPaper(cie(), "ASMT02C", 100.0);
        Paper cbse = createPaper(cbse(), "ASMT02B", 100.0);
        try {
            UUID cieStudent = firstStudentIn(currentFocusSection(cie()));
            UUID cbseStudent = firstStudentIn(currentFocusSection(cbse()));
            enterMark(cie(), cie, cieStudent, 85.0);
            enterMark(cbse(), cbse, cbseStudent, 85.0);

            var cieCard = generateCard(cie(), cieStudent, "T1", "CERT-ASMT02-CIE");
            var cbseCard = generateCard(cbse(), cbseStudent, "T1", "CERT-ASMT02-CBSE");

            var cieRow = subjectRow(cie(), cieCard, cie.subjectId());
            var cbseRow = subjectRow(cbse(), cbseCard, cbse.subjectId());
            assertThat(cieRow.get("percentage").asDouble()).isEqualTo(85.0);
            assertThat(cbseRow.get("percentage").asDouble()).isEqualTo(85.0);

            // A* .. U, not A1 .. E.
            assertThat(cieRow.get("gradeLetter").asText()).isEqualTo("A");
            assertThat(cbseRow.get("gradeLetter").asText()).isEqualTo("A2");
            assertThat(queryOne("SELECT grade_scale_code FROM report_card WHERE id = ?", String.class, cieCard))
                .isEqualTo("CIE_ASTAR_E");

            // Cambridge subjects are separate qualifications, so the card does
            // not print an aggregate percentage across them; CBSE's does.
            assertThat(get("/v1/assessment/report-cards/" + cieCard, principalToken(cie()))
                .getBody().get("card").get("overallPct")).isNull();
            assertThat(get("/v1/assessment/report-cards/" + cbseCard, principalToken(cbse()))
                .getBody().get("card").get("overallPct")).isNotNull();
        } finally {
            deletePaper(cie);
            deletePaper(cbse);
        }
    }

    @Test @Tag("P1")
    void cert_ASMT_03_componentWeightsThatDoNotSumAreRejected() {
        String token = principalToken(cbse());
        var assessment = post("/v1/assessment", body(
            "schoolId", cbse().id(), "sectionId", currentFocusSection(cbse()),
            "subjectId", subjectOf(cbse(), "SCI"),
            "termId", termOf(cbse(), cbse().currentAy().code(), "T2"),
            "strategyCode", "CBSE-CCE-2024", "name", "CERT-ASMT03 unbalanced",
            "assessmentType", "HY", "maxMarks", 100.0, "weightPct", 20.0,
            "scheduledOn", "2026-11-10"), token);
        UUID assessmentId = UUID.fromString(assessment.getBody().get("id").asText());
        try {
            post("/v1/assessment/" + assessmentId + "/components",
                body("code", "A", "name", "Paper A", "maxMarks", 60.0, "weightPct", 50.0, "sortOrder", 1), token);
            post("/v1/assessment/" + assessmentId + "/components",
                body("code", "B", "name", "Paper B", "maxMarks", 40.0, "weightPct", 30.0, "sortOrder", 2), token);

            var validation = get("/v1/assessment/" + assessmentId + "/validation", token);
            assertThat(validation.getBody().get("valid").asBoolean()).isFalse();
            assertThat(validation.getBody().get("issues").toString()).contains("80");

            // And it is a gate, not a warning: marking cannot open on it.
            var opened = post("/v1/assessment/" + assessmentId + "/status", Map.of("status", "marking"), token);
            assertThat(opened.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(queryOne("SELECT status FROM assessment WHERE id = ?", String.class, assessmentId))
                .isEqualTo("draft");
        } finally {
            inChainDo(jdbc -> {
                jdbc.update("DELETE FROM assessment_component WHERE assessment_id = ?", assessmentId);
                jdbc.update("DELETE FROM assessment WHERE id = ?", assessmentId);
            });
        }
    }

    @Test @Tag("P1")
    void cert_ASMT_04_bulkMarkEntryValidatesMaximaAndBlanks() {
        Paper paper = createPaper(cbse(), "ASMT04", 20.0);
        try {
            String token = principalToken(cbse());
            List<UUID> students = studentsIn(currentFocusSection(cbse()));
            UUID scored = students.get(0);
            UUID overMax = students.get(1);
            UUID blank = students.get(2);
            UUID zero = students.get(3);

            var result = post("/v1/assessment/marks/bulk", body(
                "schoolId", cbse().id(), "componentId", paper.componentId(),
                "entries", List.of(
                    Map.of("studentId", scored, "rawMarks", 18.0),
                    Map.of("studentId", overMax, "rawMarks", 25.0),
                    Map.of("studentId", blank.toString()),                 // no marks at all
                    Map.of("studentId", zero, "rawMarks", 0.0)),
                "enteredByStaffId", cbse().teacherStaffIds().get(0),
                "reason", "CERT-ASMT04"), token);
            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().get("accepted").asInt()).isEqualTo(3);

            // The 25/20 is refused and named; the other three are stored.
            var rejected = result.getBody().get("rejected");
            assertThat(rejected).hasSize(1);
            assertThat(rejected.get(0).get("studentId").asText()).isEqualTo(overMax.toString());
            assertThat(rejected.get(0).get("reason").asText()).contains("exceeds the component maximum");
            assertThat(count("SELECT count(*) FROM mark WHERE assessment_component_id = ? AND student_id = ?",
                paper.componentId(), overMax)).isZero();

            // A blank is not a zero: one has no number and says why, the other
            // is a real mark of nought.
            assertThat(markStatus(paper, blank)).isEqualTo("pending");
            assertThat(queryList("SELECT raw_marks FROM mark WHERE assessment_component_id = ? AND student_id = ?",
                Double.class, paper.componentId(), blank).get(0)).isNull();
            assertThat(markStatus(paper, zero)).isEqualTo("entered");
            assertThat(queryOne("SELECT raw_marks FROM mark WHERE assessment_component_id = ? AND student_id = ?",
                Double.class, paper.componentId(), zero)).isEqualTo(0.0);
        } finally {
            deletePaper(paper);
        }
    }

    @Test @Tag("P1")
    void cert_ASMT_05_examAbsenceIsRecordedAsAbsentNotZero() {
        Paper paper = createPaper(cbse(), "ASMT05", 50.0);
        try {
            String token = principalToken(cbse());
            List<UUID> students = studentsIn(currentFocusSection(cbse()));
            UUID absentee = students.get(0);
            UUID present = students.get(1);

            post("/v1/assessment/marks/bulk", body(
                "schoolId", cbse().id(), "componentId", paper.componentId(),
                "entries", List.of(
                    Map.of("studentId", absentee.toString(), "status", "absent"),
                    Map.of("studentId", present, "rawMarks", 40.0)),
                "enteredByStaffId", cbse().teacherStaffIds().get(0)), token);

            assertThat(markStatus(paper, absentee)).isEqualTo("absent");
            assertThat(queryList("SELECT raw_marks FROM mark WHERE assessment_component_id = ? AND student_id = ?",
                Double.class, paper.componentId(), absentee).get(0)).isNull();

            UUID card = generateCard(cbse(), absentee, "T1", "CERT-ASMT05");
            var row = subjectRow(cbse(), card, paper.subjectId());
            assertThat(row.get("resultStatus").asText()).isEqualTo("absent");
            assertThat(row.get("display").asText()).isEqualTo("AB");
            assertThat(row.get("marksObtained")).isNull();

            // Excluded from the average, not counted as nought: the absent
            // paper's 50 marks are absent from the denominator too.
            var detail = get("/v1/assessment/report-cards/" + card, token).getBody().get("card");
            double totalMax = detail.get("totalMaxMarks").asDouble();
            double subjectMaxima = subjectMaximaOfMarkedRows(token, card);
            assertThat(totalMax).isEqualTo(subjectMaxima);
            assertThat(detail.get("overallPct").asDouble())
                .isEqualTo(round(detail.get("totalMarks").asDouble() * 100.0 / totalMax));
        } finally {
            deletePaper(paper);
        }
    }

    @Test @Tag("P1")
    void cert_ASMT_06_marksAreNotEditableOnceLocked() {
        Paper paper = createPaper(cbse(), "ASMT06", 30.0);
        try {
            String token = principalToken(cbse());
            UUID student = firstStudentIn(currentFocusSection(cbse()));
            var marked = enterMark(cbse(), paper, student, 21.0);
            assertThat(marked.getStatusCode()).isEqualTo(HttpStatus.OK);

            var locked = post("/v1/assessment/" + paper.assessmentId() + "/status",
                Map.of("status", "locked"), token);
            assertThat(locked.getStatusCode()).isEqualTo(HttpStatus.OK);

            var afterLock = enterMark(cbse(), paper, student, 29.0);
            assertThat(afterLock.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(queryOne("SELECT raw_marks FROM mark WHERE assessment_component_id = ? AND student_id = ?",
                Double.class, paper.componentId(), student)).isEqualTo(21.0);
        } finally {
            deletePaper(paper);
        }
    }

    @Test @Tag("P1")
    void cert_ASMT_07_unlockRequiresAuthorisedRoleReasonAndAudit() {
        Paper paper = createPaper(cbse(), "ASMT07", 30.0);
        try {
            String principal = principalToken(cbse());
            UUID student = firstStudentIn(currentFocusSection(cbse()));
            enterMark(cbse(), paper, student, 25.0);
            post("/v1/assessment/" + paper.assessmentId() + "/status", Map.of("status", "locked"), principal);

            // A subject teacher cannot reopen it.
            var byTeacher = post("/v1/assessment/" + paper.assessmentId() + "/status",
                Map.of("status", "marking", "reason", "Wants to change a mark"), teacherToken(cbse(), 1));
            assertThat(byTeacher.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

            // Nor can the principal without saying why.
            var noReason = post("/v1/assessment/" + paper.assessmentId() + "/status",
                Map.of("status", "marking"), principal);
            assertThat(noReason.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

            var reopened = post("/v1/assessment/" + paper.assessmentId() + "/status",
                Map.of("status", "marking", "reason", "Moderation error on Q4 — reopening for correction"), principal);
            assertThat(reopened.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(reopened.getBody().get("status").asText()).isEqualTo("marking");

            assertThat(count(
                "SELECT count(*) FROM audit_log WHERE action = 'assessment.status_change' AND target_id = ? " +
                "AND reason LIKE 'Moderation error%'", paper.assessmentId())).isEqualTo(1);
        } finally {
            deletePaper(paper);
        }
    }

    @Test @Tag("P2")
    void cert_ASMT_08_reEvaluationSupersedesWithoutDiscardingTheOriginal() {
        Paper paper = createPaper(cbse(), "ASMT08", 40.0);
        try {
            String principal = principalToken(cbse());
            UUID student = firstStudentIn(currentFocusSection(cbse()));
            enterMark(cbse(), paper, student, 24.0);
            UUID markId = queryOne("SELECT id FROM mark WHERE assessment_component_id = ? AND student_id = ?",
                UUID.class, paper.componentId(), student);
            UUID card = generateCard(cbse(), student, "T1", "CERT-ASMT08");
            assertThat(subjectRow(cbse(), card, paper.subjectId()).get("marksObtained").asDouble()).isEqualTo(24.0);

            // The parent asks; the school decides.
            var request = post("/v1/assessment/marks/" + markId + "/re-evaluations",
                Map.of("reason", "Q3 appears to be unmarked"), guardianTokenFor(cbse(), student));
            assertThat(request.getStatusCode()).isEqualTo(HttpStatus.OK);
            UUID requestId = UUID.fromString(request.getBody().get("id").asText());

            var decided = post("/v1/assessment/re-evaluations/" + requestId + "/decide", body(
                "outcome", "revised", "newRawMarks", 31.0,
                "reason", "Q3 was indeed unmarked; 7 marks added"), principal);
            assertThat(decided.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(decided.getBody().get("status").asText()).isEqualTo("revised");

            // The mark moves, and the mark it used to be is still there.
            assertThat(queryOne("SELECT raw_marks FROM mark WHERE id = ?", Double.class, markId)).isEqualTo(31.0);
            var revisions = get("/v1/assessment/marks/" + markId + "/revisions", principal).getBody();
            assertThat(revisions).hasSize(1);
            assertThat(revisions.get(0).get("oldRawMarks").asDouble()).isEqualTo(24.0);
            assertThat(revisions.get(0).get("newRawMarks").asDouble()).isEqualTo(31.0);
            assertThat(revisions.get(0).get("kind").asText()).isEqualTo("re_evaluation");

            // And the card the family reads catches up with it.
            assertThat(subjectRow(cbse(), card, paper.subjectId()).get("marksObtained").asDouble()).isEqualTo(31.0);
        } finally {
            deletePaper(paper);
        }
    }

    @Test @Tag("P1")
    void cert_ASMT_09_examTimetableAvoidsPerStudentClashesAndIssuesHallTickets() {
        String token = principalToken(cbse());
        UUID gradeId = gradeOf(cbse(), cbse().focusGradeCode());
        var schedule = post("/v1/exams/schedules", body(
            "schoolId", cbse().id(), "academicYearId", cbse().currentAy().id(),
            "termId", termOf(cbse(), cbse().currentAy().code(), "T1"),
            "code", "CERT-ASMT09", "name", "Half Yearly examinations",
            "startsOn", "2026-09-21", "endsOn", "2026-09-25"), token);
        assertThat(schedule.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID scheduleId = UUID.fromString(schedule.getBody().get("id").asText());

        try {
            var maths = post("/v1/exams/schedules/" + scheduleId + "/sessions", body(
                "gradeId", gradeId, "subjectId", subjectOf(cbse(), "MATH"), "paperCode", "P1",
                "name", "Mathematics Paper 1", "onDate", "2026-09-21",
                "startsAt", "09:30:00", "endsAt", "11:30:00", "room", "Hall A",
                "invigilatorStaffId", cbse().teacherStaffIds().get(2), "maxMarks", 80.0), token);
            assertThat(maths.getStatusCode()).isEqualTo(HttpStatus.OK);

            // A second paper the same students sit, overlapping the first.
            var science = post("/v1/exams/schedules/" + scheduleId + "/sessions", body(
                "gradeId", gradeId, "subjectId", subjectOf(cbse(), "SCI"), "paperCode", "P1",
                "name", "Science Paper 1", "onDate", "2026-09-21",
                "startsAt", "10:30:00", "endsAt", "12:30:00", "room", "Hall B",
                "invigilatorStaffId", cbse().teacherStaffIds().get(3), "maxMarks", 80.0), token);
            UUID scienceId = UUID.fromString(science.getBody().get("id").asText());

            var clashes = get("/v1/exams/schedules/" + scheduleId + "/clashes", token);
            assertThat(clashes.getBody().get("clashCount").asInt())
                .isEqualTo(studentsInGrade(cbse(), cbse().focusGradeCode()));
            // Publishing over a clash is refused: from publication onwards the
            // school runs on this document.
            assertThat(post("/v1/exams/schedules/" + scheduleId + "/publish", null, token).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

            delete("/v1/exams/sessions/" + scienceId, token);
            post("/v1/exams/schedules/" + scheduleId + "/sessions", body(
                "gradeId", gradeId, "subjectId", subjectOf(cbse(), "SCI"), "paperCode", "P1",
                "name", "Science Paper 1", "onDate", "2026-09-22",
                "startsAt", "09:30:00", "endsAt", "11:30:00", "room", "Hall B",
                "invigilatorStaffId", cbse().teacherStaffIds().get(3), "maxMarks", 80.0), token);

            assertThat(get("/v1/exams/schedules/" + scheduleId + "/clashes", token)
                .getBody().get("clashCount").asInt()).isZero();
            assertThat(post("/v1/exams/schedules/" + scheduleId + "/publish", null, token)
                .getBody().get("status").asText()).isEqualTo("published");

            var tickets = post("/v1/exams/schedules/" + scheduleId + "/hall-tickets", null, token);
            assertThat(tickets.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(tickets.getBody().size()).isEqualTo(studentsInGrade(cbse(), cbse().focusGradeCode()));

            UUID student = firstStudentIn(currentFocusSection(cbse()));
            var ticket = get("/v1/exams/schedules/" + scheduleId + "/hall-tickets/" + student, token).getBody();
            assertThat(ticket.get("ticketNo").asText()).startsWith("CERT-ASMT09-");
            assertThat(ticket.get("sessions")).hasSize(2);
            assertThat(ticket.get("sessions").get(0).get("room").asText()).isEqualTo("Hall A");

            // Re-issuing keeps the numbers already handed out.
            post("/v1/exams/schedules/" + scheduleId + "/hall-tickets", null, token);
            assertThat(get("/v1/exams/schedules/" + scheduleId + "/hall-tickets/" + student, token)
                .getBody().get("ticketNo").asText()).isEqualTo(ticket.get("ticketNo").asText());
        } finally {
            inChainDo(jdbc -> jdbc.update("DELETE FROM exam_schedule WHERE id = ?", scheduleId));
        }
    }

    @Test @Tag("P1")
    void cert_ASMT_10_reportCardCarriesMarksAttendanceRemarksAndPromotionDecision() {
        String token = principalToken(cbse());
        UUID student = firstStudentIn(currentFocusSection(cbse()));
        UUID cardId = UUID.fromString(post("/v1/assessment/report-cards", body(
            "schoolId", cbse().id(), "studentId", student,
            "academicYearId", cbse().currentAy().id(),
            "termId", termOf(cbse(), cbse().currentAy().code(), "T1"),
            "strategyCode", cbse().strategyCode(), "templateCode", "CERT-ASMT10",
            "payload", Map.of("headline", "Term 1"),
            "teacherRemarks", "Works steadily; should speak up more in class",
            "coScholastic", List.of(
                Map.of("areaCode", "WORK_EDU", "areaName", "Work education", "rating", "A", "sortOrder", 1),
                Map.of("areaCode", "ART", "areaName", "Art education", "rating", "B", "sortOrder", 2))
            ), token).getBody().get("id").asText());
        try {
            var detail = get("/v1/assessment/report-cards/" + cardId, token).getBody();
            var card = detail.get("card");

            // Subject marks, from the marks actually entered.
            assertThat(detail.get("subjects").size()).isEqualTo(cbse().subjectCodes().size());
            assertThat(card.get("totalMarks").asDouble()).isGreaterThan(0);
            assertThat(card.get("overallGrade").asText()).isNotBlank();

            // The attendance line is the attendance module's number, over the
            // school-calendar denominator.
            assertThat(card.get("attendanceWorkingDays").asInt()).isGreaterThan(0);
            assertThat(card.get("attendancePct").asDouble()).isGreaterThan(0);
            var summary = get("/v1/attendance/students/" + student + "/summary?from="
                + cbse().currentAy().startsOn() + "&to=" + attendanceWindowEnd(cbse(), "T1"), token).getBody();
            assertThat(card.get("attendancePct").asDouble()).isEqualTo(summary.get("percentage").asDouble());

            assertThat(card.get("teacherRemarks").asText()).contains("speak up more");
            assertThat(detail.get("coScholastic")).hasSize(2);
            assertThat(card.get("promotionDecision").asText()).isIn("promote", "detain", "graduate");
            // The payload the caller sent survives beside the computed content.
            assertThat(detail.get("payload").get("headline").asText()).isEqualTo("Term 1");
        } finally {
            deleteCards("CERT-ASMT10");
        }
    }

    @Test @Tag("P2")
    void cert_ASMT_11_rankAndPercentileAreComputedReproducibly() {
        String token = principalToken(cbse());
        UUID sectionId = currentFocusSection(cbse());
        try {
            var first = post("/v1/assessment/report-cards/sections", body(
                "schoolId", cbse().id(), "sectionId", sectionId,
                "academicYearId", cbse().currentAy().id(),
                "termId", termOf(cbse(), cbse().currentAy().code(), "T1"),
                "strategyCode", cbse().strategyCode(), "templateCode", "CERT-ASMT11"), token);
            assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
            int cohort = studentsIn(sectionId).size();
            assertThat(first.getBody().size()).isEqualTo(cohort);

            List<Placing> before = placings("CERT-ASMT11");
            assertThat(before).hasSize(cohort);
            assertThat(before.get(0).rank()).isEqualTo(1);
            assertThat(before.stream().mapToInt(Placing::rank).max().orElse(0)).isLessThanOrEqualTo(cohort);
            for (Placing placing : before) {
                assertThat(placing.percentile()).isBetween(0.0, 100.0);
            }
            // A better aggregate never ranks worse.
            for (int i = 1; i < before.size(); i++) {
                if (before.get(i - 1).rankKey() > before.get(i).rankKey()) {
                    assertThat(before.get(i - 1).rank()).isLessThan(before.get(i).rank());
                } else {
                    assertThat(before.get(i - 1).rank()).isEqualTo(before.get(i).rank());
                }
            }

            // Reproducible: the same marks rank the same way on a rerun.
            post("/v1/assessment/report-cards/sections", body(
                "schoolId", cbse().id(), "sectionId", sectionId,
                "academicYearId", cbse().currentAy().id(),
                "termId", termOf(cbse(), cbse().currentAy().code(), "T1"),
                "strategyCode", cbse().strategyCode(), "templateCode", "CERT-ASMT11"), token);
            assertThat(placings("CERT-ASMT11")).isEqualTo(before);
        } finally {
            deleteCards("CERT-ASMT11");
        }
    }

    @Test @Tag("P1")
    void cert_ASMT_12_lockedReportCardCannotBeSilentlyRegenerated() {
        String token = principalToken(cbse());
        UUID student = firstStudentIn(currentFocusSection(cbse()));
        try {
            UUID cardId = generateCard(cbse(), student, "T1", "CERT-ASMT12");
            post("/v1/assessment/report-cards/" + cardId + "/lock", null, token);

            // Regeneration of a locked card is refused rather than quietly
            // producing a second document for the same term.
            var again = post("/v1/assessment/report-cards", body(
                "schoolId", cbse().id(), "studentId", student,
                "academicYearId", cbse().currentAy().id(),
                "termId", termOf(cbse(), cbse().currentAy().code(), "T1"),
                "strategyCode", cbse().strategyCode(), "templateCode", "CERT-ASMT12"), token);
            assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(count("SELECT count(*) FROM report_card WHERE student_id = ? AND template_code = ?",
                student, "CERT-ASMT12")).isEqualTo(1);

            // Published, the family can see it; before that they cannot.
            String guardian = guardianTokenFor(cbse(), student);
            assertThat(cardIds(guardian, student)).doesNotContain(cardId.toString());
            assertThat(post("/v1/assessment/report-cards/" + cardId + "/publish", null, token).getStatusCode())
                .isEqualTo(HttpStatus.OK);
            assertThat(cardIds(guardian, student)).contains(cardId.toString());

            // Unlocking is deliberate, audited, and only then may it regenerate.
            assertThat(post("/v1/assessment/report-cards/" + cardId + "/unlock",
                Map.of("reason", "Mathematics re-evaluation admitted"), token).getStatusCode())
                .isEqualTo(HttpStatus.OK);
            var regenerated = post("/v1/assessment/report-cards", body(
                "schoolId", cbse().id(), "studentId", student,
                "academicYearId", cbse().currentAy().id(),
                "termId", termOf(cbse(), cbse().currentAy().code(), "T1"),
                "strategyCode", cbse().strategyCode(), "templateCode", "CERT-ASMT12"), token);
            assertThat(regenerated.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(regenerated.getBody().get("version").asInt()).isEqualTo(2);
            assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'report_card.unlocked' " +
                "AND target_id = ?", cardId)).isEqualTo(1);
        } finally {
            deleteCards("CERT-ASMT12");
        }
    }

    @Test @Tag("P1")
    void cert_ASMT_13_reportCardShowsTheStudentsOwnElectiveSet() {
        String token = principalToken(cie());
        var block = createElectiveBlock(cie(), "ASMT13");
        try {
            var cardA = post("/v1/assessment/report-cards", body(
                "schoolId", cie().id(), "studentId", block.studentA(),
                "academicYearId", cie().currentAy().id(),
                "termId", termOf(cie(), cie().currentAy().code(), "T1"),
                "strategyCode", cie().strategyCode(), "templateCode", "CIE-DEFAULT",
                "payload", Map.of("remarks", "Steady progress")), token);
            assertThat(cardA.getStatusCode()).isEqualTo(HttpStatus.OK);

            var cardB = post("/v1/assessment/report-cards", body(
                "schoolId", cie().id(), "studentId", block.studentB(),
                "academicYearId", cie().currentAy().id(),
                "termId", termOf(cie(), cie().currentAy().code(), "T1"),
                "strategyCode", cie().strategyCode(), "templateCode", "CIE-DEFAULT",
                "payload", Map.of("remarks", "Steady progress")), token);
            assertThat(cardB.getStatusCode()).isEqualTo(HttpStatus.OK);

            UUID idA = UUID.fromString(cardA.getBody().get("id").asText());
            UUID idB = UUID.fromString(cardB.getBody().get("id").asText());

            // Each card carries that student's own option, and not the other's.
            List<String> subjectsA = queryList(
                "SELECT subject_code FROM report_card_subject WHERE report_card_id = ?", String.class, idA);
            List<String> subjectsB = queryList(
                "SELECT subject_code FROM report_card_subject WHERE report_card_id = ?", String.class, idB);
            assertThat(subjectsA).contains("ASMT13-A").doesNotContain("ASMT13-B");
            assertThat(subjectsB).contains("ASMT13-B").doesNotContain("ASMT13-A");

            // The caller's own payload survives alongside the resolved subjects.
            String payloadA = queryOne("SELECT payload::text FROM report_card WHERE id = ?", String.class, idA);
            assertThat(payloadA).contains("Steady progress").contains("ASMT13-A");
        } finally {
            inChainDo(jdbc -> jdbc.update(
                "DELETE FROM report_card WHERE student_id IN (?, ?) AND template_code = 'CIE-DEFAULT'",
                block.studentA(), block.studentB()));
            deleteElectiveBlock(block);
        }
    }

    @Test @Tag("P2")
    void cert_ASMT_14_midYearJoinerReportCardShowsOnlyTermsAttended() {
        String token = principalToken(cbse());
        UUID sectionId = currentFocusSection(cbse());
        LocalDate joinedOn = LocalDate.parse(
            queryOne("SELECT starts_on::text FROM term WHERE id = ?", String.class,
                termOf(cbse(), cbse().currentAy().code(), "T2")));

        var student = post("/v1/people/students", body(
            "schoolId", cbse().id(), "firstName", "Midyear", "lastName", "Joiner",
            "dob", "2016-05-04", "gender", "female"), token);
        UUID studentId = UUID.fromString(student.getBody().get("id").asText());
        try {
            var enrolment = post("/v1/enrolment", body(
                "schoolId", cbse().id(), "studentId", studentId, "sectionId", sectionId,
                "academicYearId", cbse().currentAy().id(), "startsOn", joinedOn.toString()), token);
            assertThat(enrolment.getStatusCode()).isEqualTo(HttpStatus.OK);

            var card = post("/v1/assessment/report-cards", body(
                "schoolId", cbse().id(), "studentId", studentId,
                "academicYearId", cbse().currentAy().id(), "termId", null,
                "strategyCode", cbse().strategyCode(), "templateCode", "CERT-ASMT14"), token);
            assertThat(card.getStatusCode()).isEqualTo(HttpStatus.OK);

            var body = card.getBody();
            assertThat(body.get("termsInYear").asInt()).isEqualTo(2);
            assertThat(body.get("termsAttended").asInt()).isEqualTo(1);
            assertThat(body.get("enrolledFrom").asText()).isEqualTo(joinedOn.toString());
            // An explanatory note, not blank rows a reader takes for failure.
            assertThat(body.get("coverageNote").asText())
                .contains("Term 1").contains("not on roll");

            var detail = get("/v1/assessment/report-cards/" + body.get("id").asText(), token).getBody();
            assertThat(detail.get("subjects").size()).isEqualTo(cbse().subjectCodes().size());
            for (JsonNode row : detail.get("subjects")) {
                assertThat(row.get("resultStatus").asText()).isEqualTo("not_assessed");
                assertThat(row.get("display").asText()).isEqualTo("—");
            }
        } finally {
            inChainDo(jdbc -> {
                jdbc.update("DELETE FROM report_card WHERE student_id = ?", studentId);
                jdbc.update("DELETE FROM enrolment WHERE student_id = ?", studentId);
                jdbc.update("DELETE FROM student WHERE id = ?", studentId);
            });
        }
    }

    @Test @Tag("P2")
    void cert_ASMT_15_duesBlockPolicyIsHonouredAtPublication() {
        String token = principalToken(cbse());
        UUID student = firstStudentIn(currentFocusSection(cbse()));
        try {
            // The fixture's current-year invoices are unpaid, so this family is
            // in arrears whichever way the school decides to treat that.
            assertThat(count("SELECT count(*) FROM fee_invoice WHERE student_id = ? AND status = 'open'", student))
                .isGreaterThan(0);

            put("/v1/assessment/policy", body(
                "schoolId", cbse().id(), "duesBlockPolicy", "withhold", "duesBlockThreshold", 0.0), token);

            UUID cardId = generateCard(cbse(), student, "T1", "CERT-ASMT15");
            post("/v1/assessment/report-cards/" + cardId + "/lock", null, token);

            var withheld = post("/v1/assessment/report-cards/" + cardId + "/publish", null, token);
            assertThat(withheld.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(withheld.getBody().get("message").asText()).contains("withhold");
            assertThat(queryOne("SELECT status FROM report_card WHERE id = ?", String.class, cardId))
                .isEqualTo("locked");

            // The other half of the choice: a school that releases results
            // regardless publishes the same card unchanged.
            put("/v1/assessment/policy", body(
                "schoolId", cbse().id(), "duesBlockPolicy", "release"), token);
            var released = post("/v1/assessment/report-cards/" + cardId + "/publish", null, token);
            assertThat(released.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(released.getBody().get("status").asText()).isEqualTo("published");
        } finally {
            put("/v1/assessment/policy", body(
                "schoolId", cbse().id(), "duesBlockPolicy", "release"), token);
            deleteCards("CERT-ASMT15");
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * A subject nobody else's scenario touches, taught to the whole focus
     * section, with one assessment open for marking. Scenarios that need to
     * assert an exact percentage need marks they own outright — the fixture's
     * seeded marks belong to everybody.
     */
    private record Paper(SchoolSeed school, UUID subjectId, UUID assessmentId, UUID componentId) {}

    private Paper createPaper(SchoolSeed school, String code, double maxMarks) {
        String token = principalToken(school);
        UUID sectionId = currentFocusSection(school);

        UUID subjectId = UUID.fromString(post("/v1/tenancy/schools/" + school.id() + "/subjects",
            Map.of("code", code, "name", code + " paper"), token).getBody().get("id").asText());
        post("/v1/tenancy/sections/" + sectionId + "/teachers", body(
            "subjectId", subjectId, "teacherStaffId", school.teacherStaffIds().get(0),
            "isPrimary", false, "isElective", false), token);

        UUID assessmentId = UUID.fromString(post("/v1/assessment", body(
            "schoolId", school.id(), "sectionId", sectionId, "subjectId", subjectId,
            "termId", termOf(school, school.currentAy().code(), "T1"),
            "strategyCode", school.strategyCode(), "name", code + " assessment",
            "assessmentType", "HY", "maxMarks", maxMarks, "weightPct", 100.0,
            "scheduledOn", "2026-08-03"), token).getBody().get("id").asText());
        UUID componentId = UUID.fromString(post("/v1/assessment/" + assessmentId + "/components",
            body("code", "MAIN", "name", "Written paper", "maxMarks", maxMarks, "weightPct", 100.0,
                "sortOrder", 1), token).getBody().get("id").asText());
        post("/v1/assessment/" + assessmentId + "/status", Map.of("status", "marking"), token);
        return new Paper(school, subjectId, assessmentId, componentId);
    }

    private void deletePaper(Paper paper) {
        inChainDo(jdbc -> {
            jdbc.update("DELETE FROM report_card_subject WHERE subject_id = ?", paper.subjectId());
            jdbc.update("DELETE FROM mark_reevaluation WHERE mark_id IN " +
                "(SELECT id FROM mark WHERE assessment_component_id = ?)", paper.componentId());
            jdbc.update("DELETE FROM mark_revision WHERE mark_id IN " +
                "(SELECT id FROM mark WHERE assessment_component_id = ?)", paper.componentId());
            jdbc.update("DELETE FROM mark WHERE assessment_component_id = ?", paper.componentId());
            jdbc.update("DELETE FROM assessment_component WHERE assessment_id = ?", paper.assessmentId());
            jdbc.update("DELETE FROM assessment WHERE id = ?", paper.assessmentId());
            jdbc.update("DELETE FROM report_card WHERE template_code LIKE 'CERT-ASMT%'");
            jdbc.update("DELETE FROM section_subject_teacher WHERE subject_id = ?", paper.subjectId());
            jdbc.update("DELETE FROM subject WHERE id = ?", paper.subjectId());
        });
    }

    private void deleteCards(String templateCode) {
        inChainDo(jdbc -> jdbc.update("DELETE FROM report_card WHERE template_code = ?", templateCode));
    }

    private org.springframework.http.ResponseEntity<JsonNode> enterMark(
        SchoolSeed school, Paper paper, UUID studentId, double rawMarks
    ) {
        return post("/v1/assessment/components/" + paper.componentId() + "/marks", body(
            "schoolId", school.id(), "studentId", studentId, "rawMarks", rawMarks,
            "enteredByStaffId", school.teacherStaffIds().get(0),
            "reason", "Certification entry"), principalToken(school));
    }

    private String markStatus(Paper paper, UUID studentId) {
        return queryOne("SELECT status FROM mark WHERE assessment_component_id = ? AND student_id = ?",
            String.class, paper.componentId(), studentId);
    }

    private UUID generateCard(SchoolSeed school, UUID studentId, String termCode, String templateCode) {
        var response = post("/v1/assessment/report-cards", body(
            "schoolId", school.id(), "studentId", studentId,
            "academicYearId", school.currentAy().id(),
            "termId", termOf(school, school.currentAy().code(), termCode),
            "strategyCode", school.strategyCode(), "templateCode", templateCode), principalToken(school));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return UUID.fromString(response.getBody().get("id").asText());
    }

    private JsonNode subjectRow(SchoolSeed school, UUID cardId, UUID subjectId) {
        var detail = get("/v1/assessment/report-cards/" + cardId, principalToken(school)).getBody();
        for (JsonNode row : detail.get("subjects")) {
            if (row.get("subjectId").asText().equals(subjectId.toString())) return row;
        }
        throw new AssertionError("Report card " + cardId + " has no row for subject " + subjectId);
    }

    private double subjectMaximaOfMarkedRows(String token, UUID cardId) {
        double total = 0;
        for (JsonNode row : get("/v1/assessment/report-cards/" + cardId, token).getBody().get("subjects")) {
            if ("marked".equals(row.get("resultStatus").asText()) && !row.get("maxMarks").isNull()) {
                total += row.get("maxMarks").asDouble();
            }
        }
        return total;
    }

    private record Placing(UUID id, int rank, double percentile, double rankKey) {}

    private List<Placing> placings(String templateCode) {
        return inChain(jdbc -> jdbc.query(
            "SELECT id, class_rank, percentile, rank_key FROM report_card WHERE template_code = ? " +
            "ORDER BY rank_key DESC, id",
            (rs, i) -> new Placing(UUID.fromString(rs.getString("id")), rs.getInt("class_rank"),
                rs.getDouble("percentile"), rs.getDouble("rank_key")),
            templateCode));
    }

    private List<String> cardIds(String token, UUID studentId) {
        var cards = get("/v1/assessment/report-cards/students/" + studentId, token).getBody();
        List<String> ids = new java.util.ArrayList<>();
        cards.forEach(card -> ids.add(card.get("id").asText()));
        return ids;
    }

    private int studentsInGrade(SchoolSeed school, String gradeCode) {
        return (int) count(
            "SELECT count(*) FROM enrolment e JOIN section s ON s.id = e.section_id " +
            "WHERE s.grade_id = ? AND s.academic_year_id = ? AND e.status = 'active'",
            gradeOf(school, gradeCode), school.currentAy().id());
    }

    private String attendanceWindowEnd(SchoolSeed school, String termCode) {
        LocalDate termEnd = LocalDate.parse(queryOne("SELECT ends_on::text FROM term WHERE id = ?", String.class,
            termOf(school, school.currentAy().code(), termCode)));
        LocalDate today = LocalDate.now();
        return (termEnd.isAfter(today) ? today : termEnd).toString();
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
