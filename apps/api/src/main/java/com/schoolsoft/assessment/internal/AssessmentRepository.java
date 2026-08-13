package com.schoolsoft.assessment.internal;

import com.schoolsoft.assessment.api.AssessmentComponentDto;
import com.schoolsoft.assessment.api.AssessmentDto;
import com.schoolsoft.iam.api.Authz;
import com.schoolsoft.platform.db.Jdbc;
import com.schoolsoft.platform.web.ConflictException;
import com.schoolsoft.platform.web.ForbiddenException;
import com.schoolsoft.platform.web.NotFoundException;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Assessments and their components, and the lifecycle they move through.
 *
 * The lifecycle used to be decorative: the statuses were stored and nothing
 * consulted them. Two rules now hang off it — marks are refused once the
 * assessment is sealed (enforced in {@link MarkService}), and an assessment
 * cannot open for marking while its components do not add up (ASMT-03). The
 * second is deliberately a gate rather than a warning: weights that do not sum
 * are discovered on a report card otherwise, which is weeks too late.
 */
@Repository
public class AssessmentRepository {

    private final JdbcTemplate jdbc;
    private final Authz authz;
    private final AssessmentPolicyRepository policies;

    public AssessmentRepository(JdbcTemplate jdbc, Authz authz, AssessmentPolicyRepository policies) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.policies = policies;
    }

    // -------------------------- Assessment --------------------------

    private static final RowMapper<AssessmentDto> ASSESSMENT_MAPPER = (rs, i) -> new AssessmentDto(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("school_id")),
        UUID.fromString(rs.getString("section_id")),
        UUID.fromString(rs.getString("subject_id")),
        rs.getString("term_id") == null ? null : UUID.fromString(rs.getString("term_id")),
        rs.getString("strategy_code"),
        rs.getString("name"),
        rs.getString("assessment_type"),
        Jdbc.nullableDouble(rs, "max_marks"),
        Jdbc.nullableDouble(rs, "weight_pct"),
        rs.getDate("scheduled_on") == null ? null : rs.getDate("scheduled_on").toLocalDate(),
        rs.getString("status")
    );

    private static final String ASSESSMENT_COLS =
        "id, school_id, section_id, subject_id, term_id, strategy_code, name, assessment_type, " +
        "max_marks, weight_pct, scheduled_on, status";

    public List<AssessmentDto> listBySection(UUID sectionId) {
        return jdbc.query(
            "SELECT " + ASSESSMENT_COLS + " FROM assessment WHERE section_id = ? ORDER BY scheduled_on NULLS LAST",
            ASSESSMENT_MAPPER, sectionId
        );
    }

    public Optional<AssessmentDto> find(UUID id) {
        var rows = jdbc.query("SELECT " + ASSESSMENT_COLS + " FROM assessment WHERE id = ?", ASSESSMENT_MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public AssessmentDto create(
        UUID schoolId, UUID sectionId, UUID subjectId, UUID termId, String strategyCode,
        String name, String assessmentType, Double maxMarks, Double weightPct, LocalDate scheduledOn
    ) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO assessment (id, school_id, section_id, subject_id, term_id, strategy_code, name, " +
            "  assessment_type, max_marks, weight_pct, scheduled_on) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, schoolId, sectionId, subjectId, termId, strategyCode, name, assessmentType,
            maxMarks, weightPct, scheduledOn == null ? null : Date.valueOf(scheduledOn)
        );
        return find(id).orElseThrow();
    }

    /** Statuses past which marks are considered final, and reopening one is a decision. */
    private static final List<String> SEALED = MarkService.SEALED;

    /** Statuses that mean "marking is open or done" — the ones the weight gate guards. */
    private static final List<String> MARKING_OR_LATER = List.of("marking", "locked", "published");

    /** Roles allowed to reopen a sealed assessment. */
    private static final List<String> UNLOCK_ROLES = MarkService.EXAM_AUTHORITY_ROLES;

    public AssessmentDto setStatus(UUID id, String status, String reason) {
        AssessmentDto current = find(id)
            .orElseThrow(() -> new NotFoundException("Assessment not found: " + id));

        // Unlocking is the high-risk direction: marks a parent has already seen
        // become editable again, so it needs an authorised role and a reason,
        // and the audit entry at the endpoint records both (SEC-08).
        if (SEALED.contains(current.status()) && !SEALED.contains(status)) {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException(
                    "Reopening a " + current.status() + " assessment needs a reason");
            }
            if (authz.rolesOfCurrentUser().stream().noneMatch(UNLOCK_ROLES::contains)) {
                throw new ForbiddenException(
                    "Your role cannot reopen a " + current.status() + " assessment (needs one of " + UNLOCK_ROLES + ")");
            }
        }

        // Opening for marking is the last moment the shape of the assessment can
        // still be fixed cheaply (ASMT-03). Only the arithmetic blocks: an
        // assessment with no components yet has nothing to mark and harms
        // nobody, but one whose weights do not sum produces a wrong report card
        // silently, weeks later.
        if (MARKING_OR_LATER.contains(status) && !MARKING_OR_LATER.contains(current.status())) {
            List<String> blocking = weightIssues(id);
            if (!blocking.isEmpty()) {
                throw new ConflictException(
                    "Assessment " + current.name() + " cannot open for marking: " + String.join("; ", blocking));
            }
        }

        int updated = jdbc.update("UPDATE assessment SET status = ? WHERE id = ?", status, id);
        if (updated == 0) throw new NotFoundException("Assessment not found: " + id);
        return find(id).orElseThrow();
    }

    // -------------------------- Validation (ASMT-03) --------------------------

    /** What is wrong with an assessment's shape, in the words a teacher needs. */
    public record Validation(UUID assessmentId, boolean valid, List<String> issues) {}

    /**
     * Component weights must sum to 100% of the assessment, and their marks to
     * its total. Both are checked against the school's tolerance, because a
     * three-way split of 100 cannot be expressed exactly and refusing 99.99 is
     * not a rule anybody wants.
     */
    public Validation validate(UUID assessmentId) {
        List<String> issues = new ArrayList<>();
        if (listComponents(assessmentId).isEmpty()) {
            issues.add("the assessment has no components, so there is nothing to mark");
            return new Validation(assessmentId, false, issues);
        }
        issues.addAll(weightIssues(assessmentId));
        return new Validation(assessmentId, issues.isEmpty(), issues);
    }

    /** The arithmetic half of {@link #validate}: what makes a mark mean the wrong thing. */
    private List<String> weightIssues(UUID assessmentId) {
        AssessmentDto assessment = find(assessmentId)
            .orElseThrow(() -> new NotFoundException("Assessment not found: " + assessmentId));
        var components = listComponents(assessmentId);
        double tolerance = policies.forSchool(assessment.schoolId()).weightTolerancePct();
        List<String> issues = new ArrayList<>();
        if (components.isEmpty()) return issues;

        long weighted = components.stream().filter(c -> c.weightPct() != null).count();
        if (weighted > 0 && weighted < components.size()) {
            issues.add("some components carry a weight and others do not; weight all of them or none");
        } else if (weighted == components.size()) {
            double sum = components.stream().mapToDouble(AssessmentComponentDto::weightPct).sum();
            if (Math.abs(sum - 100.0) > tolerance) {
                issues.add("component weights sum to " + round(sum) + "%, not 100%");
            }
        }

        if (assessment.maxMarks() != null) {
            double sum = components.stream().mapToDouble(AssessmentComponentDto::maxMarks).sum();
            if (Math.abs(sum - assessment.maxMarks()) > tolerance) {
                issues.add("component marks sum to " + round(sum) + ", not the assessment total of "
                    + round(assessment.maxMarks()));
            }
        }
        return issues;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    // -------------------------- Assessment Component --------------------------

    private static final RowMapper<AssessmentComponentDto> COMPONENT_MAPPER = (rs, i) -> new AssessmentComponentDto(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("assessment_id")),
        rs.getString("code"),
        rs.getString("name"),
        rs.getDouble("max_marks"),
        Jdbc.nullableDouble(rs, "weight_pct"),
        rs.getInt("sort_order")
    );

    public List<AssessmentComponentDto> listComponents(UUID assessmentId) {
        return jdbc.query(
            "SELECT id, assessment_id, code, name, max_marks, weight_pct, sort_order FROM assessment_component " +
            "WHERE assessment_id = ? ORDER BY sort_order",
            COMPONENT_MAPPER, assessmentId
        );
    }

    public AssessmentComponentDto addComponent(
        UUID assessmentId, String code, String name, double maxMarks, Double weightPct, int sortOrder
    ) {
        AssessmentDto assessment = find(assessmentId)
            .orElseThrow(() -> new NotFoundException("Assessment not found: " + assessmentId));
        if (SEALED.contains(assessment.status())) {
            throw new ConflictException(
                "Assessment " + assessment.name() + " is " + assessment.status()
                + "; its components cannot change while it is sealed");
        }
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO assessment_component (id, assessment_id, code, name, max_marks, weight_pct, sort_order) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            id, assessmentId, code, name, maxMarks, weightPct, sortOrder
        );
        return jdbc.queryForObject(
            "SELECT id, assessment_id, code, name, max_marks, weight_pct, sort_order FROM assessment_component WHERE id = ?",
            COMPONENT_MAPPER, id
        );
    }
}
