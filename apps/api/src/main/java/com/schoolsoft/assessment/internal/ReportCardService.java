package com.schoolsoft.assessment.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoolsoft.assessment.api.CurriculumStrategy;
import com.schoolsoft.assessment.api.GradeScaleDto;
import com.schoolsoft.assessment.api.ReportCardDetailDto;
import com.schoolsoft.assessment.api.ReportCardDto;
import com.schoolsoft.assessment.api.SubjectResult;
import com.schoolsoft.assessment.internal.strategy.GradeScaleRepository;
import com.schoolsoft.assessment.internal.strategy.StrategyRegistry;
import com.schoolsoft.attendance.api.AttendanceSummaries;
import com.schoolsoft.attendance.api.AttendanceSummaryDto;
import com.schoolsoft.enrolment.api.StudentSubjectDto;
import com.schoolsoft.enrolment.api.SubjectSetResolver;
import com.schoolsoft.fees.api.FeeDues;
import com.schoolsoft.iam.api.Authz;
import com.schoolsoft.platform.db.Jdbc;
import com.schoolsoft.platform.tenancy.TenantContext;
import com.schoolsoft.platform.web.ConflictException;
import com.schoolsoft.platform.web.ForbiddenException;
import com.schoolsoft.platform.web.NotFoundException;
import com.schoolsoft.tenancy.api.AcademicYearGuard;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Report card generation (GAP-13, GAP-29).
 *
 * A report card used to be a row with a JSON blob in it: whatever the caller
 * sent was what the school printed. Nothing computed the marks, nothing knew
 * the attendance, nothing recorded whether the child goes up a year — which
 * is the one fact the next academic year cannot start without.
 *
 * This assembles a card from the record instead:
 *
 * <ul>
 *   <li>subject rows come from the student's own subject set, so two children
 *       in one section with different options get different cards (ASMT-13);</li>
 *   <li>an absence renders AB and leaves the average alone; it is never a zero
 *       (ASMT-05);</li>
 *   <li>attendance comes from the attendance module against the school-calendar
 *       denominator, so the card and the attendance screen cannot disagree;</li>
 *   <li>grades, rank and the promotion decision come from the curriculum
 *       strategy, so CBSE and Cambridge differ without a config fork (ASMT-11);</li>
 *   <li>a mid-year joiner's card says which terms it speaks for rather than
 *       showing blank rows that read as failure (ASMT-14).</li>
 * </ul>
 *
 * Marks are read from assessments that have reached {@code marking} — a card
 * generated mid-term is a legitimate progress report. Which of those are still
 * unpublished is a year-closure question, and Phase 6's readiness check asks it.
 */
@Service
public class ReportCardService {

    /** Assessment statuses whose marks are complete enough to print. */
    private static final List<String> PRINTABLE = List.of("marking", "locked", "published");

    /** Roles that may unlock a card a family has already been shown. */
    private static final List<String> UNLOCK_ROLES = MarkService.EXAM_AUTHORITY_ROLES;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final SubjectSetResolver subjectSets;
    private final AttendanceSummaries attendance;
    private final StrategyRegistry strategies;
    private final GradeScaleRepository gradeScales;
    private final AssessmentPolicyRepository policies;
    private final FeeDues feeDues;
    private final AcademicYearGuard academicYears;
    private final Authz authz;

    public ReportCardService(JdbcTemplate jdbc, ObjectMapper json, SubjectSetResolver subjectSets,
                             AttendanceSummaries attendance, StrategyRegistry strategies,
                             GradeScaleRepository gradeScales, AssessmentPolicyRepository policies,
                             FeeDues feeDues, AcademicYearGuard academicYears, Authz authz) {
        this.jdbc = jdbc;
        this.json = json;
        this.subjectSets = subjectSets;
        this.attendance = attendance;
        this.strategies = strategies;
        this.gradeScales = gradeScales;
        this.policies = policies;
        this.feeDues = feeDues;
        this.academicYears = academicYears;
        this.authz = authz;
    }

    // ------------------------------------------------------------- generation

    /** Everything a card is built from; the optional fields are the school's own words. */
    public record GenerateRequest(
        UUID schoolId, UUID studentId, UUID academicYearId, UUID termId,
        String strategyCode, String templateCode, Map<String, Object> payload,
        String teacherRemarks, String principalRemarks, String promotionDecision,
        List<CoScholasticInput> coScholastic
    ) {}

    public record CoScholasticInput(String areaCode, String areaName, String rating, String remarks, int sortOrder) {}

