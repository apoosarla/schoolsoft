package com.schoolsoft.platform.security;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The permission vocabulary. Every HTTP endpoint gates on one of these codes
 * (see {@code @PreAuthorize("@perm.can('...')")}), and nothing outside this
 * enum is a valid gate — {@code RbacArchitectureTest} fails the build on a
 * code that does not resolve here, which is what keeps the annotations and
 * this list from drifting apart.
 *
 * <h2>Why an enum and not a table</h2>
 * The <em>vocabulary</em> is code because it is the contract the controllers
 * are written against; a permission that no endpoint checks is dead, and a
 * gate naming a permission nobody defined is a hole. The <em>grants</em>
 * (which role holds which permission) stay data, in {@code role_perm}, because
 * a school may define custom roles — see {@code docs/adr/0001-rbac-matrix.md}.
 *
 * <h2>{@code .own} permissions</h2>
 * A code ending in {@code .own} authorises a caller to read <em>their own</em>
 * slice — a guardian their children's, a student their own. It is never a
 * complete gate on its own: the handler must still scope the query to the
 * caller, which {@code SelfScope} exists to do. The staff-facing sibling of
 * the same code (without {@code .own}) is the unrestricted one.
 *
 * <h2>Scope</h2>
 * These are school-wide capabilities. Narrowing a holder to a campus, section
 * or subject is a separate axis handled by {@code Authz.campusScopeOfCurrentUser()}
 * and by the contextual authorizers ({@code AttendanceAuthorizer}), which run
 * <em>after</em> the permission check, not instead of it.
 */
public enum Perm {

    // ===== academic structure (tenancy) =====
    STRUCTURE_VIEW("structure.view", "Read schools, campuses, grades, sections, subjects, terms"),
    STRUCTURE_MANAGE("structure.manage", "Create and edit the academic structure"),
    ACADEMIC_YEAR_MANAGE("academic_year.manage", "Open, lock and close an academic year"),
    TEACHER_ASSIGN("teacher.assign", "Assign subject teachers to a section"),
    CURRICULUM_VIEW("curriculum.view", "Read curricula, nodes and learning outcomes"),
    CURRICULUM_MANAGE("curriculum.manage", "Create, clone and publish curricula"),

    // ===== people =====
    STUDENT_VIEW("student.view", "Read any student record"),
    STUDENT_VIEW_OWN("student.view.own", "Read your own record, or your children's"),
    STUDENT_MANAGE("student.manage", "Create and edit student records"),
    GUARDIAN_VIEW("guardian.view", "Read guardian records and their student links"),
    STAFF_VIEW("staff.view", "Read staff records"),
    DIRECTORY_VIEW("directory.view", "Read the school directory"),

    // ===== enrolment =====
    ENROLMENT_VIEW("enrolment.view", "Read enrolments, subject sets and elections"),
    ENROLMENT_VIEW_OWN("enrolment.view.own", "Read your own enrolment, or your children's"),
    ENROLMENT_MANAGE("enrolment.manage", "Enrol, transfer, exit and renumber students"),
    ELECTION_MANAGE("election.manage", "Elect and drop elective subjects"),

    // ===== admissions =====
    ADMISSION_VIEW("admission.view", "Read applications and their event history"),
    ADMISSION_MANAGE("admission.manage", "Create applications and record test scores"),
    ADMISSION_DECIDE("admission.decide", "Move an application through the funnel"),
    ADMISSION_ENROL("admission.enrol", "Convert an accepted application into an enrolment"),

    // ===== calendar =====
    CALENDAR_VIEW("calendar.view", "Read the school calendar and working days"),
    CALENDAR_MANAGE("calendar.manage", "Edit calendar patterns and entries"),
    CLOSURE_DECLARE("closure.declare", "Declare a same-day closure"),

