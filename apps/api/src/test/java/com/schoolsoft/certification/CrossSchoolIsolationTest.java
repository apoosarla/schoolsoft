package com.schoolsoft.certification;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolsoft.certification.support.AbstractCertificationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Cross-school id enumeration, table by table.
 *
 * <p>Isolation between the schools of one chain is row-level security, not
 * handler code: V009 puts a {@code school_id = current_school_id()} policy on
 * every table that carries the column, which is why reading another school's
 * student or invoice by id answers 404 without anything in Java saying so.</p>
 *
 * <p>A table <em>without</em> {@code school_id} gets no policy, and is safe
 * only while every read of it joins a parent that has one. That was assumed
 * and never checked. Probing it found twelve reads and one write that did not
 * join, each returning another school's rows to a valid token:
 * invoice lines, assessment components, library copies, terms, curriculum
 * nodes, learning outcomes, thread messages, assignment submissions, quiz
 * questions, quiz attempts, admission events, a bus's GPS trail (in
 * {@code cert_SEC_04}), and a role grant written onto another school's staff.</p>
 *
 * <p>This is the regression net. Each case fails if somebody drops the join
 * again — which is the only reason these queries look the way they do.</p>
 */
@Tag("harness")
class CrossSchoolIsolationTest extends AbstractCertificationTest {

    private static final UUID PROBE_NODE       = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID PROBE_LO         = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID PROBE_THREAD     = UUID.fromString("00000000-0000-4000-8000-000000000003");
    private static final UUID PROBE_MSG        = UUID.fromString("00000000-0000-4000-8000-000000000004");
    private static final UUID PROBE_ASSIGNMENT = UUID.fromString("00000000-0000-4000-8000-000000000005");
    private static final UUID PROBE_QUIZ       = UUID.fromString("00000000-0000-4000-8000-000000000006");
    private static final UUID PROBE_APP        = UUID.fromString("00000000-0000-4000-8000-000000000007");

    private UUID one(String sql, Object... args) {
        return queryOne(sql, UUID.class, args);
    }

