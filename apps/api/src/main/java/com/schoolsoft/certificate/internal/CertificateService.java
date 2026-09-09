package com.schoolsoft.certificate.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoolsoft.assessment.api.AcademicRecord;
import com.schoolsoft.attendance.api.AttendanceSummaries;
import com.schoolsoft.certificate.api.CertificateDto;
import com.schoolsoft.iam.api.Authz;
import com.schoolsoft.platform.web.ConflictException;
import com.schoolsoft.platform.web.NotFoundException;
import com.schoolsoft.tenancy.api.NumberSeries;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issuing a certificate (XFER-02, XFER-07, GRAD-02).
 *
 * <h2>Frozen at issue</h2>
 * The payload is assembled once, hashed, and never recomputed. A Transfer
 * Certificate states a child's attendance, their class, their conduct and their
 * last result <em>as at the day it was signed</em>; regenerating those fields on
 * read would mean the same serial number printing different figures next term,
 * after a re-evaluation lands or a section is renamed. A certificate that can
 * change its own contents is not a certificate.
 *
 * <h2>What is refused</h2>
 * A TC before the withdrawal is complete — the whole point of the clearance
 * checklist is that it gates this document. And a second live certificate of the
 * same kind against the same enrolment (XFER-07): a school corrects a wrong TC
 * by revoking it and issuing a replacement, which leaves both in the record and
 * the serial the family already holds still resolving.
 */
@Service
public class CertificateService {

    private final CertificateRepository repo;
    private final DataSource dataSource;
    private final NumberSeries numbers;
    private final AttendanceSummaries attendance;
    private final AcademicRecord academics;
    private final Authz authz;
    private final ObjectMapper json;

    public CertificateService(CertificateRepository repo, DataSource dataSource, NumberSeries numbers,
                              AttendanceSummaries attendance, AcademicRecord academics, Authz authz,
                              ObjectMapper json) {
        this.repo = repo;
        this.dataSource = dataSource;
        this.numbers = numbers;
        this.attendance = attendance;
        this.academics = academics;
        this.authz = authz;
        this.json = json;
    }

    /** What the registrar supplies that no table holds: conduct, and any remarks. */
    public record IssueRequest(
        UUID studentId, String kind, String conduct, String remarks, Map<String, Object> extras) {}

    // ------------------------------------------------------------------ reads

    public CertificateDto find(UUID id) { return repo.require(id); }

    public List<CertificateDto> forStudent(UUID studentId) { return repo.listForStudent(studentId); }

    public List<CertificateDto> list(UUID schoolId, String kind) { return repo.list(schoolId, kind); }

    /**
     * Whether the stored payload still hashes to what was signed. The check a
     * receiving school makes against a serial number it was handed.
     */
    public Map<String, Object> verify(UUID id) {
        CertificateDto cert = repo.require(id);
        String recomputed = hash(repo.payloadText(id));
        var out = new LinkedHashMap<String, Object>();
        out.put("id", cert.id());
        out.put("serialNo", cert.serialNo());
        out.put("kind", cert.kind());
        out.put("issuedOn", cert.issuedOn().toString());
        out.put("intact", recomputed.equals(cert.payloadHash()));
        out.put("revoked", !cert.live());
        if (!cert.live()) out.put("revokedReason", cert.revokedReason());
        return out;
    }

    // ----------------------------------------------------------------- issue

    @Transactional
    public CertificateDto issue(IssueRequest req) {
        Student student = student(req.studentId());
        Exit exit = exitOf(req.studentId(), req.kind());

        if (!repo.liveFor(exit.enrolmentId(), req.kind()).isEmpty()) {
            throw new ConflictException("A live " + req.kind() + " certificate already exists for this "
                + "enrolment. Revoke it before issuing a replacement.");
        }

        LocalDate issuedOn = LocalDate.now();
        Map<String, Object> payload = buildPayload(student, exit, req, issuedOn);
        String serial = numbers.next(student.schoolId(), NumberSeries.Kind.certificate, null,
            serialPattern(req.kind()), Map.of("KIND", req.kind().toUpperCase()));
        payload.put("serialNo", serial);

        UUID id = repo.insert(student.schoolId(), student.id(), exit.enrolmentId(), exit.withdrawalId(),
            req.kind(), serial, issuedOn, authz.currentStaffId(), payload, hash(payload));
        return repo.require(id);
    }

    /**
     * Revokes and, when {@code reissue} is set, immediately issues a replacement
     * linked back to it — the correction path, so a school never edits a
     * document it has already handed over.
     */
    @Transactional
    public CertificateDto revoke(UUID id, String reason, IssueRequest reissue) {
        CertificateDto cert = repo.require(id);
        if (cert.live() && repo.revoke(id, reason, authz.currentStaffId()) == 0) {
            return repo.require(id);
        }
        if (reissue == null) return repo.require(id);

        CertificateDto replacement = issue(reissue);
        repo.markSuperseded(id, replacement.id());
        return replacement;
    }

