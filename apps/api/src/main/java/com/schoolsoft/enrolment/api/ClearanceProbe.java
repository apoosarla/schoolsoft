package com.schoolsoft.enrolment.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * What one area of the school has to say about a child who is leaving.
 *
 * <p>The alternative was for the enrolment module to ask fees, library and
 * transport directly — which would mean enrolment depending on three modules so
 * that it can run a checklist, and a fourth area (an asset register, a hostel, a
 * lab) meaning a fourth edit to a service that has nothing to do with any of
 * them. Instead each module publishes what it knows about a leaver and enrolment
 * collects the answers: the dependency points inward, and a module that has
 * nothing to say simply does not implement this.</p>
 *
 * <p>An implementation lives in the <em>owning</em> module ({@code fees},
 * {@code library}, {@code transport}), because "is anything outstanding" is that
 * module's question and the answer must not drift from its own screens.</p>
 */
public interface ClearanceProbe {

    /** One of the {@code clearance_item.area} values this probe answers for. */
    String area();

    /**
     * What is outstanding for {@code studentId} as of {@code lastWorkingDate}.
     *
     * <p>Read-only. It runs when the withdrawal is filed and again whenever the
     * checklist is refreshed, so it must be safe to call repeatedly.</p>
     */
    Finding probe(UUID studentId, LocalDate lastWorkingDate);

    /**
     * Closes this area out, at the moment the withdrawal completes: transport
     * ends the bus seat, and an area with nothing to wind down does nothing.
     *
     * <p>Called once, inside the completing transaction, and only after the
     * checklist says the area is clear or waived.</p>
     */
    default void settle(UUID studentId, LocalDate lastWorkingDate) {}

    /**
     * {@code blocking} is the whole point: it is the difference between "this
     * child owes ₹18,000" and "this child may not be released". An area reports
     * what it knows and whether that should stop the exit; only a
     * {@code withdrawal.override} holder can then let it through.
     *
     * @param detail human-readable, and shown on the checklist verbatim
     * @param amount money outstanding where the area deals in money, else null
     */
    record Finding(boolean blocking, String detail, Double amount) {

        public static Finding clear(String detail) { return new Finding(false, detail, null); }

        public static Finding blocked(String detail, Double amount) {
            return new Finding(true, detail, amount);
        }
    }
}