    @Transactional
    public ReportCardDto generate(GenerateRequest req) {
        academicYears.requireOpen(req.academicYearId());

        Existing existing = existingCard(req.studentId(), req.academicYearId(), req.termId(), req.templateCode());
        if (existing != null && !"draft".equals(existing.status())) {
            // A locked card is a document a family has been shown. Regenerating
            // it silently is how two versions of one term end up in circulation
            // (ASMT-12).
            throw new ConflictException(
                "Report card " + existing.id() + " is " + existing.status()
                + "; unlock it before regenerating (POST /v1/assessment/report-cards/" + existing.id() + "/unlock)");
        }

        UUID cardId = existing == null ? UUID.randomUUID() : existing.id();
        int version = existing == null ? 1 : existing.version() + 1;

        Cohort cohort = cohortOf(req.studentId(), req.academicYearId(), req.termId());
        GradeScaleDto scale = gradeScales.forSchool(req.schoolId(), req.strategyCode());
        CurriculumStrategy strategy = strategies.forCode(req.strategyCode());

        List<SubjectResult> subjects = subjectResults(req.studentId(), req.termId(), cohort, scale, strategy);
        AttendanceSummaryDto att = attendance.forStudent(req.studentId(), cohort.from(), cohort.to());

        Double aggregate = strategy.reportsAggregatePercentage() ? aggregatePercentage(subjects) : null;
        Double totalObtained = sum(subjects, true);
        Double totalMax = sum(subjects, false);
        String overallGrade = aggregate == null ? null : strategy.gradeFor(aggregate, scale);
        Double rankKey = strategy.rankKey(subjects, scale);

        String promotion = req.promotionDecision() != null && !req.promotionDecision().isBlank()
            ? req.promotionDecision()
            : strategy.promotionFor(subjects, scale, cohort.terminalGrade());

        Coverage coverage = coverage(req.studentId(), req.academicYearId(), cohort);

        Map<String, Object> payload = new LinkedHashMap<>(req.payload() == null ? Map.of() : req.payload());
        // The resolved subject set stays in the payload for templates that were
        // written against it before the columns existed.
        payload.put("subjects", subjects.stream().map(s -> Map.of(
            "subjectId", s.subjectId().toString(), "code", s.subjectCode(),
            "name", s.subjectName(), "origin", s.origin())).toList());

        if (existing == null) {
            jdbc.update(
                "INSERT INTO report_card (id, school_id, student_id, section_id, academic_year_id, term_id, " +
                "  strategy_code, template_code, payload, status, version) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'draft', ?)",
                cardId, req.schoolId(), req.studentId(), cohort.sectionId(), req.academicYearId(), req.termId(),
                req.strategyCode(), req.templateCode(), jsonb(payload), version);
        } else {
            jdbc.update(
                "UPDATE report_card SET section_id = ?, strategy_code = ?, payload = ?, version = ?, " +
                "  generated_at = now(), updated_at = now() WHERE id = ?",
                cohort.sectionId(), req.strategyCode(), jsonb(payload), version, cardId);
            jdbc.update("DELETE FROM report_card_subject WHERE report_card_id = ?", cardId);
            jdbc.update("DELETE FROM report_card_coscholastic WHERE report_card_id = ?", cardId);
        }

        jdbc.update(
            "UPDATE report_card SET grade_scale_code = ?, total_marks = ?, total_max_marks = ?, overall_pct = ?, " +
            "  overall_grade = ?, rank_key = ?, attendance_working_days = ?, attendance_present_days = ?, " +
            "  attendance_pct = ?, promotion_decision = ?, teacher_remarks = ?, principal_remarks = ?, " +
            "  enrolled_from = ?, terms_attended = ?, terms_in_year = ?, coverage_note = ?, updated_at = now() " +
            "WHERE id = ?",
            scale.code(), totalObtained, totalMax, aggregate, overallGrade, rankKey,
            att.workingDays(), presentDays(att), att.percentage(), promotion,
            req.teacherRemarks(), req.principalRemarks(),
            coverage.enrolledFrom() == null ? null : Date.valueOf(coverage.enrolledFrom()),
            coverage.termsAttended(), coverage.termsInYear(), coverage.note(), cardId);

        writeSubjectRows(cardId, subjects);
        writeCoScholastic(cardId, req.coScholastic());

        // Rank is a property of the cohort, so it is recomputed for everyone
        // holding a card for this term rather than guessed for this one.
        rankCohort(cohort.sectionId(), req.academicYearId(), req.termId(), req.templateCode());
        return find(cardId);
    }