    /**
     * The child rows the fixture does not carry — nothing seeds a curriculum
     * tree, a thread, an assignment, a quiz or an application, because the
     * scenarios that need them create their own. Seeded here against the
     * <em>other</em> school so there is something to try to steal.
     */
    private void seedOtherSchoolsChildRows() {
        UUID cie = cie().id();
        UUID cieSection = one("SELECT id FROM section WHERE school_id = ? LIMIT 1", cie);
        UUID cieSubject = one("SELECT id FROM subject WHERE school_id = ? LIMIT 1", cie);
        UUID cieCurr    = one("SELECT id FROM curriculum WHERE school_id = ? LIMIT 1", cie);
        UUID cieAy      = one("SELECT id FROM academic_year WHERE school_id = ? LIMIT 1", cie);
        UUID cieGrade   = one("SELECT id FROM grade WHERE school_id = ? LIMIT 1", cie);
        UUID cieUser    = one("SELECT id FROM user_account WHERE school_id = ? LIMIT 1", cie);
        UUID cieStudent = one("SELECT id FROM student WHERE school_id = ? LIMIT 1", cie);
                inChainDo(j -> {

            j.update("INSERT INTO curriculum_node (id, curriculum_id, node_type, name, path, depth) " +
                "VALUES (?, ?, 'unit', 'Probe unit', 'probe', 1) ON CONFLICT DO NOTHING",
                PROBE_NODE, cieCurr);
            j.update("INSERT INTO learning_outcome (id, curriculum_node_id, code, statement) " +
                "VALUES (?, ?, 'PRB-1', 'Probe outcome') ON CONFLICT DO NOTHING", PROBE_LO, PROBE_NODE);
            j.update("INSERT INTO message_thread (id, school_id, participants) VALUES (?, ?, ARRAY[?]::uuid[]) " +
                "ON CONFLICT DO NOTHING", PROBE_THREAD, cie, cieUser);
            j.update("INSERT INTO message (id, thread_id, sender_user_id, body) VALUES (?, ?, ?, 'probe') " +
                "ON CONFLICT DO NOTHING", PROBE_MSG, PROBE_THREAD, cieUser);
            j.update("INSERT INTO assignment (id, school_id, section_id, subject_id, title) " +
                "VALUES (?, ?, ?, ?, 'Probe assignment') ON CONFLICT DO NOTHING",
                PROBE_ASSIGNMENT, cie, cieSection, cieSubject);
            j.update("INSERT INTO assignment_submission (id, assignment_id, student_id, body) " +
                "VALUES (?, ?, ?, 'probe') ON CONFLICT DO NOTHING",
                UUID.randomUUID(), PROBE_ASSIGNMENT, cieStudent);
            j.update("INSERT INTO quiz (id, school_id, title) VALUES (?, ?, 'Probe quiz') ON CONFLICT DO NOTHING",
                PROBE_QUIZ, cie);
            j.update("INSERT INTO quiz_question (id, quiz_id, kind, prompt) " +
                "VALUES (?, ?, 'mcq', 'Probe question?') ON CONFLICT DO NOTHING", UUID.randomUUID(), PROBE_QUIZ);
            j.update("INSERT INTO quiz_attempt (id, quiz_id, student_id) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                UUID.randomUUID(), PROBE_QUIZ, cieStudent);
            j.update("INSERT INTO admission_application (id, school_id, academic_year_id, grade_id, application_no, " +
                "applicant_first_name, guardian_name, guardian_phone) " +
                "VALUES (?, ?, ?, ?, 'PROBE-1', 'Probe', 'Probe Guardian', '+910000000000') ON CONFLICT DO NOTHING",
                PROBE_APP, cie, cieAy, cieGrade);
            j.update("INSERT INTO admission_event (id, application_id, event_type, to_state) " +
                "VALUES (?, ?, 'created', 'lead') ON CONFLICT DO NOTHING", UUID.randomUUID(), PROBE_APP);
        });

    }

    @Test
    @DisplayName("a child table with no school_id is still bounded by its parent's policy")
    void childTablesWithoutSchoolIdAreBoundedByTheirParent() {
        seedOtherSchoolsChildRows();
        String a = principalToken(cbse());
        UUID cie = cie().id();

        UUID curriculum   = one("SELECT id FROM curriculum WHERE school_id = ? LIMIT 1", cie);
        UUID invoice      = one("SELECT id FROM fee_invoice WHERE school_id = ? LIMIT 1", cie);
        UUID assessment   = one("SELECT id FROM assessment WHERE school_id = ? LIMIT 1", cie);
        UUID libraryTitle = one("SELECT id FROM library_title WHERE school_id = ? LIMIT 1", cie);
        UUID academicYear = one("SELECT id FROM academic_year WHERE school_id = ? LIMIT 1", cie);

        // Each of these once returned the other school's rows.
        assertThat(get("/v1/fees/invoices/" + invoice + "/lines", a).getBody()).isEmpty();
        assertThat(get("/v1/assessment/" + assessment + "/components", a).getBody()).isEmpty();
        assertThat(get("/v1/library/titles/" + libraryTitle + "/copies", a).getBody()).isEmpty();
        assertThat(get("/v1/tenancy/academic-years/" + academicYear + "/terms", a).getBody()).isEmpty();
        assertThat(get("/v1/curriculum/" + curriculum + "/nodes", a).getBody()).isEmpty();
        assertThat(get("/v1/curriculum/nodes/" + PROBE_NODE + "/learning-outcomes", a).getBody()).isEmpty();
        assertThat(get("/v1/comms/threads/" + PROBE_THREAD + "/messages", a).getBody()).isEmpty();
        assertThat(get("/v1/lms/assignments/" + PROBE_ASSIGNMENT + "/submissions", a).getBody()).isEmpty();
        assertThat(get("/v1/lms/quizzes/" + PROBE_QUIZ + "/questions", a).getBody()).isEmpty();
        assertThat(get("/v1/lms/quizzes/" + PROBE_QUIZ + "/attempts", a).getBody()).isEmpty();
        assertThat(get("/v1/admissions/applications/" + PROBE_APP + "/events", a).getBody()).isEmpty();

        // And the school that owns them still reads them.
        assertThat(get("/v1/curriculum/" + curriculum + "/nodes", principalToken(cie())).getBody()).isNotEmpty();
        assertThat(get("/v1/comms/threads/" + PROBE_THREAD + "/messages", principalToken(cie())).getBody())
            .isNotEmpty();
    }

    /**
     * The one write of the set. staff_role carries no school_id either, and
     * nothing tied the request's schoolId to the caller's own, so one school's
     * it_admin could grant a role onto another school's staff record.
     */
    @Test
    @DisplayName("a role grant cannot be written onto another school's staff")
    void aRoleGrantCannotCrossTheSchoolBoundary() {
        UUID otherSchoolsStaff = one("SELECT id FROM staff WHERE school_id = ? LIMIT 1", cie().id());

        var granted = post("/v1/iam/staff-roles/assign", body(
            "staffId", otherSchoolsStaff, "schoolId", cie().id(), "roleCode", "it_admin",
            "scopeType", "school", "scopeId", cie().id(),
            "reason", "cross-school isolation test"), principalToken(cbse()));

        assertThat(granted.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(count("SELECT count(*) FROM staff_role WHERE staff_id = ? AND role_code = 'it_admin'",
            otherSchoolsStaff)).isZero();
    }
}