    // ----------------------------------------------------------------- inside

    /**
     * The statutory fields, in the order a Transfer Certificate prints them.
     *
     * <p>What the school does not record is <em>absent</em>, not guessed:
     * nationality, category and NCC enrolment are not columns here, so they come
     * in as {@code extras} if the registrar types them and are simply missing if
     * they do not. A certificate that quietly fills a statutory field with a
     * plausible default is worse than one with a blank on it.</p>
     */
    private Map<String, Object> buildPayload(Student student, Exit exit, IssueRequest req, LocalDate issuedOn) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("kind", req.kind());
        payload.put("schoolName", student.schoolName());
        payload.put("studentName", student.fullName());
        payload.put("admissionNo", student.admissionNo());
        if (student.dob() != null) payload.put("dateOfBirth", student.dob().toString());
        payload.put("guardians", student.guardians());

        payload.put("dateOfAdmission", exit.firstDay() == null ? null : exit.firstDay().toString());
        payload.put("dateOfLeaving", exit.lastDay() == null ? null : exit.lastDay().toString());
        payload.put("lastClassStudied", exit.classLabel());
        payload.put("reasonForLeaving", exit.reason());

        academics.latestFor(student.id()).ifPresent(latest -> {
            payload.put("lastExamTaken", latest.academicYearCode() + " " + latest.termName());
            payload.put("lastExamResult", latest.overallGrade() == null
                ? latest.overallPct() : latest.overallGrade());
            payload.put("qualifiedForPromotion", "promote".equals(latest.promotionDecision()));
            payload.put("subjectsStudied", latest.subjects().stream()
                .map(AcademicRecord.SubjectLine::subjectName).toList());
        });

        // Attendance over the child's whole time here, from the same summary the
        // attendance screen and the report card use (ASMT-10) — a TC that
        // disagrees with the report card the family already holds is the sort of
        // discrepancy a receiving school queries.
        academics.attendanceWindow(student.id()).ifPresent(window -> {
            var summary = attendance.forStudent(student.id(), window[0], window[1]);
            if (summary != null) {
                payload.put("attendanceFrom", window[0].toString());
                payload.put("attendanceTo", window[1].toString());
                payload.put("workingDays", summary.workingDays());
                payload.put("daysPresent", summary.present());
                payload.put("attendancePct", summary.percentage());
            }
        });

        // A transcript is the whole published history; a TC quotes only the last
        // result. Carrying every term on a TC would make it a different document.
        if ("transcript".equals(req.kind()) || "leaving".equals(req.kind())) {
            payload.put("terms", academics.forStudent(student.id()));
        }