    // ===== attendance =====
    ATTENDANCE_VIEW("attendance.view", "Read any register or student summary"),
    ATTENDANCE_VIEW_OWN("attendance.view.own", "Read your own attendance, or your children's"),
    ATTENDANCE_MARK("attendance.mark", "Mark a register"),
    ATTENDANCE_AMEND_REQUEST("attendance.amend.request", "Request an amendment outside the edit window"),
    ATTENDANCE_AMEND_DECIDE("attendance.amend.decide", "Approve or reject an amendment request"),
    ATTENDANCE_POLICY_MANAGE("attendance.policy.manage", "Set the edit window and approver roles"),
    LEAVE_VIEW("leave.view", "Read leave applications"),
    LEAVE_APPLY("leave.apply", "Apply for leave"),
    LEAVE_DECIDE("leave.decide", "Approve or reject leave"),

    // ===== timetable =====
    TIMETABLE_VIEW("timetable.view", "Read any section's or teacher's timetable"),
    TIMETABLE_VIEW_OWN("timetable.view.own", "Read your own timetable, or your children's"),
    TIMETABLE_MANAGE("timetable.manage", "Edit slots and bell schedules"),
    COVER_VIEW("cover.view", "Read cover needs and assignments"),
    COVER_MANAGE("cover.manage", "Assign and withdraw cover"),

    // ===== assessment =====
    ASSESSMENT_VIEW("assessment.view", "Read assessments and their components"),
    ASSESSMENT_MANAGE("assessment.manage", "Create assessments and components, move their status"),
    MARK_VIEW("mark.view", "Read any marks"),
    MARK_VIEW_OWN("mark.view.own", "Read your own marks, or your children's"),
    MARK_ENTER("mark.enter", "Enter and revise marks"),
    MARK_REEVAL_REQUEST("mark.reeval.request", "Request a re-evaluation"),
    MARK_REEVAL_DECIDE("mark.reeval.decide", "Decide a re-evaluation"),
    REPORT_CARD_VIEW("report_card.view", "Read any report card"),
    REPORT_CARD_VIEW_OWN("report_card.view.own", "Read your own report card, or your children's"),
    REPORT_CARD_GENERATE("report_card.generate", "Generate report cards"),
    REPORT_CARD_LOCK("report_card.lock", "Lock and unlock a report card"),
    REPORT_CARD_PUBLISH("report_card.publish", "Publish a report card to guardians"),
    ASSESSMENT_POLICY_MANAGE("assessment.policy.manage", "Set the grading scheme and mark policy"),

    // ===== exams =====
    EXAM_VIEW("exam.view", "Read exam schedules and sessions"),
    EXAM_VIEW_OWN("exam.view.own", "Read the exam schedule you sit, or your children's"),
    EXAM_MANAGE("exam.manage", "Build exam schedules and sessions"),
    EXAM_PUBLISH("exam.publish", "Publish and unpublish an exam schedule"),
    HALL_TICKET_ISSUE("hall_ticket.issue", "Issue hall tickets"),
    HALL_TICKET_VIEW("hall_ticket.view", "Read hall tickets"),
    HALL_TICKET_VIEW_OWN("hall_ticket.view.own", "Read your own hall ticket, or your children's"),

    // ===== fees =====
    FEE_STRUCTURE_VIEW("fee.structure.view", "Read fee structures and heads"),
    FEE_STRUCTURE_MANAGE("fee.structure.manage", "Create, edit and clone fee structures and heads"),
    FEE_INVOICE_VIEW("fee.invoice.view", "Read any invoice"),
    FEE_INVOICE_VIEW_OWN("fee.invoice.view.own", "Read your own invoices, or your children's"),
    FEE_INVOICE_MANAGE("fee.invoice.manage", "Generate and combine invoices"),
    FEE_PAYMENT_RECORD("fee.payment.record", "Record a payment"),
    FEE_ADJUSTMENT_MANAGE("fee.adjustment.manage", "Post a waiver, refund or reversal"),
    FEE_CONCESSION_MANAGE("fee.concession.manage", "Grant concessions and set sibling policy"),
    FEE_REPORT_VIEW("fee.report.view", "Read the day book and outstanding reports"),
    DUNNING_MANAGE("dunning.manage", "Set the dunning policy and run dunning"),

