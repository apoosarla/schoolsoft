package com.schoolsoft.tenancy.api;

import com.schoolsoft.platform.web.NotFoundException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Enforces {@code section.capacity} (GAP-10).
 *
 * The rule is deliberately not absolute: schools do admit the 31st child into
 * a section of 30, and refusing outright would only teach the office to raise
 * the capacity number and forget. So the seat check refuses by default and
 * accepts an explicit reason, which is stored on the enrolment — a year later
 * "why is 5A holding 33" has an answer.
 */
@Service
public class SectionCapacity {

    private final JdbcTemplate jdbc;

    public SectionCapacity(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Occupancy(UUID sectionId, Integer capacity, int active, Integer seatsLeft) {}

    public Occupancy occupancyOf(UUID sectionId) {
        var rows = jdbc.query(
            "SELECT s.capacity, (SELECT count(*) FROM enrolment e " +
            "                    WHERE e.section_id = s.id AND e.status = 'active') AS active " +
            "FROM section s WHERE s.id = ?",
            (rs, i) -> new Object[]{ (Integer) rs.getObject("capacity"), rs.getInt("active") },
            sectionId);
        if (rows.isEmpty()) throw new NotFoundException("Section not found: " + sectionId);
        Integer capacity = (Integer) rows.get(0)[0];
        int active = (Integer) rows.get(0)[1];
        return new Occupancy(sectionId, capacity, active,
            capacity == null ? null : Math.max(0, capacity - active));
    }

    /**
     * Refuses a seat the section does not have, unless the caller supplies a
     * reason. Returns the reason to store on the enrolment (null when the
     * section had room).
     */
    public String reserveSeat(UUID sectionId, String overCapacityReason) {
        Occupancy occupancy = occupancyOf(sectionId);
        if (occupancy.capacity() == null || occupancy.active() < occupancy.capacity()) {
            return null;
        }
        if (overCapacityReason == null || overCapacityReason.isBlank()) {
            throw new IllegalArgumentException(
                "Section is full: capacity " + occupancy.capacity() + ", " + occupancy.active()
                + " active enrolments. Supply overCapacityReason to admit anyway.");
        }
        return overCapacityReason;
    }
}