    /** Generates (or regenerates) every student in a section — the way a school actually issues cards. */
    @Transactional
    public List<ReportCardDto> generateForSection(UUID schoolId, UUID sectionId, UUID academicYearId, UUID termId,
                                                  String strategyCode, String templateCode) {
        List<UUID> students = jdbc.queryForList(
            "SELECT student_id FROM enrolment WHERE section_id = ? AND status = 'active' ORDER BY roll_no",
            UUID.class, sectionId);
        List<ReportCardDto> cards = new ArrayList<>();
        for (UUID studentId : students) {
            cards.add(generate(new GenerateRequest(schoolId, studentId, academicYearId, termId, strategyCode,
                templateCode, Map.of(), null, null, null, List.of())));
        }
        return cards;
    }

    /**
     * Rebuilds the draft cards a student holds — called when a mark they were
     * built from is superseded, so a re-evaluation reaches the document the
     * family reads (ASMT-08). Locked and published cards are deliberately left
     * alone: reissuing one is a decision, not a side effect.
     */
    @Transactional
    public void refreshDraftCardsFor(UUID studentId) {
        record Card(UUID id, UUID schoolId, UUID academicYearId, UUID termId, String strategyCode,
                    String templateCode, String teacherRemarks, String principalRemarks, String promotion) {}
        List<Card> drafts = jdbc.query(
            "SELECT id, school_id, academic_year_id, term_id, strategy_code, template_code, " +
            "       teacher_remarks, principal_remarks, promotion_decision " +
            "FROM report_card WHERE student_id = ? AND status = 'draft'",
            (rs, i) -> new Card(
                UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("school_id")),
                UUID.fromString(rs.getString("academic_year_id")),
                rs.getString("term_id") == null ? null : UUID.fromString(rs.getString("term_id")),
                rs.getString("strategy_code"), rs.getString("template_code"),
                rs.getString("teacher_remarks"), rs.getString("principal_remarks"),
                rs.getString("promotion_decision")),
            studentId);
        for (Card card : drafts) {
            generate(new GenerateRequest(card.schoolId(), studentId, card.academicYearId(), card.termId(),
                card.strategyCode(), card.templateCode(), existingPayload(card.id()),
                card.teacherRemarks(), card.principalRemarks(), null, List.of()));
        }
    }

    // ------------------------------------------------------------- lifecycle

    /**
     * draft → locked.
     *
     * <p>The transition asserts the state it is moving out of in the same
     * statement that moves it. Reading the status first and updating after
     * leaves a window: the card can be published in between, and the update's
     * {@code status <> 'published'} guard then matches nothing while the method
     * still returns a card and reports success. The caller believes they locked
     * something they did not.</p>
     */
    @Transactional
    public ReportCardDto lock(UUID id) {
        int moved = jdbc.update(
            "UPDATE report_card SET is_locked = TRUE, status = 'locked', updated_at = now() " +
            "WHERE id = ? AND status = 'draft'", id);
        if (moved == 0) refuseTransition(id, "lock", "draft");
        return find(id);
    }

    /**
     * Unlocking a card the family has already been shown: an authorised role, a
     * reason, and an audit row at the endpoint. Same rule as reopening an
     * assessment, because it is the same act.
     */
    @Transactional
    public ReportCardDto unlock(UUID id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Unlocking a report card needs a reason");
        }
        if (authz.rolesOfCurrentUser().stream().noneMatch(UNLOCK_ROLES::contains)) {
            throw new ForbiddenException(
                "Your role cannot unlock a report card (needs one of " + UNLOCK_ROLES + ")");
        }
        int moved = jdbc.update(
            "UPDATE report_card SET is_locked = FALSE, status = 'draft', parent_visible_from = NULL, " +
            "  published_at = NULL, updated_at = now() " +
            "WHERE id = ? AND status IN ('locked', 'published')", id);
        if (moved == 0) refuseTransition(id, "unlock", "locked or published");
        return find(id);
    }

    /**
     * Publication is what makes a card visible to a family, and it is where a
     * school's dues policy applies (ASMT-15). The choice is the school's:
     * {@code withhold} holds the card back while the household is in arrears,
     * {@code release} — the default — ignores the balance entirely.
     */
    @Transactional
    public ReportCardDto publish(UUID id) {
        ReportCardDto card = find(id);
        var policy = policies.forSchool(card.schoolId());
        if ("withhold".equals(policy.duesBlockPolicy())) {
            double due = feeDues.outstandingForStudent(card.studentId());
            if (due > policy.duesBlockThreshold()) {
                throw new ConflictException(
                    "Report card withheld: " + due + " outstanding against this student, and the school's "
                    + "dues policy is 'withhold'. Settle the dues or change the policy to release.");
            }
        }
        // Conditional on 'locked', not on the status read above: between that
        // read and this write the card can be unlocked back to draft, and
        // publishing a draft is how a family gets shown a card the school had
        // taken back.
        int moved = jdbc.update(
            "UPDATE report_card SET status = 'published', is_locked = TRUE, published_at = now(), " +
            "  parent_visible_from = COALESCE(parent_visible_from, now()), updated_at = now() " +
            "WHERE id = ? AND status = 'locked'", id);
        if (moved == 0) refuseTransition(id, "publish", "locked");
        return find(id);
    }

    /** Records the school's promotion decision, overriding what the strategy suggested. */
    @Transactional
    public ReportCardDto setPromotion(UUID id, String decision) {
        if (!List.of("promote", "detain", "graduate").contains(decision)) {
            throw new IllegalArgumentException("Promotion decision must be promote | detain | graduate");
        }
        int updated = jdbc.update(
            "UPDATE report_card SET promotion_decision = ?, promotion_decided_by_user_id = ?, updated_at = now() " +
            "WHERE id = ?", decision, currentUserId(), id);
        if (updated == 0) throw new NotFoundException("Report card not found: " + id);
        return find(id);
    }

    // ------------------------------------------------------------------ reads

    public ReportCardDto find(UUID id) {
        return jdbc.query(SELECT_CARD + " WHERE id = ?", CARD_MAPPER, id).stream().findFirst()
            .orElseThrow(() -> new NotFoundException("Report card not found: " + id));
    }

    /**
     * A student's cards. A guardian sees the published ones only: a draft is
     * the school's working copy, and showing it to a family before the school
     * has agreed it is how a mark gets argued about twice.
     */
    public List<ReportCardDto> listForStudent(UUID studentId) {
        var snap = TenantContext.get();
        boolean guardian = snap != null && "guardian".equals(snap.subjectType());
        String where = guardian
            ? " WHERE student_id = ? AND status = 'published' AND parent_visible_from <= now()"
            : " WHERE student_id = ?";
        return jdbc.query(SELECT_CARD + where + " ORDER BY generated_at DESC", CARD_MAPPER, studentId);
    }

    public ReportCardDetailDto detail(UUID id) {
        ReportCardDto card = find(id);
        List<ReportCardDetailDto.SubjectRow> subjects = jdbc.query(
            "SELECT subject_id, subject_code, subject_name, origin, marks_obtained, max_marks, percentage, " +
            "       grade_letter, result_status, is_passing, remarks, sort_order " +
            "FROM report_card_subject WHERE report_card_id = ? ORDER BY sort_order, subject_code",
            (rs, i) -> {
                String status = rs.getString("result_status");
                Double obtained = Jdbc.nullableDouble(rs, "marks_obtained");
                Double max = Jdbc.nullableDouble(rs, "max_marks");
                Boolean passing = rs.getObject("is_passing") == null ? null : rs.getBoolean("is_passing");
                return new ReportCardDetailDto.SubjectRow(
                    UUID.fromString(rs.getString("subject_id")), rs.getString("subject_code"),
                    rs.getString("subject_name"), rs.getString("origin"), obtained, max,
                    Jdbc.nullableDouble(rs, "percentage"), rs.getString("grade_letter"), status,
                    display(status, obtained, max), passing, rs.getString("remarks"), rs.getInt("sort_order"));
            },
            id);
        List<ReportCardDetailDto.CoScholasticRow> coScholastic = jdbc.query(
            "SELECT area_code, area_name, rating, remarks, sort_order FROM report_card_coscholastic " +
            "WHERE report_card_id = ? ORDER BY sort_order, area_code",
            (rs, i) -> new ReportCardDetailDto.CoScholasticRow(rs.getString("area_code"), rs.getString("area_name"),
                rs.getString("rating"), rs.getString("remarks"), rs.getInt("sort_order")),
            id);
        return new ReportCardDetailDto(card, subjects, coScholastic, existingPayload(id));
    }

    /** What the row prints. AB is not a zero, and EX is not a blank. */
    private static String display(String resultStatus, Double obtained, Double max) {
        return switch (resultStatus) {
            case "absent" -> "AB";
            case "medical_leave" -> "AB (ML)";
            case "exempt" -> "EX";
            case "not_assessed" -> "—";
            default -> (obtained == null ? "—" : trim(obtained)) + " / " + (max == null ? "—" : trim(max));
        };
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    // -------------------------------------------------------------- internals

    /** The cohort a card belongs to: the student's section, and the window it covers. */
    private record Cohort(UUID sectionId, UUID gradeId, LocalDate from, LocalDate to, LocalDate asOf,
                          boolean terminalGrade) {}

    private Cohort cohortOf(UUID studentId, UUID academicYearId, UUID termId) {
        LocalDate from;
        LocalDate to;
        if (termId != null) {
            var range = jdbc.query("SELECT starts_on, ends_on FROM term WHERE id = ?",
                (rs, i) -> new LocalDate[]{rs.getDate("starts_on").toLocalDate(), rs.getDate("ends_on").toLocalDate()},
                termId);
            if (range.isEmpty()) throw new NotFoundException("Term not found: " + termId);
            from = range.get(0)[0];
            to = range.get(0)[1];
        } else {
            var range = jdbc.query("SELECT starts_on, ends_on FROM academic_year WHERE id = ?",
                (rs, i) -> new LocalDate[]{rs.getDate("starts_on").toLocalDate(), rs.getDate("ends_on").toLocalDate()},
                academicYearId);
            if (range.isEmpty()) throw new NotFoundException("Academic year not found: " + academicYearId);
            from = range.get(0)[0];
            to = range.get(0)[1];
        }
        LocalDate today = LocalDate.now();
        LocalDate asOf = to.isAfter(today) ? today : to;
        LocalDate windowEnd = asOf.isBefore(from) ? from : asOf;

        var rows = jdbc.query(
            "SELECT e.section_id, s.grade_id, e.starts_on, " +
            "       (g.sort_order = (SELECT max(sort_order) FROM grade WHERE school_id = g.school_id)) AS terminal " +
            "FROM enrolment e JOIN section s ON s.id = e.section_id JOIN grade g ON g.id = s.grade_id " +
            "WHERE e.student_id = ? AND s.academic_year_id = ? " +
            "ORDER BY (e.status = 'active') DESC, e.starts_on DESC LIMIT 1",
            (rs, i) -> new Object[]{UUID.fromString(rs.getString("section_id")),
                UUID.fromString(rs.getString("grade_id")), rs.getBoolean("terminal"),
                rs.getDate("starts_on").toLocalDate()},
            studentId, academicYearId);
        if (rows.isEmpty()) {
            throw new NotFoundException("Student " + studentId + " has no enrolment in that academic year");
        }
        // Subjects are resolved through the enrolment the student holds on the
        // date asked about, so a card drawn up before a joiner's start date has
        // to ask as of their first day — otherwise a child admitted for next
        // term gets a card with no subjects on it at all (ASMT-14).
        LocalDate enrolmentStart = (LocalDate) rows.get(0)[3];
        LocalDate resolveOn = asOf.isBefore(enrolmentStart) ? enrolmentStart : asOf;
        return new Cohort((UUID) rows.get(0)[0], (UUID) rows.get(0)[1], from, windowEnd, resolveOn,
            (Boolean) rows.get(0)[2]);
    }

    /**
     * One row per subject the student takes, aggregated over every printable
     * assessment for their section in the window.
     *
     * Components the student did not sit are removed from the numerator *and*
     * the denominator, so an absence lowers nothing; a subject with no scored
     * component at all reports why rather than reporting nought.
     *
     * Where an assessment carries a {@code weight_pct}, the subject percentage
     * is the weighted mean of the assessments' percentages; where it does not,
     * marks are simply pooled. The printed marks column always shows the real
     * totals — a weighted percentage and pooled marks can differ, and the marks
     * are what a parent checks against the paper in front of them.
     */
    private List<SubjectResult> subjectResults(UUID studentId, UUID termId, Cohort cohort, GradeScaleDto scale,
                                               CurriculumStrategy strategy) {
        record Row(UUID subjectId, UUID assessmentId, Double weightPct, double componentMax,
                   Double rawMarks, String status) {}
        List<Row> rows = jdbc.query(
            "SELECT a.subject_id, a.id AS assessment_id, a.weight_pct, ac.max_marks AS component_max, " +
            "       m.raw_marks, m.status " +
            "FROM assessment a " +
            "JOIN assessment_component ac ON ac.assessment_id = a.id " +
            "LEFT JOIN mark m ON m.assessment_component_id = ac.id AND m.student_id = ? " +
            "WHERE a.section_id = ? AND a.status IN ('marking', 'locked', 'published') " +
            "  AND (CAST(? AS uuid) IS NULL OR a.term_id = CAST(? AS uuid))",
            (rs, i) -> new Row(
                UUID.fromString(rs.getString("subject_id")),
                UUID.fromString(rs.getString("assessment_id")),
                Jdbc.nullableDouble(rs, "weight_pct"),
                rs.getDouble("component_max"),
                Jdbc.nullableDouble(rs, "raw_marks"),
                rs.getString("status")),
            studentId, cohort.sectionId(), termId, termId);

        // subject → assessment → running totals
        Map<UUID, Map<UUID, double[]>> totals = new LinkedHashMap<>();       // [obtained, max, weight]
        Map<UUID, String> nonScoringStatus = new LinkedHashMap<>();
        for (Row row : rows) {
            if ("entered".equals(row.status())) {
                var perAssessment = totals.computeIfAbsent(row.subjectId(), k -> new LinkedHashMap<>());
                double[] running = perAssessment.computeIfAbsent(row.assessmentId(),
                    k -> new double[]{0, 0, row.weightPct() == null ? -1 : row.weightPct()});
                running[0] += row.rawMarks() == null ? 0 : row.rawMarks();
                running[1] += row.componentMax();
            } else if (row.status() != null && !"pending".equals(row.status())) {
                nonScoringStatus.putIfAbsent(row.subjectId(), row.status());
            }
        }

        List<SubjectResult> results = new ArrayList<>();
        int order = 0;
        for (StudentSubjectDto subject : subjectSets.forStudent(studentId, cohort.asOf())) {
            order++;
            var perAssessment = totals.get(subject.subjectId());
            if (perAssessment == null || perAssessment.isEmpty()) {
                String status = nonScoringStatus.getOrDefault(subject.subjectId(), "not_assessed");
                results.add(new SubjectResult(subject.subjectId(), subject.subjectCode(), subject.subjectName(),
                    subject.origin(), null, null, null, status, null, null, order));
                continue;
            }

            double obtained = 0;
            double max = 0;
            double weightedPct = 0;
            double weightTotal = 0;
            boolean weighted = true;
            for (double[] running : perAssessment.values()) {
                obtained += running[0];
                max += running[1];
                if (running[2] < 0 || running[1] <= 0) {
                    weighted = false;
                } else {
                    weightedPct += (running[0] * 100.0 / running[1]) * running[2];
                    weightTotal += running[2];
                }
            }
            Double pct = max <= 0 ? null
                : round(weighted && weightTotal > 0 ? weightedPct / weightTotal : obtained * 100.0 / max);
            String grade = pct == null ? null : strategy.gradeFor(pct, scale);
            Boolean passing = pct == null ? null : strategy.isPassing(pct, scale);
            results.add(new SubjectResult(subject.subjectId(), subject.subjectCode(), subject.subjectName(),
                subject.origin(), round(obtained), round(max), pct, "marked", grade, passing, order));
        }
        return results;
    }

    /** Which terms the card can honestly speak for (ASMT-14). */
    private record Coverage(LocalDate enrolledFrom, int termsAttended, int termsInYear, String note) {}

    private Coverage coverage(UUID studentId, UUID academicYearId, Cohort cohort) {
        LocalDate enrolledFrom = jdbc.query(
            "SELECT min(e.starts_on) AS starts_on FROM enrolment e JOIN section s ON s.id = e.section_id " +
            "WHERE e.student_id = ? AND s.academic_year_id = ?",
            (rs, i) -> rs.getDate("starts_on") == null ? null : rs.getDate("starts_on").toLocalDate(),
            studentId, academicYearId).stream().findFirst().orElse(null);

        record Term(String name, LocalDate startsOn, LocalDate endsOn) {}
        List<Term> terms = jdbc.query(
            "SELECT name, starts_on, ends_on FROM term WHERE academic_year_id = ? ORDER BY starts_on",
            (rs, i) -> new Term(rs.getString("name"), rs.getDate("starts_on").toLocalDate(),
                rs.getDate("ends_on").toLocalDate()),
            academicYearId);

        if (enrolledFrom == null || terms.isEmpty()) {
            return new Coverage(enrolledFrom, terms.size(), terms.size(), null);
        }
        List<String> missed = new ArrayList<>();
        int attended = 0;
        for (Term term : terms) {
            // A term counts as attended when the student was on roll for its
            // start; joining halfway through a term still gets them the term,
            // with the attendance line telling the rest of the story.
            if (!enrolledFrom.isAfter(term.endsOn())) attended++;
            else missed.add(term.name());
        }
        String note = missed.isEmpty() ? null
            : "Joined on " + enrolledFrom + "; this card covers " + attended + " of " + terms.size()
              + " terms. No marks are shown for " + String.join(", ", missed)
              + " because the student was not on roll.";
        return new Coverage(enrolledFrom, attended, terms.size(), note);
    }

    private void writeSubjectRows(UUID cardId, List<SubjectResult> subjects) {
        List<Object[]> rows = new ArrayList<>();
        for (SubjectResult subject : subjects) {
            rows.add(new Object[]{
                UUID.randomUUID(), cardId, subject.subjectId(), subject.subjectCode(), subject.subjectName(),
                subject.origin(), subject.marksObtained(), subject.maxMarks(), subject.percentage(),
                subject.gradeLetter(), subject.status(), subject.passing(), subject.sortOrder()
            });
        }
        jdbc.batchUpdate(
            "INSERT INTO report_card_subject (id, report_card_id, subject_id, subject_code, subject_name, origin, " +
            "  marks_obtained, max_marks, percentage, grade_letter, result_status, is_passing, sort_order) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", rows);
    }

    private void writeCoScholastic(UUID cardId, List<CoScholasticInput> areas) {
        if (areas == null || areas.isEmpty()) return;
        List<Object[]> rows = new ArrayList<>();
        for (CoScholasticInput area : areas) {
            rows.add(new Object[]{UUID.randomUUID(), cardId, area.areaCode(), area.areaName(),
                area.rating(), area.remarks(), area.sortOrder()});
        }
        jdbc.batchUpdate(
            "INSERT INTO report_card_coscholastic (id, report_card_id, area_code, area_name, rating, remarks, sort_order) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)", rows);
    }

    /**
     * Dense ranking over the cohort's stored rank keys, ties sharing a place.
     * The percentile is the share of the cohort at or below the student, which
     * is stable under reruns and independent of the order rows come back in
     * (ASMT-11).
     */
    private void rankCohort(UUID sectionId, UUID academicYearId, UUID termId, String templateCode) {
        record Row(UUID id, Double rankKey) {}
        List<Row> cards = jdbc.query(
            "SELECT rc.id, rc.rank_key FROM report_card rc " +
            "WHERE rc.section_id = ? AND rc.academic_year_id = ? " +
            "  AND rc.term_id IS NOT DISTINCT FROM CAST(? AS uuid) AND rc.template_code = ? " +
            "ORDER BY rc.rank_key DESC NULLS LAST, rc.student_id",
            (rs, i) -> new Row(UUID.fromString(rs.getString("id")), Jdbc.nullableDouble(rs, "rank_key")),
            sectionId, academicYearId, termId, templateCode);

        List<Row> ranked = cards.stream().filter(c -> c.rankKey() != null).toList();
        int size = ranked.size();
        for (Row card : cards) {
            if (card.rankKey() == null) {
                jdbc.update("UPDATE report_card SET class_rank = NULL, class_size = ?, percentile = NULL WHERE id = ?",
                    size, card.id());
                continue;
            }
            long better = ranked.stream().filter(o -> o.rankKey() > card.rankKey() + 1e-9).count();
            long atOrBelow = ranked.stream().filter(o -> o.rankKey() <= card.rankKey() + 1e-9).count();
            int rank = (int) better + 1;
            double percentile = size == 0 ? 0 : round(atOrBelow * 100.0 / size);
            jdbc.update("UPDATE report_card SET class_rank = ?, class_size = ?, percentile = ? WHERE id = ?",
                rank, size, percentile, card.id());
        }
    }

    private record Existing(UUID id, String status, int version) {}

    private Existing existingCard(UUID studentId, UUID academicYearId, UUID termId, String templateCode) {
        return jdbc.query(
            "SELECT id, status, version FROM report_card WHERE student_id = ? AND academic_year_id = ? " +
            "  AND term_id IS NOT DISTINCT FROM CAST(? AS uuid) AND template_code = ?",
            (rs, i) -> new Existing(UUID.fromString(rs.getString("id")), rs.getString("status"), rs.getInt("version")),
            studentId, academicYearId, termId, templateCode).stream().findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> existingPayload(UUID cardId) {
        String raw = jdbc.query("SELECT payload::text FROM report_card WHERE id = ?",
            (rs, i) -> rs.getString(1), cardId).stream().findFirst().orElse(null);
        if (raw == null) return Map.of();
        try {
            return json.readValue(raw, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private PGobject jsonb(Object value) {
        try {
            PGobject object = new PGobject();
            object.setType("jsonb");
            object.setValue(json.writeValueAsString(value));
            return object;
        } catch (Exception e) {
            throw new IllegalStateException("Unserialisable report card payload", e);
        }
    }

    private static Double aggregatePercentage(List<SubjectResult> subjects) {
        double obtained = 0;
        double max = 0;
        for (SubjectResult subject : subjects) {
            if (!subject.counted() || subject.marksObtained() == null || subject.maxMarks() == null) continue;
            obtained += subject.marksObtained();
            max += subject.maxMarks();
        }
        return max <= 0 ? null : round(obtained * 100.0 / max);
    }

    private static Double sum(List<SubjectResult> subjects, boolean obtained) {
        double total = 0;
        boolean any = false;
        for (SubjectResult subject : subjects) {
            Double value = obtained ? subject.marksObtained() : subject.maxMarks();
            if (!subject.counted() || value == null) continue;
            total += value;
            any = true;
        }
        return any ? round(total) : null;
    }

    private static Double presentDays(AttendanceSummaryDto att) {
        return att.present() + att.late() + (att.halfDay() * 0.5);
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private UUID currentUserId() {
        var snap = TenantContext.get();
        return snap == null ? null : snap.userAccountId();
    }

    private static final String SELECT_CARD =
        "SELECT id, school_id, student_id, section_id, academic_year_id, term_id, strategy_code, template_code, " +
        "       status, version, is_locked, grade_scale_code, total_marks, total_max_marks, overall_pct, " +
        "       overall_grade, class_rank, class_size, percentile, attendance_working_days, " +
        "       attendance_present_days, attendance_pct, promotion_decision, teacher_remarks, principal_remarks, " +
        "       enrolled_from, terms_attended, terms_in_year, coverage_note, published_at, generated_at " +
        "FROM report_card";

    private static final RowMapper<ReportCardDto> CARD_MAPPER = (rs, i) -> new ReportCardDto(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("school_id")),
        UUID.fromString(rs.getString("student_id")),
        rs.getString("section_id") == null ? null : UUID.fromString(rs.getString("section_id")),
        UUID.fromString(rs.getString("academic_year_id")),
        rs.getString("term_id") == null ? null : UUID.fromString(rs.getString("term_id")),
        rs.getString("strategy_code"),
        rs.getString("template_code"),
        rs.getString("status"),
        rs.getInt("version"),
        rs.getBoolean("is_locked"),
        rs.getString("grade_scale_code"),
        Jdbc.nullableDouble(rs, "total_marks"),
        Jdbc.nullableDouble(rs, "total_max_marks"),
        Jdbc.nullableDouble(rs, "overall_pct"),
        rs.getString("overall_grade"),
        rs.getObject("class_rank") == null ? null : rs.getInt("class_rank"),
        rs.getObject("class_size") == null ? null : rs.getInt("class_size"),
        Jdbc.nullableDouble(rs, "percentile"),
        rs.getObject("attendance_working_days") == null ? null : rs.getInt("attendance_working_days"),
        Jdbc.nullableDouble(rs, "attendance_present_days"),
        Jdbc.nullableDouble(rs, "attendance_pct"),
        rs.getString("promotion_decision"),
        rs.getString("teacher_remarks"),
        rs.getString("principal_remarks"),
        rs.getDate("enrolled_from") == null ? null : rs.getDate("enrolled_from").toLocalDate(),
        rs.getObject("terms_attended") == null ? null : rs.getInt("terms_attended"),
        rs.getObject("terms_in_year") == null ? null : rs.getInt("terms_in_year"),
        rs.getString("coverage_note"),
        rs.getTimestamp("published_at") == null ? null : rs.getTimestamp("published_at").toInstant(),
        rs.getTimestamp("generated_at").toInstant());

    /**
     * Why a conditional transition matched no row. Either the card is gone, or
     * it is in a state this transition does not start from — and the caller
     * needs to be told which, because the two need different things done about
     * them.
     *
     * <p>Re-running a transition that has already happened is not an error: a
     * retried request should not fail because the first attempt succeeded.</p>
     */
    private void refuseTransition(UUID id, String transition, String expectedFrom) {
        ReportCardDto card = find(id);
        String target = switch (transition) {
            case "lock" -> "locked";
            case "unlock" -> "draft";
            default -> "published";
        };
        if (target.equals(card.status())) return;
        throw new ConflictException(
            "Cannot " + transition + " a report card that is '" + card.status() + "'"
            + " \u2014 the transition starts from " + expectedFrom + ".");
    }

}
