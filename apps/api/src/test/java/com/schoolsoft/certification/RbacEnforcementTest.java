package com.schoolsoft.certification;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolsoft.certification.support.AbstractCertificationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * What the permission gates actually do, over HTTP, with real tokens.
 *
 * <p>{@code RbacArchitectureTest} proves every endpoint carries a gate and that
 * the gate names a permission that exists. It cannot prove the gate is the
 * right one — that a librarian is kept out of the fee ledger, that a guardian
 * sees their own child and not the class. This does, one case per shape of
 * mistake, positive and negative.</p>
 *
 * <p>Method names deliberately avoid the {@code cert_XX_NN_} form:
 * {@link CatalogueSyncTest} owns that namespace and requires a matching row in
 * {@code docs/certification-test-scenarios.md}. These are structural guards on
 * the authorization model, not product scenarios.</p>
 *
 * <p>Runs in the {@code harness} group, alongside the architecture rules, so
 * the blocking CI gate covers both halves.</p>
 */
@Tag("harness")
class RbacEnforcementTest extends AbstractCertificationTest {

    // ===================== the hole this whole model closed =====================

    /**
     * The original defect: {@code POST /v1/iam/staff-roles/assign} carried no
     * authorization at all, so any valid token — a parent's, a driver's —
     * could grant itself {@code it_admin} and own the chain.
     */
    @Test
    @DisplayName("a guardian cannot grant themselves a role")
    void guardianCannotGrantRoles() {
        UUID studentId = firstStudentIn(currentFocusSection(cbse()));
        String guardian = guardianTokenFor(cbse(), studentId);

        var granted = post("/v1/iam/staff-roles/assign", body(
            "staffId", cbse().principalStaffId(),
            "schoolId", cbse().id(),
            "roleCode", "it_admin",
            "scopeType", "school",
            "scopeId", cbse().id(),
            // A role grant is @Audited(requireReason = true), and the audit
            // interceptor runs ahead of method security — omit the reason and
            // the refusal is a 400 about the payload rather than the 403 this
            // test is about. Send what a real caller sends.
            "reason", "rbac enforcement test"), guardian);

        assertThat(granted.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(count("SELECT count(*) FROM staff_role WHERE role_code = 'it_admin' "
            + "AND staff_id = ?", cbse().principalStaffId())).isZero();
    }

    @Test
    @DisplayName("a guardian cannot read the role catalogue or the audit log")
    void guardianCannotReadAdministration() {
        UUID studentId = firstStudentIn(currentFocusSection(cbse()));
        String guardian = guardianTokenFor(cbse(), studentId);

        assertThat(get("/v1/iam/roles", guardian).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/v1/audit?schoolId=" + cbse().id(), guardian).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    /**
     * The calendar is the counter-example to "a family only reads its own":
     * {@code calendar.view} is school-wide in {@code GUARDIAN_BASELINE} on
     * purpose, because the same day resolution is served to anybody at
     * {@code /v1/public/schools/&#123;chain&#125;/&#123;school&#125;/calendar}
     * with no token. Reading it is not a leak; authoring it is the gate that
     * matters.
     */
    @Test
    @DisplayName("a guardian reads the calendar and cannot author it")
    void guardianReadsTheCalendarAndCannotAuthorIt() {
        UUID studentId = firstStudentIn(currentFocusSection(cbse()));
        String guardian = guardianTokenFor(cbse(), studentId);
        String range = "?schoolId=" + cbse().id() + "&from=2026-09-07&to=2026-09-07";

        assertThat(get("/v1/calendar/days" + range, guardian).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(post("/v1/calendar/entries", body(
            "schoolId", cbse().id(), "onDate", "2026-09-07", "kind", "holiday",
            "title", "Declared by a parent"), guardian).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post("/v1/calendar/closures", body(
            "schoolId", cbse().id(), "onDate", "2026-09-07",
            "title", "Declared by a parent"), guardian).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(count("SELECT count(*) FROM school_calendar WHERE school_id = ? "
            + "AND title = 'Declared by a parent'", cbse().id())).isZero();
    }

    // ===================== a permission is not a relationship =====================

    /**
     * {@code fee.invoice.view.own} gets a guardian through the door of the dues
     * endpoint. {@code SelfScope} decides whose dues. Both halves have to hold,
     * and this is the half a permission check alone would miss.
     */
    @Test
    @DisplayName("a guardian reads their own child's dues and no other family's")
    void guardianSeesOnlyTheirOwnChildsMoney() {
        var students = studentsIn(currentFocusSection(cbse()));
        UUID mine = students.get(0);
        UUID theirs = students.get(1);
        String guardian = guardianTokenFor(cbse(), mine);

        assertThat(get("/v1/fees/students/" + mine + "/dues", guardian).getStatusCode())
            .isEqualTo(HttpStatus.OK);
        assertThat(get("/v1/fees/students/" + theirs + "/dues", guardian).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("a guardian reads their own child's attendance and no other student's")
    void guardianSeesOnlyTheirOwnChildsAttendance() {
        var students = studentsIn(currentFocusSection(cbse()));
        UUID mine = students.get(0);
        UUID theirs = students.get(1);
        String guardian = guardianTokenFor(cbse(), mine);
        String range = "?from=2026-08-01&to=2026-08-31";

        assertThat(get("/v1/attendance/students/" + mine + range, guardian).getStatusCode())
            .isEqualTo(HttpStatus.OK);
        assertThat(get("/v1/attendance/students/" + theirs + range, guardian).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("a guardian reads their own child's record and no other student's")
    void guardianSeesOnlyTheirOwnChildsRecord() {
        var students = studentsIn(currentFocusSection(cbse()));
        UUID mine = students.get(0);
        UUID theirs = students.get(1);
        String guardian = guardianTokenFor(cbse(), mine);

        assertThat(get("/v1/people/students/" + mine, guardian).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/v1/people/students/" + theirs, guardian).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    /**
     * The list-shaped version of the same rule. A component's marks are the
     * whole class; refusing outright would take the marks screen away from
     * every parent, so the read is narrowed instead — the guardian gets their
     * own rows and learns nothing about the rest.
     */
    @Test
    @DisplayName("a guardian's view of a component's marks holds only their own child")
    void guardianSeesOnlyTheirOwnRowInAComponentsMarks() {
        UUID sectionId = currentFocusSection(cbse());
        UUID componentId = queryOne(
            "SELECT c.id FROM assessment_component c JOIN assessment a ON a.id = c.assessment_id "
            + "JOIN mark m ON m.assessment_component_id = c.id "
            + "WHERE a.section_id = ? LIMIT 1", UUID.class, sectionId);
        UUID mine = queryOne("SELECT student_id FROM mark WHERE assessment_component_id = ? LIMIT 1",
            UUID.class, componentId);
        String guardian = guardianTokenFor(cbse(), mine);

        var asStaff = get("/v1/assessment/components/" + componentId + "/marks", principalToken(cbse()));
        var asGuardian = get("/v1/assessment/components/" + componentId + "/marks", guardian);

        assertThat(asStaff.getBody().size()).isGreaterThan(1);
        assertThat(asGuardian.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(asGuardian.getBody().findValuesAsText("studentId"))
            .containsExactly(mine.toString());
    }

    /**
     * {@code exam.view.own} lets a family through the door of every exam read,
     * and a draft schedule is the exams officer's working copy: papers at hours
     * that still clash, in rooms not yet booked. A family that reads it plans
     * around a timetable the school never issued. Publication is what makes a
     * schedule theirs to see, and unpublishing takes it back.
     */
    @Test
    @DisplayName("a guardian reads an exam schedule only once it is published")
    void guardianReadsOnlyPublishedExamSchedules() {
        String principal = principalToken(cbse());
        UUID student = firstStudentIn(currentFocusSection(cbse()));
        String guardian = guardianTokenFor(cbse(), student);

        UUID scheduleId = UUID.fromString(post("/v1/exams/schedules", body(
            "schoolId", cbse().id(), "academicYearId", cbse().currentAy().id(),
            "code", "RBAC-DRAFT", "name", "Draft examinations",
            "startsOn", "2026-09-21", "endsOn", "2026-09-25"), principal).getBody().get("id").asText());
        String list = "/v1/exams/schedules?schoolId=" + cbse().id();
        String one = "/v1/exams/schedules/" + scheduleId;
        try {
            post(one + "/sessions", body(
                "gradeId", gradeOf(cbse(), cbse().focusGradeCode()), "subjectId", subjectOf(cbse(), "SCI"),
                "name", "Science Paper 1", "onDate", "2026-09-22",
                "startsAt", "09:30:00", "endsAt", "11:30:00", "room", "Hall B"), principal);

            // The office works on its draft.
            assertThat(get(list, principal).getBody().findValuesAsText("id")).contains(scheduleId.toString());
            assertThat(get(one, principal).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(get(one + "/sessions", principal).getBody()).hasSize(1);

            // The family is not told it exists, by any of the four reads.
            assertThat(get(list, guardian).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(get(list, guardian).getBody().findValuesAsText("id"))
                .doesNotContain(scheduleId.toString());
            assertThat(get(one, guardian).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(get(one + "/sessions", guardian).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(get(one + "/students/" + student + "/sessions", guardian).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

            // Published, it is theirs to read.
            post(one + "/publish", null, principal);
            post(one + "/hall-tickets", null, principal);
            assertThat(get(list, guardian).getBody().findValuesAsText("id")).contains(scheduleId.toString());
            assertThat(get(one, guardian).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(get(one + "/sessions", guardian).getBody()).hasSize(1);
            assertThat(get(one + "/students/" + student + "/sessions", guardian).getBody()).hasSize(1);
            assertThat(get(one + "/hall-tickets/" + student, guardian).getStatusCode())
                .isEqualTo(HttpStatus.OK);

            // Taken back, it is gone again — and so is the ticket that listed its
            // papers, which the office can still read.
            post(one + "/unpublish", null, principal);
            assertThat(get(list, guardian).getBody().findValuesAsText("id"))
                .doesNotContain(scheduleId.toString());
            assertThat(get(one + "/sessions", guardian).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(get(one + "/hall-tickets/" + student, guardian).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(get(one + "/hall-tickets/" + student, principal).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        } finally {
            inChainDo(jdbc -> jdbc.update("DELETE FROM exam_schedule WHERE id = ?", scheduleId));
        }
    }

    // ===================== staff hold what their job needs, and no more =====================

    @Test
    @DisplayName("a librarian cannot read the fee ledger")
    void librarianIsNotAnAccountant() {
        String librarian = librarianToken(cbse());

        assertThat(get("/v1/library/titles?schoolId=" + cbse().id(), librarian).getStatusCode())
            .isEqualTo(HttpStatus.OK);
        assertThat(get("/v1/fees/reports/day-book?schoolId=" + cbse().id()
            + "&from=2026-08-01&to=2026-08-31", librarian).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    /**
     * BUG-26. {@code dashboard.view} opens the overview for a teacher's
     * register and comms cards, but the school's takings and admissions
     * pipeline follow the grants that open those modules' own reports. The
     * figures are left out of the payload, not hidden by the page.
     */
    @Test
    @DisplayName("a teacher's dashboard carries no school-wide fee or admissions totals")
    void dashboardTotalsFollowTheirModulesGrants() {
        String overview = "/v1/dashboards/schools/" + cbse().id() + "/overview";

        var teacher = get(overview, teacherToken(cbse(), 0));
        assertThat(teacher.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(teacher.getBody().get("activeEnrolments").isNumber()).isTrue();
        assertThat(teacher.getBody().hasNonNull("feeInvoicedMtd")).isFalse();
        assertThat(teacher.getBody().hasNonNull("feeCollectedMtd")).isFalse();
        assertThat(teacher.getBody().hasNonNull("admissionsFunnel")).isFalse();

        var principal = get(overview, principalToken(cbse())).getBody();
        assertThat(principal.get("feeInvoicedMtd").isNumber()).isTrue();
        assertThat(principal.get("admissionsFunnel").isObject()).isTrue();

        // The accountant reads the takings but not the admissions pipeline.
        var accountant = get(overview, accountantToken(cbse())).getBody();
        assertThat(accountant.get("feeInvoicedMtd").isNumber()).isTrue();
        assertThat(accountant.hasNonNull("admissionsFunnel")).isFalse();
    }

    @Test
    @DisplayName("an accountant cannot enter marks or publish a report card")
    void accountantIsNotATeacher() {
        String accountant = accountantToken(cbse());
        UUID sectionId = currentFocusSection(cbse());
        UUID componentId = queryOne(
            "SELECT c.id FROM assessment_component c JOIN assessment a ON a.id = c.assessment_id "
            + "WHERE a.section_id = ? LIMIT 1", UUID.class, sectionId);

        assertThat(get("/v1/fees/reports/day-book?schoolId=" + cbse().id()
            + "&from=2026-08-01&to=2026-08-31", accountant).getStatusCode())
            .isEqualTo(HttpStatus.OK);

        var entered = post("/v1/assessment/components/" + componentId + "/marks", body(
            "schoolId", cbse().id(),
            "studentId", firstStudentIn(sectionId),
            "rawMarks", 99.0), accountant);
        assertThat(entered.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("a subject teacher cannot manage roles or run the fee generator")
    void teacherIsNotAnAdministrator() {
        String teacher = teacherToken(cbse(), 1);

        assertThat(get("/v1/iam/roles", teacher).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post("/v1/fees/generate", body(
            "schoolId", cbse().id(),
            "academicYearId", cbse().currentAy().id(),
            "cycleLabel", "should-never-run"), teacher).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);

        // Reading the timetable is a teacher's job; revising it is not. Retiring
        // a slot is a timetable.manage write behind an ordinary-looking POST.
        UUID anySlot = queryOne("SELECT id FROM timetable_slot WHERE section_id = ? LIMIT 1",
            UUID.class, currentFocusSection(cbse()));
        assertThat(post("/v1/timetable/slots/" + anySlot + "/retire",
            body("lastDay", cbse().currentAy().startsOn().plusMonths(1).toString()), teacher)
            .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ===================== the principals that hold no role row =====================

    /**
     * A chain (HQ) admin oversees the chain's schools and changes none of them.
     * The read set is derived from {@code Perm.isUnrestrictedRead()}, so this
     * is the test that the derivation lands on the right side of the line.
     */
    @Test
    @DisplayName("a chain admin reads the schools in their chain and writes nothing")
    void chainAdminReadsAndDoesNotWrite() {
        String hq = chainAdminToken();

        assertThat(get("/v1/tenancy/schools", hq).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/v1/tenancy/schools/" + cbse().id() + "/readiness", hq).getStatusCode())
            .isEqualTo(HttpStatus.OK);
        assertThat(get("/v1/iam/me", hq).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(post("/v1/people/students", body(
            "schoolId", cbse().id(), "firstName", "Should", "lastName", "NotExist"), hq).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post("/v1/iam/staff-roles/assign", body(
            "staffId", cbse().principalStaffId(), "schoolId", cbse().id(),
            "roleCode", "it_admin", "scopeType", "school", "scopeId", cbse().id(),
            "reason", "rbac enforcement test"), hq).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    /**
     * The chain admin's second write, and the reason it is not a third: a
     * school with nobody in it has nobody who could appoint anybody, so the
     * first keyholder comes from the chain. Everything else about staff stays
     * where it was — a chain admin still cannot grant a role, and the office's
     * own hiring is the school's.
     */
    @Test
    @DisplayName("appointing a school's first administrator is school.onboard, not role.manage")
    void firstAdminIsOnboardingAndNotRoleManagement() {
        String hq = chainAdminToken();
        String teacher = teacherToken(cbse(), 1);

        // A teacher is refused whatever the school's state — the gate is the
        // permission, and a subject teacher holds neither school.onboard nor
        // any business appointing a head.
        assertThat(post("/v1/tenancy/schools/" + cbse().id() + "/first-admin", body(
            "firstName", "Should", "lastName", "NotExist",
            "email", "should.not.exist@oakridge.test"), teacher).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);

        // The chain admin passes the gate and is then refused by the use case,
        // because this school already has people in it. 409, not 403: the
        // permission was never the problem.
        assertThat(post("/v1/tenancy/schools/" + cbse().id() + "/first-admin", body(
            "firstName", "Should", "lastName", "NotExist",
            "email", "should.not.exist@oakridge.test"), hq).getStatusCode())
            .isEqualTo(HttpStatus.CONFLICT);

        assertThat(count("SELECT count(*) FROM staff WHERE school_id = ? AND email = ?",
            cbse().id(), "should.not.exist@oakridge.test")).isZero();
    }

    /**
     * Making a year current is {@code academic_year.manage}, the same gate as
     * closing one. It is deliberately not {@code structure.manage}: which year
     * the school points at decides what every date-scoped read answers, and
     * that is the year's own lifecycle rather than the shape of the school.
     */
    @Test
    @DisplayName("making a year current is academic_year.manage, not structure.manage")
    void activatingAYearIsAcademicYearManagement() {
        assertThat(post("/v1/tenancy/academic-years/" + cbse().currentAy().id() + "/activate",
            body(), teacherToken(cbse(), 1)).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);

        // The head of the school holds it. Activating the year that is already
        // current is the no-op case, so this asserts the gate without moving
        // the fixture's notion of "this year" for the scenarios that follow.
        assertThat(post("/v1/tenancy/academic-years/" + cbse().currentAy().id() + "/activate",
            body(), principalToken(cbse())).getStatusCode())
            .isEqualTo(HttpStatus.OK);
        assertThat(queryOne("SELECT is_current FROM academic_year WHERE id = ?", Boolean.class,
            cbse().currentAy().id())).isTrue();
    }

    /**
     * Handing a chain over is the operator's, and only the operator's. A chain
     * admin appointing another chain admin would be a chain's HQ growing its
     * own membership, which is a real need with no endpoint yet; what it must
     * not be is this one, which exists because a chain with nobody in it has
     * nobody to ask.
     */
    @Test
    @DisplayName("appointing a chain's HQ administrator is platform-admin only")
    void chainHandoverIsPlatformAdminOnly() {
        String hq = chainAdminToken();
        String principal = principalToken(cbse());

        // The chain's own admin is refused at the filter: this path is not one
        // of CHAIN_ADMIN_PREFIXES, so the subject type never reaches the gate.
        assertThat(post("/v1/platform-admin/chains/" + seed.chainId() + "/admins",
            body("email", "should.not.exist.hq@oakridge.test"), hq).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);

        // And a school's own head, who holds every permission inside their
        // school, holds nothing above it.
        assertThat(post("/v1/platform-admin/chains/" + seed.chainId() + "/admins",
            body("email", "should.not.exist.hq@oakridge.test"), principal).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/v1/platform-admin/chains/" + seed.chainId() + "/admins", principal).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(count("SELECT count(*) FROM user_account WHERE email = ?",
            "should.not.exist.hq@oakridge.test")).isZero();
    }

    /**
     * The operators' trail says who at Schoolsoft looked at which customer. It
     * is Schoolsoft's to read; a customer's own log is {@code /v1/audit}.
     */
    /**
     * A family may ask; only the head and the registrar may answer. Serving
     * an erasure removes a person from the record, so it is not handed to
     * whoever happens to hold a read of the student list.
     */
    @Test
    @DisplayName("data requests are the family's to file and the registrar's to serve")
    void dataRequestsAreServedByTheOfficeOnly() {
        UUID studentId = queryOne("SELECT student_id FROM enrolment WHERE section_id = ? ORDER BY roll_no "
            + "LIMIT 1", UUID.class, currentFocusSection(cbse()));
        String guardian = guardianTokenFor(cbse(), studentId);
        UUID nobody = UUID.randomUUID();

        assertThat(get("/v1/privacy/requests", registrarToken(cbse())).getStatusCode())
            .isEqualTo(HttpStatus.OK);
        assertThat(get("/v1/privacy/requests", principalToken(cbse())).getStatusCode())
            .isEqualTo(HttpStatus.OK);
        assertThat(get("/v1/privacy/students/" + studentId + "/consents", guardian).getStatusCode())
            .isEqualTo(HttpStatus.OK);

        assertThat(get("/v1/privacy/requests", accountantToken(cbse())).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/v1/privacy/students/" + studentId + "/consents", teacherToken(cbse(), 1))
            .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post("/v1/privacy/requests/" + nobody + "/fulfil", body("reason", "rbac"), guardian)
            .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post("/v1/privacy/requests/" + nobody + "/refuse", body("reason", "rbac"), guardian)
            .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("the operators' audit trail is platform-admin only")
    void operatorTrailIsPlatformAdminOnly() {
        assertThat(get("/v1/platform-admin/audit", platformAdminToken()).getStatusCode())
            .isEqualTo(HttpStatus.OK);
        assertThat(get("/v1/platform-admin/audit", chainAdminToken()).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/v1/platform-admin/audit", principalToken(cbse())).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    /**
     * The chain's HQ accounts are the chain admin's to manage, and nobody
     * else's. A school's head holds every permission inside their school and
     * nothing above it; an operator hands a chain over once, through their own
     * console, and has no business on the customer's screen afterwards.
     */
    @Test
    @DisplayName("managing a chain's HQ accounts is chain.admin.manage, held by the chain admin alone")
    void chainHqAccountsAreTheChainAdmins() {
        assertThat(get("/v1/tenancy/chain/admins", chainAdminToken()).getStatusCode())
            .isEqualTo(HttpStatus.OK);

        String principal = principalToken(cbse());
        assertThat(get("/v1/tenancy/chain/admins", principal).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post("/v1/tenancy/chain/admins",
            body("email", "should.not.exist.hq2@oakridge.test"), principal).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post("/v1/tenancy/chain/admins",
            body("email", "should.not.exist.hq2@oakridge.test"), teacherToken(cbse(), 1)).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(count("SELECT count(*) FROM user_account WHERE email = ?",
            "should.not.exist.hq2@oakridge.test")).isZero();
    }

    /**
     * The one that is not about permissions.
     *
     * <p>A chain admin's token carries no school, and V009's policy reads
     * {@code school_id = current_school_id() OR current_school_id() IS NULL} —
     * so a school-scoped read made with this token is not refused and not
     * empty. It returns <em>every school in the chain</em>, merged, because
     * RLS stands aside for a session that has no school and the chain admin
     * holds every unrestricted read in the baseline. Nothing in the
     * authorization model says no.</p>
     *
     * <p>{@code TenantResolverFilter.CHAIN_ADMIN_PREFIXES} is what says no,
     * and it says it before the handler runs, which is why the refusal here
     * is the filter's {@code chain_admin_scope} rather than a 403 from a
     * {@code @PreAuthorize}. The two schools in the fixture are what make the
     * test meaningful: without the gate the read below answers 200 with both
     * schools' children in one list.</p>
     */
    @Test
    @DisplayName("a chain admin cannot reach a read built for one school")
    void chainAdminCannotReachASchoolScopedRead() {
        String hq = chainAdminToken();

        for (String schoolScoped : List.of(
                "/v1/people/students?schoolId=" + cbse().id(),
                "/v1/people/staff?schoolId=" + cbse().id(),
                "/v1/dashboards/schools/" + cbse().id() + "/overview",
                "/v1/audit?schoolId=" + cbse().id())) {
            var refused = get(schoolScoped, hq);
            assertThat(refused.getStatusCode())
                .describedAs("chain admin reaching %s", schoolScoped)
                .isEqualTo(HttpStatus.FORBIDDEN);
        }

        // The staff whose school it is still reads it. The gate is the subject
        // type's, not the endpoint's.
        assertThat(get("/v1/people/students?schoolId=" + cbse().id(), principalToken(cbse())).getStatusCode())
            .isEqualTo(HttpStatus.OK);
    }

    // ===================== linking a driver is what lets them drive =====================

    /**
     * BUG-18. A driver linked to a staff record used to be "linked" and still
     * refused by the driver app: the link granted nothing. Linking now grants
     * the {@code driver} role in the same transaction, and refuses a staff
     * member who already holds another role — the dev data had a driver linked
     * to the principal, which with the grant would hand her a second role.
     */
    @Test
    @DisplayName("linking a driver grants the driver role, unlinking takes it back, and neither touches another role")
    void linkingADriverGrantsTheDriverRole() {
        StaffLogin fresh = newStaffLogin();
        UUID staffId = fresh.staffId();
        String newDriver = fresh.token();
        String roster = "/v1/transport/routes/" + cbse().routeId() + "/students";

        // Refused at the gate: no transport.drive.
        var beforeLink = get(roster, newDriver);
        assertThat(beforeLink.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(beforeLink.getBody().get("message").asText()).doesNotContain("do not drive this route");

        var linked = post("/v1/transport/drivers?schoolId=" + cbse().id(),
            body("name", "New Driver", "staffId", staffId), principalToken(cbse()));
        assertThat(linked.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(count("SELECT count(*) FROM staff_role WHERE staff_id = ? AND role_code = 'driver' "
            + "AND revoked_at IS NULL", staffId)).isEqualTo(1);

        // Through the gate now; RouteScope is what says no, because nobody has
        // rostered them to R1 yet. That is the office's next step, not a gap.
        var afterLink = get(roster, newDriver);
        assertThat(afterLink.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(afterLink.getBody().get("message").asText()).contains("do not drive this route");

        // One staff record, one driver.
        assertThat(post("/v1/transport/drivers?schoolId=" + cbse().id(),
            body("name", "New Driver again", "staffId", staffId), principalToken(cbse())).getStatusCode())
            .isEqualTo(HttpStatus.CONFLICT);

        // The principal is not a driver, and linking one to her grants nothing.
        var wrongPerson = post("/v1/transport/drivers?schoolId=" + cbse().id(),
            body("name", "Suresh Babu", "staffId", cbse().principalStaffId()), principalToken(cbse()));
        assertThat(wrongPerson.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(count("SELECT count(*) FROM staff_role WHERE staff_id = ? AND role_code = 'driver'",
            cbse().principalStaffId())).isZero();
        assertThat(count("SELECT count(*) FROM driver WHERE staff_id = ?", cbse().principalStaffId())).isZero();

        // Unlinking takes the role back with the link; the driver row stays.
        UUID driverId = UUID.fromString(linked.getBody().get("id").asText());
        String unlink = "/v1/transport/drivers/" + driverId + "/unlink?schoolId=" + cbse().id();
        assertThat(post(unlink, body(), teacherToken(cbse(), 0)).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        var unlinked = post(unlink, body(), principalToken(cbse()));
        assertThat(unlinked.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(unlinked.getBody().hasNonNull("staffId")).isFalse();
        assertThat(count("SELECT count(*) FROM staff_role WHERE staff_id = ? AND role_code = 'driver' "
            + "AND revoked_at IS NULL", staffId)).isZero();
        var gateAgain = get(roster, newDriver);
        assertThat(gateAgain.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(gateAgain.getBody().get("message").asText()).doesNotContain("do not drive this route");
        // A retry of an unlink that already happened is not an error.
        assertThat(post(unlink, body(), principalToken(cbse())).getStatusCode()).isEqualTo(HttpStatus.OK);

        // Relinking the same row restores the grant, and refuses the principal just as creating did.
        String link = "/v1/transport/drivers/" + driverId + "/link?schoolId=" + cbse().id();
        assertThat(post(link, body("staffId", cbse().principalStaffId()), principalToken(cbse())).getStatusCode())
            .isEqualTo(HttpStatus.CONFLICT);
        assertThat(post(link, body("staffId", staffId), teacherToken(cbse(), 0)).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post(link, body("staffId", staffId), principalToken(cbse())).getStatusCode())
            .isEqualTo(HttpStatus.OK);
        assertThat(count("SELECT count(*) FROM staff_role WHERE staff_id = ? AND role_code = 'driver' "
            + "AND revoked_at IS NULL", staffId)).isEqualTo(1);
    }

    /**
     * A {@code driver} grant made on purpose on the Roles screen is not the
     * link's, and unlinking the driver leaves it alone (V042). Before, the
     * unlink could not tell the two apart and revoked both.
     */
    @Test
    @DisplayName("unlinking a driver takes back only the role the link granted")
    void unlinkingLeavesAHandMadeDriverGrant() {
        String principal = principalToken(cbse());
        StaffLogin handMade = newStaffLogin();
        assertThat(post("/v1/iam/staff-roles/assign", body("staffId", handMade.staffId(), "schoolId", cbse().id(),
            "roleCode", "driver", "reason", "drives the late bus"), principal).getStatusCode())
            .isEqualTo(HttpStatus.NO_CONTENT);
        UUID driverId = UUID.fromString(post("/v1/transport/drivers?schoolId=" + cbse().id(),
            body("name", "Hand Made", "staffId", handMade.staffId()), principal).getBody().get("id").asText());
        post("/v1/transport/drivers/" + driverId + "/unlink?schoolId=" + cbse().id(), body(), principal);
        assertThat(count("SELECT count(*) FROM staff_role WHERE staff_id = ? AND role_code = 'driver' "
            + "AND revoked_at IS NULL", handMade.staffId())).isEqualTo(1);

        // The link's own grant does go, and a later hand grant over a link's claims it.
        StaffLogin linked = newStaffLogin();
        UUID linkedDriver = UUID.fromString(post("/v1/transport/drivers?schoolId=" + cbse().id(),
            body("name", "Linked", "staffId", linked.staffId()), principal).getBody().get("id").asText());
        post("/v1/iam/staff-roles/assign", body("staffId", linked.staffId(), "schoolId", cbse().id(),
            "roleCode", "driver", "reason", "keeps driving after the link"), principal);
        post("/v1/transport/drivers/" + linkedDriver + "/unlink?schoolId=" + cbse().id(), body(), principal);
        assertThat(count("SELECT count(*) FROM staff_role WHERE staff_id = ? AND role_code = 'driver' "
            + "AND revoked_at IS NULL", linked.staffId())).isEqualTo(1);
    }

    // ===================== who drives which route, and from when =====================

    /**
     * {@code route_assignment} is what {@code RouteScope} reads. Assigning a
     * route from a day replaces whoever drove it then; the old window closes
     * the day before and is kept. Windows shorten, never lengthen; only an
     * assignment that has not started may be deleted. The route and vehicle
     * are the test's own, so R1's roster stays the fixture's.
     */
    @Test
    @DisplayName("a route assignment decides whose bus, and replaces rather than deletes")
    void routeAssignmentDecidesWhoseBus() {
        String principal = principalToken(cbse());
        String sfx = UUID.randomUUID().toString().substring(0, 6);
        UUID routeId = UUID.fromString(post("/v1/transport/routes?schoolId=" + cbse().id(),
            body("code", "RA" + sfx, "name", "Assignment " + sfx, "direction", "pickup"), principal)
            .getBody().get("id").asText());
        UUID vehicleId = UUID.fromString(post("/v1/transport/vehicles?schoolId=" + cbse().id(),
            body("registrationNo", "TS-RA-" + sfx, "capacity", 30), principal).getBody().get("id").asText());
        StaffLogin first = newStaffLogin();
        StaffLogin second = newStaffLogin();
        UUID firstDriver = UUID.fromString(post("/v1/transport/drivers?schoolId=" + cbse().id(),
            body("name", "First " + sfx, "staffId", first.staffId()), principal).getBody().get("id").asText());
        UUID secondDriver = UUID.fromString(post("/v1/transport/drivers?schoolId=" + cbse().id(),
            body("name", "Second " + sfx, "staffId", second.staffId()), principal).getBody().get("id").asText());
        String roster = "/v1/transport/routes/" + routeId + "/students";
        java.time.LocalDate today = java.time.LocalDate.now();

        // A route nobody drives is the office's warning, from today.
        assertThat(gapFor(routeId, principal)).isEqualTo(today + "|null");

        // Only the office rosters.
        assertThat(post("/v1/transport/route-assignments", body("schoolId", cbse().id(), "routeId", routeId,
            "vehicleId", vehicleId, "driverId", firstDriver, "effectiveFrom", today.toString()),
            teacherToken(cbse(), 0)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // Another school's route is not this school's to roster.
        assertThat(post("/v1/transport/route-assignments", body("schoolId", cbse().id(), "routeId", cie().routeId(),
            "vehicleId", vehicleId, "driverId", firstDriver, "effectiveFrom", today.toString()), principal)
            .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        var assigned = post("/v1/transport/route-assignments", body("schoolId", cbse().id(), "routeId", routeId,
            "vehicleId", vehicleId, "driverId", firstDriver, "effectiveFrom", today.toString()), principal);
        assertThat(assigned.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID firstWindow = UUID.fromString(assigned.getBody().get("id").asText());
        assertThat(get(roster, first.token()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(roster, second.token()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(gapFor(routeId, principal)).isNull();

        // Tomorrow somebody else drives it: today's window closes today, and today is still first's.
        var replaced = post("/v1/transport/route-assignments", body("schoolId", cbse().id(), "routeId", routeId,
            "vehicleId", vehicleId, "driverId", secondDriver, "effectiveFrom", today.plusDays(1).toString()),
            principal);
        assertThat(replaced.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID secondWindow = UUID.fromString(replaced.getBody().get("id").asText());
        var windows = get("/v1/transport/route-assignments?schoolId=" + cbse().id() + "&routeId=" + routeId,
            principal).getBody();
        assertThat(windows).hasSize(2);
        for (var w : windows) {
            if (w.get("id").asText().equals(firstWindow.toString())) {
                assertThat(w.get("effectiveTo").asText()).isEqualTo(today.toString());
            }
        }
        assertThat(get(roster, first.token()).getStatusCode()).isEqualTo(HttpStatus.OK);

        // A second replacement from the same day overlaps the one already there.
        assertThat(post("/v1/transport/route-assignments", body("schoolId", cbse().id(), "routeId", routeId,
            "vehicleId", vehicleId, "driverId", firstDriver, "effectiveFrom", today.plusDays(1).toString()),
            principal).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // Not started: deletable. Started: ended instead.
        assertThat(delete("/v1/transport/route-assignments/" + secondWindow + "?schoolId=" + cbse().id(),
            principal).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(delete("/v1/transport/route-assignments/" + firstWindow + "?schoolId=" + cbse().id(),
            principal).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        String end = "/v1/transport/route-assignments/" + firstWindow + "/end";
        assertThat(post(end, body("schoolId", cbse().id(), "lastDay", today.minusDays(1).toString()), principal)
            .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(end, body("schoolId", cbse().id(), "lastDay", today.toString()), principal)
            .getStatusCode()).isEqualTo(HttpStatus.OK);
        // Ended with nothing after it: no driver from tomorrow, and nobody picks it up.
        assertThat(gapFor(routeId, principal)).isEqualTo(today.plusDays(1) + "|null");
        assertThat(post(end, body("schoolId", cbse().id(), "lastDay", today.plusDays(5).toString()), principal)
            .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    /** The route's first uncovered day and when it is covered again, as "from|to", or null when it has none. */
    private String gapFor(UUID routeId, String token) {
        for (var g : get("/v1/transport/route-assignments/gaps?schoolId=" + cbse().id(), token).getBody()) {
            if (g.get("routeId").asText().equals(routeId.toString())) {
                return g.get("uncoveredFrom").asText() + "|"
                    + (g.hasNonNull("coveredAgainOn") ? g.get("coveredAgainOn").asText() : "null");
            }
        }
        return null;
    }

    /**
     * One driver, or one vehicle, on two routes running the same way on the
     * same days is warned of, not refused: routes carry no times yet, so two
     * pickups may run back to back. A pickup and a drop are the normal day.
     */
    @Test
    @DisplayName("a driver or vehicle on two same-way routes at once is a warning, not a refusal")
    void sameWayRoutesClashAsAWarning() {
        String principal = principalToken(cbse());
        String sfx = UUID.randomUUID().toString().substring(0, 6);
        java.util.function.BiFunction<String, String, UUID> route = (code, direction) -> UUID.fromString(
            post("/v1/transport/routes?schoolId=" + cbse().id(), body("code", code + sfx, "name", code + " " + sfx,
                "direction", direction), principal).getBody().get("id").asText());
        java.util.function.Function<String, UUID> vehicle = reg -> UUID.fromString(
            post("/v1/transport/vehicles?schoolId=" + cbse().id(), body("registrationNo", reg + sfx, "capacity", 30),
                principal).getBody().get("id").asText());
        UUID morningA = route.apply("CA", "pickup");
        UUID morningB = route.apply("CB", "pickup");
        UUID evening = route.apply("CC", "drop");
        UUID busOne = vehicle.apply("TS-C1-");
        UUID busTwo = vehicle.apply("TS-C2-");
        UUID driverId = UUID.fromString(post("/v1/transport/drivers?schoolId=" + cbse().id(),
            body("name", "Clash " + sfx, "staffId", newStaffLogin().staffId()), principal).getBody().get("id").asText());
        String today = java.time.LocalDate.now().toString();
        java.util.function.BiFunction<UUID, UUID, java.util.Map<String, Object>> req = (routeId, bus) -> body(
            "schoolId", cbse().id(), "routeId", routeId, "vehicleId", bus, "driverId", driverId, "effectiveFrom", today);

        assertThat(post("/v1/transport/route-assignments", req.apply(morningA, busOne), principal).getStatusCode())
            .isEqualTo(HttpStatus.OK);

        // Only the office asks; asking changes nothing.
        assertThat(post("/v1/transport/route-assignments/check", req.apply(morningB, busTwo),
            teacherToken(cbse(), 0)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Same driver, another pickup, another bus: the driver clashes, the bus does not.
        var warned = post("/v1/transport/route-assignments/check", req.apply(morningB, busTwo), principal).getBody();
        assertThat(warned).hasSize(1);
        assertThat(warned.get(0).get("kind").asText()).isEqualTo("driver");
        assertThat(warned.get(0).get("firstRouteCode").asText()).isEqualTo("CA" + sfx);
        assertThat(warned.get(0).get("secondRouteCode").asText()).isEqualTo("CB" + sfx);
        assertThat(count("SELECT count(*) FROM route_assignment WHERE route_id = ?", morningB)).isZero();

        // A drop in the same bus with the same driver is the ordinary day.
        assertThat(post("/v1/transport/route-assignments/check", req.apply(evening, busOne), principal).getBody())
            .isEmpty();

        // Saved anyway, the clash stays on the office's list until somebody resolves it.
        assertThat(post("/v1/transport/route-assignments", req.apply(morningB, busOne), principal).getStatusCode())
            .isEqualTo(HttpStatus.OK);
        var standing = get("/v1/transport/route-assignments/clashes?schoolId=" + cbse().id(), principal).getBody();
        long ours = 0;
        for (var c : standing) {
            if (c.get("firstRouteCode").asText().endsWith(sfx)) {
                ours++;
                assertThat(c.get("from").asText()).isEqualTo(today);
                assertThat(c.hasNonNull("to")).isFalse();
            }
        }
        assertThat(ours).describedAs("driver and vehicle both on two pickups").isEqualTo(2);
    }

    private record StaffLogin(UUID staffId, UUID accountId, String token) {}

    /** A staff member of CBSE with a sign-in and no role, the test's own. */
    private StaffLogin newStaffLogin() {
        UUID staffId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        String suffix = staffId.toString().substring(0, 8);
        inChainDo(jdbc -> {
            jdbc.update(
                "INSERT INTO staff (id, school_id, employee_no, first_name, last_name, email, "
                + "employment_type, joined_on) VALUES (?, ?, ?, 'New', 'Driver', ?, 'contract', current_date)",
                staffId, cbse().id(), "EMP-DRV-" + suffix, "drv-" + suffix + "@cert.test");
            jdbc.update(
                "INSERT INTO user_account (id, school_id, subject_type, subject_id, email) "
                + "VALUES (?, ?, 'staff', ?, ?)",
                accountId, cbse().id(), staffId, "drv-" + suffix + "@cert.test");
        });
        return new StaffLogin(staffId, accountId, tokenFor(cbse(), accountId, "staff"));
    }

    // ===================== the driver's bus, and only the driver's bus =====================

    /**
     * The last of GAP-31's neighbours. {@code driver} held school-wide
     * {@code student.view} because the roster returned bare ids and the app
     * read each rider out of {@code /v1/people/students/&#123;id&#125;} — a
     * whole school's student directory, on a bus. V029 revokes the grant; the
     * roster carries the names instead.
     */
    @Test
    @DisplayName("a driver reads their route's riders by name and no student out of the directory")
    void driverReadsRidersWithoutTheStudentDirectory() {
        String driver = driverToken(cbse());

        var roster = get("/v1/transport/routes/" + cbse().routeId() + "/students", driver);
        assertThat(roster.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(roster.getBody()).isNotEmpty();
        assertThat(roster.getBody().get(0).get("firstName").asText()).isNotBlank();
        assertThat(roster.getBody().get(0).get("admissionNo").asText()).isNotBlank();

        UUID rider = UUID.fromString(roster.getBody().get(0).get("studentId").asText());
        assertThat(get("/v1/people/students/" + rider, driver).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/v1/people/students?schoolId=" + cbse().id(), driver).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    /**
     * {@code transport.drive} says a driver may run a trip. It does not say
     * whose. Confinement comes from {@code route_assignment}, so the other
     * school's route is refused even though the token is a valid driver's —
     * and trip start is scoped too, or a driver could mint themselves a route
     * by starting a trip on it and then reading its roster.
     */
    @Test
    @DisplayName("a driver is confined to the routes they are rostered to drive")
    void driverIsConfinedToTheirOwnRoutes() {
        String driver = driverToken(cbse());
        UUID someoneElsesRoute = cie().routeId();

        assertThat(get("/v1/transport/routes/" + someoneElsesRoute + "/students", driver).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post("/v1/transport/trips/start", body(
            "schoolId", cie().id(), "routeId", someoneElsesRoute,
            "vehicleId", UUID.randomUUID(), "driverId", UUID.randomUUID(),
            "direction", "pickup"), driver).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    /**
     * The scope narrows a driver and nobody else. The office assigns riders to
     * routes and has to see every one of them — and holds no {@code driver}
     * record at all, so a scope that confined it would return nothing rather
     * than everything.
     */
    @Test
    @DisplayName("the office is not route-confined")
    void transportManagerIsNotConfined() {
        var roster = get("/v1/transport/routes/" + cbse().routeId() + "/students", principalToken(cbse()));

        assertThat(roster.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(roster.getBody()).isNotEmpty();
    }

    /**
     * The public site posts an application with no token at all. The gate on
     * those handlers is {@code permitAll()}, and it has to keep working — a
     * regression here is a school that stops taking admissions.
     */
    @Test
    @DisplayName("the public endpoints stay reachable without a token")
    void publicEndpointsNeedNoToken() {
        var school = get("/v1/public/schools/" + seed.chainSlug() + "/" + cbse().slug(), null);
        assertThat(school.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * The sign-in page has to know which school it is drawing before anybody
     * has a token, so the host resolver answers without one. What it must never
     * do is answer a question nobody asked: it resolves one named host, and an
     * address no tenant has claimed is a 404, not a hint. There is no listing
     * endpoint to test the absence of, and that is the point — a school picker
     * would publish the customer list to every visitor of every login page.
     */
    @Test
    @DisplayName("the host resolver answers without a token, and only about a host you already named")
    void tenantResolutionNeedsNoToken() {
        var school = get("/v1/public/tenant?host=oakridge." + seed.chainSlug() + ".schoolsoft.app", null);
        assertThat(school.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(school.getBody().path("schoolName").asText()).isEqualTo("Oakridge Public School");

        var hq = get("/v1/public/tenant?host=" + seed.chainSlug() + ".schoolsoft.app", null);
        assertThat(hq.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(hq.getBody().path("kind").asText()).isEqualTo("chain_hq");
        // A chain's HQ door names no school: it is the door above all of them.
        assertThat(hq.getBody().path("schoolName").asText("")).isEmpty();

        var stranger = get("/v1/public/tenant?host=nobody.example.invalid", null);
        assertThat(stranger.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(stranger.getBody().toString()).doesNotContain(seed.chainSlug());
    }

    @Test
    @DisplayName("an authenticated endpoint refuses a request with no token")
    void authenticatedEndpointsNeedOne() {
        assertThat(get("/v1/people/students?schoolId=" + cbse().id(), null).getStatusCode())
            .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ===================== the leavers' desk =====================

    /**
     * Processing an exit and forgiving its arrears are deliberately different
     * permissions. The registrar does the first all day and must not be able to
     * do the second — a clerk who can waive the balance on the way out is a
     * school with no arrears policy.
     */
    @Test
    @DisplayName("the registrar processes an exit but cannot waive its dues")
    void registrarCannotWaiveDues() {
        perms("registrar").contains("withdrawal.manage").doesNotContain("withdrawal.override");
        perms("accountant").contains("withdrawal.override").doesNotContain("withdrawal.manage");
    }

    /**
     * A certificate is a statutory document with the school's name on it. The
     * counter answers "has it come through yet" and cannot issue one, and the
     * librarian — who resolves somebody else's checklist line — cannot either.
     */
    @Test
    @DisplayName("only the office issues a certificate")
    void certificateIssueIsNarrow() {
        perms("front_office").contains("certificate.view").doesNotContain("certificate.issue");
        perms("librarian").doesNotContain("certificate.issue", "certificate.revoke");
        perms("registrar").contains("certificate.issue").doesNotContain("certificate.revoke");
        perms("principal").contains("certificate.issue", "certificate.revoke");
    }

    /**
     * A guardian holds {@code certificate.view.own} and nothing wider: the exit
     * that produced the certificate, and every other family's, stay closed.
     */
    @Test
    @DisplayName("a guardian reads their own child's certificates and no withdrawal")
    void guardianReadsOwnCertificatesOnly() {
        UUID studentId = firstStudentIn(currentFocusSection(cbse()));
        String guardian = guardianTokenFor(cbse(), studentId);

        assertThat(get("/v1/certificates/students/" + studentId, guardian).getStatusCode())
            .isEqualTo(HttpStatus.OK);
        assertThat(get("/v1/certificates?schoolId=" + cbse().id(), guardian).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/v1/enrolment/withdrawals/students/" + studentId, guardian).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post("/v1/certificates", body(
            "studentId", studentId, "kind", "bonafide"), guardian).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ===================== the admissions funnel =====================

    /**
     * A move through the funnel answers to two gates, not one: the endpoint's
     * {@code admission.decide}, and the permission the <em>particular move</em>
     * carries in {@code admission_transition}. {@code accepted -> enrolled} is
     * the one that differs — it requires {@code admission.enrol}, because
     * confirming a seat creates a student, a guardian and a login.
     *
     * <p>No seeded role separates the two (registrar and principal hold both),
     * which is the point: the separation exists for a school that writes its own
     * counsellor role, and {@code role_perm} lets it without a deploy. So this
     * builds that role rather than borrowing one, and gives it its own staff
     * member — the fixture is shared, and confining a neighbour's permissions
     * would follow them into every other scenario.</p>
     */
    @Test
    @DisplayName("a counsellor moves an application but cannot enrol it")
    void counsellorDecidesButCannotEnrol() {
        UUID counsellorUser = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        String roleCode = "counsellor_rbac_probe";

        inChainDo(jdbc -> {
            // `role` is chain-wide and carries no school_id — a custom role
            // belongs to the chain, and staff_role scopes it to one school.
            jdbc.update("INSERT INTO role (id, code, name, screen_keys, is_system) "
                + "VALUES (?, ?, 'Admissions counsellor (probe)', '{admissions}', FALSE) "
                + "ON CONFLICT DO NOTHING",
                UUID.randomUUID(), roleCode);
            // Everything the funnel needs, and deliberately not admission.enrol.
            for (String perm : List.of("admission.view", "admission.manage", "admission.decide",
                    "structure.view", "student.view")) {
                jdbc.update("INSERT INTO role_perm (role_code, perm_code) VALUES (?, ?) "
                    + "ON CONFLICT DO NOTHING", roleCode, perm);
            }
            jdbc.update("INSERT INTO staff (id, school_id, employee_no, first_name, last_name, "
                + "  campus_id, is_active) "
                + "VALUES (?, ?, ?, 'Probe', 'Counsellor', "
                + "  (SELECT id FROM campus WHERE school_id = ? ORDER BY is_primary DESC LIMIT 1), TRUE)",
                staffId, cbse().id(), "PROBE-" + staffId.toString().substring(0, 8), cbse().id());
            jdbc.update("INSERT INTO staff_role (staff_id, role_code, scope_type, scope_id) "
                + "VALUES (?, ?, 'school', ?) ON CONFLICT DO NOTHING", staffId, roleCode, cbse().id());
            jdbc.update("INSERT INTO user_account (id, school_id, subject_type, subject_id, email) "
                + "VALUES (?, ?, 'staff', ?, ?)",
                counsellorUser, cbse().id(), staffId, "probe.counsellor." + staffId + "@example.test");
        });

        String counsellor = tokenFor(cbse(), counsellorUser, "staff");
        UUID id = UUID.fromString(post("/v1/admissions/applications", body(
            "schoolId", cbse().id(), "academicYearId", cbse().currentAy().id(),
            "gradeId", gradeOf(cbse(), "1"),
            "applicationNo", "RBAC-" + UUID.randomUUID().toString().substring(0, 8),
            "applicantFirstName", "Probe", "applicantLastName", "Applicant",
            "guardianName", "Probe Guardian", "guardianPhone", "919000000042",
            "source", "walkin"), counsellor).getBody().get("id").asText());

        // Positive: every move the funnel calls admission.decide, they make.
        for (String state : List.of("application_started", "document_pending", "fee_pending", "review",
                "test_scheduled", "test_done", "offered", "accepted")) {
            assertThat(post("/v1/admissions/applications/" + id + "/transition",
                Map.of("toState", state), counsellor).getStatusCode())
                .as("counsellor moving to " + state)
                .isEqualTo(HttpStatus.OK);
        }

        // Negative: the last step is the one they may not take — through the
        // transition endpoint, whose own gate they pass...
        assertThat(post("/v1/admissions/applications/" + id + "/transition",
            Map.of("toState", "enrolled"), counsellor).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);

        // ...and through /enrol, which they never reach.
        assertThat(post("/v1/admissions/applications/" + id + "/enrol",
            Map.of("sectionId", sectionOf(cbse(), cbse().currentAy().code(), "1", "A")), counsellor)
            .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Refused twice, and still sitting where the counsellor left it. The
        // seat stays unconfirmed rather than half-confirmed.
        assertThat(queryOne("SELECT state FROM admission_application WHERE id = ?", String.class, id))
            .isEqualTo("accepted");
        assertThat(count("SELECT count(*) FROM admission_application WHERE id = ? "
            + "AND converted_student_id IS NOT NULL", id)).isZero();

        // The registrar holds admission.enrol and would finish this — proven by
        // cert_ADM_11, which converts. Not repeated here: this test runs
        // immediately before FixtureSmokeTest, whose seed counts an extra active
        // enrolment would spoil.
    }

    /**
     * Whether the school holds an entrance test is a setup decision, not a step
     * in working the funnel. The front office creates applications all day and
     * must not be able to reshape the funnel those applications move through —
     * turning the test off would silently close every {@code test_scheduled}
     * move for the whole school, for everyone.
     */
    @Test
    @DisplayName("working the funnel and reconfiguring it are different permissions")
    void reconfiguringTheFunnelIsItsOwnPermission() {
        perms("front_office").contains("admission.manage").doesNotContain("admission.policy.manage");
        perms("registrar").contains("admission.manage", "admission.policy.manage");
        perms("librarian").doesNotContain("admission.policy.manage", "admission.view");

        // Reading the policy comes with reading the funnel — the board needs it
        // to know which lanes exist.
        assertThat(get("/v1/admissions/policy?schoolId=" + cbse().id(), registrarToken(cbse()))
            .getStatusCode()).isEqualTo(HttpStatus.OK);

        // A staff member outside admissions reaches neither half.
        String librarian = librarianToken(cbse());
        assertThat(get("/v1/admissions/policy?schoolId=" + cbse().id(), librarian).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(put("/v1/admissions/policy", body(
            "schoolId", cbse().id(), "entranceTestRequired", false, "offerValidityDays", 30),
            librarian).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Pricing admission is the same kind of decision, behind the same gate:
        // read with the funnel, set only by whoever configures it.
        String fees = "/v1/admissions/fees?schoolId=" + cbse().id() + "&academicYearId="
            + cbse().currentAy().id();
        assertThat(get(fees, registrarToken(cbse())).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(fees, librarian).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(put("/v1/admissions/fees", body(
            "schoolId", cbse().id(), "academicYearId", cbse().currentAy().id(),
            "gradeId", gradeOf(cbse(), "1"), "amount", 1.0), librarian).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(fees, registrarToken(cbse())).getBody()).isEmpty();

        // Unchanged, so no other scenario's funnel moved under it. Read through
        // the API rather than the table: a school provisioned after the
        // migration has no row yet and answers from the column defaults until
        // somebody saves one.
        assertThat(get("/v1/admissions/policy?schoolId=" + cbse().id(), registrarToken(cbse()))
            .getBody().get("entranceTestRequired").asBoolean()).isTrue();
    }

    // ===================== opening a school =====================

    /**
     * {@code school.onboard} is the structural permission with the widest
     * blast radius on a school nobody is in yet: it decides when families and
     * teachers are let through the door. The office reads the checklist; only
     * the heads and the chain's HQ admin act on it.
     */
    @Test
    @DisplayName("a registrar reads the setup checklist and cannot open the school")
    void registrarReadsReadinessAndCannotGoLive() {
        String registrar = registrarToken(cbse());

        assertThat(get("/v1/tenancy/schools/" + cbse().id() + "/readiness", registrar).getStatusCode())
            .isEqualTo(HttpStatus.OK);
        assertThat(post("/v1/tenancy/schools/" + cbse().id() + "/go-live", null, registrar).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post("/v1/tenancy/schools/" + cbse().id() + "/steps/theme/skip",
            body("reason", "a registrar should not be able to do this"), registrar).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(count("SELECT count(*) FROM school_onboarding_skip WHERE school_id = ?",
            cbse().id())).isZero();
    }

    /**
     * {@code school.onboard} is two acts, and a head of school holds it for
     * the second one only. Creating a school from inside a school is refused
     * with the reason, rather than by the INSERT hitting V009's policy and
     * answering 500 about a constraint the caller cannot see.
     */
    @Test
    @DisplayName("a principal cannot create a school from inside their own")
    void principalCannotCreateASchool() {
        var refused = post("/v1/tenancy/schools", body(
            "slug", "rbac-probe-school",
            "name", "Rbac Probe School",
            "boardCode", "CBSE",
            "stateCode", "KA"), principalToken(cbse()));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody().get("message").asText()).contains("HQ console");
        assertThat(count("SELECT count(*) FROM school WHERE slug = 'rbac-probe-school'")).isZero();
    }

    /** The positive half: the gate is a gate, not a wall. */
    @Test
    @DisplayName("a principal skips an optional setup step and puts it back")
    void principalSkipsAnOptionalSetupStep() {
        String principal = principalToken(cbse());
        try {
            var skipped = post("/v1/tenancy/schools/" + cbse().id() + "/steps/fee_structure/skip",
                body("reason", "rbac enforcement test"), principal);
            assertThat(skipped.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(count("SELECT count(*) FROM school_onboarding_skip WHERE school_id = ? "
                + "AND step_key = 'fee_structure'", cbse().id())).isEqualTo(1);
        } finally {
            post("/v1/tenancy/schools/" + cbse().id() + "/steps/fee_structure/unskip", null, principal);
        }
        assertThat(count("SELECT count(*) FROM school_onboarding_skip WHERE school_id = ?",
            cbse().id())).isZero();
    }

    /**
     * A guardian is inside the school and still nowhere near this: opening a
     * school is not a thing a family does, and the checklist is not theirs to
     * read either.
     */
    @Test
    @DisplayName("a guardian cannot read or act on the setup checklist")
    void guardianIsNowhereNearOnboarding() {
        UUID studentId = firstStudentIn(currentFocusSection(cbse()));
        String guardian = guardianTokenFor(cbse(), studentId);

        assertThat(post("/v1/tenancy/schools/" + cbse().id() + "/go-live", null, guardian).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    private org.assertj.core.api.ListAssert<String> perms(String roleCode) {
        return org.assertj.core.api.Assertions.assertThat(
            queryList("SELECT perm_code FROM role_perm WHERE role_code = ?", String.class, roleCode));
    }

    // ===================== hiring and exiting staff =====================

    /**
     * GAP-44. {@code staff.manage} is the heads' alone. The registrar reads
     * the staff list and keeps the student register, and neither of those is
     * putting somebody on the payroll or taking them off it; a family and a
     * teacher are further away still.
     */
    @Test
    @DisplayName("hiring, editing and exiting staff is staff.manage, held by the heads alone")
    void staffLifecycleIsTheHeads() {
        UUID someone = cbse().teacherStaffIds().get(5);
        UUID child = firstStudentIn(currentFocusSection(cbse()));

        for (String refused : List.of(
                registrarToken(cbse()), accountantToken(cbse()), teacherToken(cbse(), 0),
                guardianTokenFor(cbse(), child))) {
            assertThat(post("/v1/people/staff", body("schoolId", cbse().id(), "firstName", "Should",
                "email", "should.not.exist@rbac.cert.test"), refused).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(put("/v1/people/staff/" + someone, body("firstName", "Renamed",
                "email", "renamed@rbac.cert.test", "version", 0), refused).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(get("/v1/people/staff/" + someone + "/duties?lastWorkingDate=2026-12-31", refused)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(post("/v1/people/staff/" + someone + "/exit", body("lastWorkingDate", "2026-12-31",
                "reason", "rbac enforcement test", "version", 0), refused).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThat(count("SELECT count(*) FROM staff WHERE email = 'should.not.exist@rbac.cert.test'")).isZero();
        assertThat(count("SELECT count(*) FROM staff WHERE id = ? AND left_on IS NULL AND first_name <> 'Renamed'",
            someone)).isEqualTo(1);

        // The head reads what a colleague holds, and cannot reach the sibling school's staff.
        assertThat(get("/v1/people/staff/" + someone + "/duties?lastWorkingDate=2026-12-31",
            principalToken(cbse())).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/v1/people/staff/" + cie().teacherStaffIds().get(0) + "/duties?lastWorkingDate=2026-12-31",
            principalToken(cbse())).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