    // ===== lms =====
    LMS_CONTENT_VIEW("lms.content.view", "Read content, lesson plans and assignments"),
    LMS_CONTENT_MANAGE("lms.content.manage", "Create content, lesson plans, assignments and quizzes"),
    LMS_SUBMIT("lms.submit", "Submit an assignment or a quiz attempt"),
    LMS_GRADE("lms.grade", "Grade a submission"),

    // ===== library =====
    LIBRARY_VIEW("library.view", "Read the catalogue and circulation"),
    LIBRARY_MANAGE("library.manage", "Edit the catalogue"),
    LIBRARY_CIRCULATE("library.circulate", "Issue, return and charge for copies"),

    // ===== comms =====
    ANNOUNCEMENT_VIEW("announcement.view", "Read announcements"),
    ANNOUNCEMENT_MANAGE("announcement.manage", "Write and publish announcements"),
    MESSAGE_PARTICIPATE("message.participate", "Read and post in your own message threads"),

    // ===== transport =====
    TRANSPORT_VIEW("transport.view", "Read vehicles, routes, stops and assignments"),
    TRANSPORT_MANAGE("transport.manage", "Edit vehicles, drivers, routes, stops and assignments"),
    TRANSPORT_DRIVE("transport.drive", "Run a trip: start, check in, end, and send GPS pings"),
    TRANSPORT_TRACK("transport.track", "Follow a live trip and its GPS trail"),

    // ===== devices =====
    DEVICE_VIEW("device.view", "Read registered devices"),
    DEVICE_MANAGE("device.manage", "Register devices"),
    DEVICE_EVENT_POST("device.event.post", "Post a scan event from a device"),

    // ===== rollover =====
    ROLLOVER_VIEW("rollover.view", "Read rollover runs and their allocations"),
    ROLLOVER_MANAGE("rollover.manage", "Create a run, allocate, commit, roll back and activate"),

    // ===== board integration =====
    BOARD_EXPORT_VIEW("board_export.view", "Read board export batches"),
    BOARD_EXPORT_MANAGE("board_export.manage", "Create and process board export batches"),

    // ===== administration =====
    ROLE_VIEW("role.view", "Read the role catalogue and staff grants"),
    ROLE_MANAGE("role.manage", "Create roles and grant or revoke them"),
    AUDIT_VIEW("audit.view", "Read the audit log"),
    THEME_VIEW("theme.view", "Read the school's theme"),
    THEME_MANAGE("theme.manage", "Edit the school's theme"),
    FEATURE_FLAG_VIEW("feature_flag.view", "Read feature flags"),
    FEATURE_FLAG_MANAGE("feature_flag.manage", "Toggle feature flags"),
    DASHBOARD_VIEW("dashboard.view", "Read the school dashboard"),
    FILE_UPLOAD("file.upload", "Request an upload ticket"),
    FILE_DOWNLOAD("file.download", "Request a download ticket");

    private static final Map<String, Perm> BY_CODE =
            Arrays.stream(values()).collect(Collectors.toMap(Perm::code, Function.identity()));

    private final String code;
    private final String description;

    Perm(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String code() { return code; }

    public String description() { return description; }

    /** True when this permission only authorises the caller's own slice. */
    public boolean isSelfScoped() { return code.endsWith(".own"); }

    /**
     * True for the unrestricted reads. What a chain (HQ) admin holds: they
     * oversee every school in the chain and change none of them, so the read
     * set is derived rather than listed — a new {@code *.view} permission is
     * theirs automatically, and a new write never is.
     */
    public boolean isUnrestrictedRead() { return code.endsWith(".view") && !isSelfScoped(); }

    public static Optional<Perm> byCode(String code) {
        return Optional.ofNullable(BY_CODE.get(code));
    }
}
