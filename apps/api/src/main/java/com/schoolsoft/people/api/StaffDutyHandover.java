package com.schoolsoft.people.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What a member of staff would leave behind, asked of the modules that know.
 *
 * <p>The people module records that somebody is leaving; it does not know what
 * a section is or what a period is, and should not learn. Each module that
 * hangs standing work off a staff id implements this, the way fees, the
 * library and transport each implement {@code ClearanceProbe} for a child's
 * exit — so an exit cannot be filed over work nobody has been given, and a new
 * kind of duty is a new bean rather than an edit here.</p>
 *
 * <p>Both methods are about the days <em>after</em> {@code lastDay}. What the
 * leaver did up to and including it stays theirs: marks, registers and lesson
 * plans carry the id of whoever wrote them and are not touched.</p>
 */
public interface StaffDutyHandover {

    /** One thing that would be left without an owner, in words an office reads. */
    record Duty(String area, String description) {}

    /** What {@code staffId} still holds the day after {@code lastDay}. */
    List<Duty> heldAfter(UUID staffId, LocalDate lastDay);

    /**
     * Moves everything {@link #heldAfter} lists to {@code successorStaffId}
     * from the day after {@code lastDay}. Runs inside the exit's transaction;
     * throwing refuses the exit whole.
     */
    void handOver(UUID staffId, UUID successorStaffId, LocalDate lastDay);
}
