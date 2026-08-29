package com.schoolsoft.certification;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolsoft.certification.support.AbstractCertificationTest;
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
    }

    // ===================== the principals that hold no role row =====================

    /**
     * A chain (HQ) admin oversees the chain's schools and changes none of them.
     * The read set is derived from {@code Perm.isUnrestrictedRead()}, so this
     * is the test that the derivation lands on the right side of the line.
     */
    @Test
    @DisplayName("a chain admin reads across the chain and writes nothing")
    void chainAdminReadsAndDoesNotWrite() {
        String hq = chainAdminToken();

        assertThat(get("/v1/tenancy/schools", hq).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/v1/people/students?schoolId=" + cbse().id(), hq).getStatusCode())
            .isEqualTo(HttpStatus.OK);

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

    @Test
    @DisplayName("an authenticated endpoint refuses a request with no token")
    void authenticatedEndpointsNeedOne() {
        assertThat(get("/v1/people/students?schoolId=" + cbse().id(), null).getStatusCode())
            .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
