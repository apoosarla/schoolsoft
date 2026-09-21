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

        // Unchanged, so no other scenario's funnel moved under it. Read through
        // the API rather than the table: a school provisioned after the
        // migration has no row yet and answers from the column defaults until
        // somebody saves one.
        assertThat(get("/v1/admissions/policy?schoolId=" + cbse().id(), registrarToken(cbse()))
            .getBody().get("entranceTestRequired").asBoolean()).isTrue();
    }

    private org.assertj.core.api.ListAssert<String> perms(String roleCode) {
        return org.assertj.core.api.Assertions.assertThat(
            queryList("SELECT perm_code FROM role_perm WHERE role_code = ?", String.class, roleCode));
    }
}
