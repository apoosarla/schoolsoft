package com.schoolsoft.enrolment.api;

import com.schoolsoft.tenancy.api.NumberSeries;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Issues the next free roll number in a section (GAP-26).
 *
 * Shared rather than duplicated because two paths admit children — a direct
 * enrolment and an admission conversion — and a second copy of this logic is
 * how one of them ends up handing out a number the section already uses.
 */
@Service
public class RollNumbers {

    private final JdbcTemplate jdbc;
    private final NumberSeries numbers;

    public RollNumbers(JdbcTemplate jdbc, NumberSeries numbers) {
        this.jdbc = jdbc;
        this.numbers = numbers;
    }

    /**
     * Returns {@code supplied} when the caller named a number; otherwise the
     * section's series issues one. A section that already holds hand-keyed
     * rolls seeds the series past them, and the generator still skips a number
     * typed in by hand afterwards — the uniqueness index means a collision is a
     * failed admission, not a cosmetic problem.
     */
    public String nextFor(UUID schoolId, UUID sectionId, String supplied) {
        if (supplied != null && !supplied.isBlank()) return supplied;

        Integer highest = jdbc.queryForObject(
            "SELECT COALESCE(max(NULLIF(regexp_replace(roll_no, '\\D', '', 'g'), '')::int), 0) " +
            "FROM enrolment WHERE section_id = ? AND status = 'active' AND roll_no IS NOT NULL",
            Integer.class, sectionId);
        long startAt = (highest == null ? 0 : highest) + 1L;

        for (int attempt = 0; attempt < 50; attempt++) {
            String candidate = numbers.next(schoolId, NumberSeries.Kind.roll, sectionId, "{SEQ:2}", null, startAt);
            Integer taken = jdbc.queryForObject(
                "SELECT count(*) FROM enrolment WHERE section_id = ? AND roll_no = ? AND status = 'active'",
                Integer.class, sectionId, candidate);
            if (taken == null || taken == 0) return candidate;
        }
        throw new IllegalStateException("Could not find a free roll number in section " + sectionId);
    }
}