        payload.put("conduct", req.conduct() == null ? "Satisfactory" : req.conduct());
        if (req.remarks() != null) payload.put("remarks", req.remarks());
        if (req.extras() != null) payload.putAll(req.extras());
        payload.put("issuedOn", issuedOn.toString());
        return payload;
    }

    /**
     * The exit a certificate is issued against.
     *
     * <p>A TC needs a <em>completed</em> withdrawal: it is the clearance
     * checklist that gates the document, and issuing one off an in-flight
     * withdrawal would make the checklist decorative. A leaving certificate or
     * transcript needs a closed enrolment, however it closed — a graduating
     * cohort leaves through rollover, not through the leavers' desk, and has no
     * withdrawal row at all (GRAD-02). A bonafide certificate is about a child
     * who is still here, and needs an open one.</p>
     */
    private Exit exitOf(UUID studentId, String kind) {
        var jdbc = new JdbcTemplate(dataSource);
        return switch (kind) {
            case "transfer" -> {
                var rows = jdbc.query(
                    "SELECT w.id, w.enrolment_id, w.last_working_date, w.reason, w.reason_code, " +
                    "       e.starts_on, (g.code || '-' || sec.code) AS class_label " +
                    "FROM withdrawal w JOIN enrolment e ON e.id = w.enrolment_id " +
                    "JOIN section sec ON sec.id = e.section_id JOIN grade g ON g.id = sec.grade_id " +
                    "WHERE w.student_id = ? AND w.state = 'completed' " +
                    "ORDER BY w.completed_at DESC LIMIT 1",
                    (rs, i) -> new Exit(
                        UUID.fromString(rs.getString("enrolment_id")), UUID.fromString(rs.getString("id")),
                        rs.getDate("starts_on").toLocalDate(), rs.getDate("last_working_date").toLocalDate(),
                        rs.getString("class_label"), rs.getString("reason")),
                    studentId);
                if (rows.isEmpty()) {
                    throw new ConflictException("A transfer certificate needs a completed withdrawal. "
                        + "File one and clear it first.");
                }
                yield rows.get(0);
            }
            case "leaving", "transcript" -> {
                var rows = jdbc.query(
                    "SELECT e.id, e.starts_on, e.ends_on, e.status, " +
                    "       (g.code || '-' || sec.code) AS class_label " +
                    "FROM enrolment e JOIN section sec ON sec.id = e.section_id " +
                    "JOIN grade g ON g.id = sec.grade_id " +
                    "WHERE e.student_id = ? AND e.ends_on IS NOT NULL " +
                    "ORDER BY e.ends_on DESC LIMIT 1",
                    (rs, i) -> new Exit(
                        UUID.fromString(rs.getString("id")), null,
                        rs.getDate("starts_on").toLocalDate(), rs.getDate("ends_on").toLocalDate(),
                        rs.getString("class_label"), "Completed " + rs.getString("status")),
                    studentId);
                if (rows.isEmpty()) {
                    throw new ConflictException("This student's enrolment is still open; a leaving "
                        + "certificate is issued after it closes.");
                }
                yield rows.get(0);
            }
            case "bonafide" -> {
                var rows = jdbc.query(
                    "SELECT e.id, e.starts_on, (g.code || '-' || sec.code) AS class_label " +
                    "FROM enrolment e JOIN section sec ON sec.id = e.section_id " +
                    "JOIN grade g ON g.id = sec.grade_id " +
                    "WHERE e.student_id = ? AND " +
                    com.schoolsoft.enrolment.api.EnrolmentActivity.activeOnDateLiteral("e", LocalDate.now()) +
                    " LIMIT 1",
                    (rs, i) -> new Exit(UUID.fromString(rs.getString("id")), null,
                        rs.getDate("starts_on").toLocalDate(), null, rs.getString("class_label"), null),
                    studentId);
                if (rows.isEmpty()) {
                    throw new ConflictException("A bonafide certificate says the child is currently "
                        + "enrolled, and this one is not.");
                }
                yield rows.get(0);
            }
            default -> throw new ConflictException("Unknown certificate kind: " + kind);
        };
    }

    private Student student(UUID studentId) {
        var jdbc = new JdbcTemplate(dataSource);
        var rows = jdbc.query(
            "SELECT s.id, s.school_id, s.admission_no, s.dob, sc.name AS school_name, " +
            "       btrim(concat_ws(' ', s.first_name, s.middle_name, s.last_name)) AS full_name " +
            "FROM student s JOIN school sc ON sc.id = s.school_id WHERE s.id = ?",
            (rs, i) -> new Student(
                UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("school_id")),
                rs.getString("admission_no"), rs.getString("full_name"), rs.getString("school_name"),
                rs.getDate("dob") == null ? null : rs.getDate("dob").toLocalDate(),
                new ArrayList<>()),
            studentId);
        if (rows.isEmpty()) throw new NotFoundException("Student not found: " + studentId);
        Student student = rows.get(0);

        jdbc.query(
            "SELECT gs.relation, btrim(concat_ws(' ', g.first_name, g.last_name)) AS name " +
            "FROM guardian_student gs JOIN guardian g ON g.id = gs.guardian_id " +
            "WHERE gs.student_id = ? ORDER BY gs.is_primary DESC, gs.relation",
            rs -> {
                student.guardians().add(Map.of("relation", rs.getString("relation"),
                    "name", rs.getString("name")));
            }, studentId);
        return student;
    }

    /**
     * {@code TC/2026-27/0001}. Certificate serials are quoted back to the school
     * years later, so they carry the year they were issued in and a counter that
     * {@link NumberSeries} takes under a row lock — two registrars issuing at
     * once cannot be handed the same number.
     */
    private static String serialPattern(String kind) {
        String prefix = switch (kind) {
            case "transfer" -> "TC";
            case "leaving" -> "SLC";
            case "transcript" -> "TR";
            default -> "BC";
        };
        return prefix + "/{YYYY}/{SEQ:4}";
    }

    /**
     * SHA-256 over the payload's serialised JSON, and over the <em>stored
     * text</em> when verifying — never over a re-serialised map. The column is
     * `json` rather than `jsonb` precisely so those two are the same bytes; with
     * jsonb, Postgres would reorder the keys on the way in and every certificate
     * would verify as tampered with.
     */
    private String hash(Map<String, Object> payload) {
        try {
            return hash(json.writeValueAsString(payload));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot hash certificate payload", e);
        }
    }

    private String hash(String canonicalJson) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot hash certificate payload", e);
        }
    }

    private record Student(UUID id, UUID schoolId, String admissionNo, String fullName, String schoolName,
                           LocalDate dob, List<Map<String, String>> guardians) {}

    private record Exit(UUID enrolmentId, UUID withdrawalId, LocalDate firstDay, LocalDate lastDay,
                        String classLabel, String reason) {}
}
