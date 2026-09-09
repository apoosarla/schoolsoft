package com.schoolsoft.enrolment.api;

import com.schoolsoft.tenancy.api.NumberSeries;
import java.sql.Date;
import java.time.LocalDate;
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

        // Today's register on both counts: a child working out their notice
        // still holds their roll number, so it is neither free to reissue nor
        // safe to seed the series past.
        LocalDate today = LocalDate.now();
        Integer highest = jdbc.queryForObject(
            "SELECT COALESCE(max(NULLIF(regexp_replace(e.roll_no, '\\D', '', 'g'), '')::int), 0) " +
            "FROM enrolment e WHERE e.section_id = ? AND " + EnrolmentActivity.activeOn("e")
                + " AND e.roll_no IS NOT NULL",
            Integer.class, sectionId, Date.valueOf(today), Date.valueOf(today));
        long startAt = (highest == null ? 0 : highest) + 1L;

        for (int attempt = 0; attempt < 50; attempt++) {
            String candidate = numbers.next(schoolId, NumberSeries.Kind.roll, sectionId, "{SEQ:2}", null, startAt);
            Integer taken = jdbc.queryForObject(
                "SELECT count(*) FROM enrolment e WHERE e.section_id = ? AND e.roll_no = ? AND "
                    + EnrolmentActivity.activeOn("e"),
                Integer.class, sectionId, candidate, Date.valueOf(today), Date.valueOf(today));
            if (taken == null || taken == 0) return candidate;
        }
        throw new IllegalStateException("Could not find a free roll number in section " + sectionId);
    }
}
