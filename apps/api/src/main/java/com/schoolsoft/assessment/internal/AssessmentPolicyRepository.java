package com.schoolsoft.assessment.internal;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * The two assessment decisions that belong to a school rather than to the
 * code: whether unpaid fees withhold a report card (ASMT-15), and how exactly
 * component weights have to add up (ASMT-03).
 *
 * A school with no row gets the safe defaults — release the card, require the
 * weights to sum — rather than an error, because the first time anybody asks is
 * usually while cards are being printed.
 */
@Repository
public class AssessmentPolicyRepository {

    public record Policy(UUID schoolId, String duesBlockPolicy, double duesBlockThreshold,
                         double weightTolerancePct) {}

    private static final RowMapper<Policy> MAPPER = (rs, i) -> new Policy(
        UUID.fromString(rs.getString("school_id")),
        rs.getString("dues_block_policy"),
        rs.getDouble("dues_block_threshold"),
        rs.getDouble("weight_tolerance_pct"));

    private final JdbcTemplate jdbc;

    public AssessmentPolicyRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Policy forSchool(UUID schoolId) {
        return jdbc.query(
            "SELECT school_id, dues_block_policy, dues_block_threshold, weight_tolerance_pct " +
            "FROM assessment_policy WHERE school_id = ?", MAPPER, schoolId)
            .stream().findFirst()
            .orElse(new Policy(schoolId, "release", 0, 0.01));
    }

    public Policy upsert(UUID schoolId, String duesBlockPolicy, Double duesBlockThreshold,
                         Double weightTolerancePct) {
        Policy current = forSchool(schoolId);
        jdbc.update(
            "INSERT INTO assessment_policy (school_id, dues_block_policy, dues_block_threshold, weight_tolerance_pct) " +
            "VALUES (?, ?, ?, ?) " +
            "ON CONFLICT (school_id) DO UPDATE SET dues_block_policy = EXCLUDED.dues_block_policy, " +
            "  dues_block_threshold = EXCLUDED.dues_block_threshold, " +
            "  weight_tolerance_pct = EXCLUDED.weight_tolerance_pct, updated_at = now()",
            schoolId,
            duesBlockPolicy == null ? current.duesBlockPolicy() : duesBlockPolicy,
            duesBlockThreshold == null ? current.duesBlockThreshold() : duesBlockThreshold,
            weightTolerancePct == null ? current.weightTolerancePct() : weightTolerancePct);
        return forSchool(schoolId);
    }
}
