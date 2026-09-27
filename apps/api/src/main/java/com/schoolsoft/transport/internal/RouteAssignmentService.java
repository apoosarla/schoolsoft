package com.schoolsoft.transport.internal;

import com.schoolsoft.audit.api.AuditService;
import com.schoolsoft.platform.time.SchoolClock;
import com.schoolsoft.platform.web.ConflictException;
import com.schoolsoft.platform.web.NotFoundException;
import com.schoolsoft.transport.api.RouteAssignmentDto;
import com.schoolsoft.transport.api.RouteClashDto;
import com.schoolsoft.transport.api.RouteGapDto;
import java.util.ArrayList;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rostering a driver and a vehicle to a route over a window of days. This is
 * what {@code RouteScope} reads to decide whose bus a driver may see, so until
 * it had a screen a newly linked driver could sign in and see nothing.
 *
 * <p>A route has one assignment in force on any day. Assigning from a date
 * <em>replaces</em> the one running then: the old window closes the day
 * before, the new one opens on the day, and nothing is deleted — trips already
 * driven under the old one still read as that driver's. The windows follow the
 * timetable's rules: a window may be shortened, never lengthened, and
 * {@code DELETE} is for an assignment made by mistake, before it started.</p>
 *
 * <p>{@code route_assignment} carries no {@code school_id} and so no RLS
 * policy. Every read and write here reaches it through {@code transport_route}
 * with the school named, and every id it is given is checked against a table
 * that does carry the policy.</p>
 */
@Service
public class RouteAssignmentService {

    private static final String IN_FORCE =
        "ra.effective_from <= ? AND COALESCE(ra.effective_to, 'infinity'::date) >= ?";

    private static final String SELECT =
        "SELECT ra.id, ra.route_id, r.code AS route_code, r.name AS route_name, " +
        "  ra.vehicle_id, v.registration_no, ra.driver_id, d.name AS driver_name, " +
        "  ra.effective_from, ra.effective_to " +
        "FROM route_assignment ra " +
        "JOIN transport_route r ON r.id = ra.route_id " +
        "JOIN vehicle v ON v.id = ra.vehicle_id " +
        "JOIN driver d ON d.id = ra.driver_id ";

    private static final RowMapper<RouteAssignmentDto> MAPPER = (rs, i) -> new RouteAssignmentDto(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("route_id")), rs.getString("route_code"), rs.getString("route_name"),
        UUID.fromString(rs.getString("vehicle_id")), rs.getString("registration_no"),
        UUID.fromString(rs.getString("driver_id")), rs.getString("driver_name"),
        rs.getDate("effective_from").toLocalDate(),
        rs.getDate("effective_to") == null ? null : rs.getDate("effective_to").toLocalDate());

    private final JdbcTemplate jdbc;
    private final SchoolClock clock;
    private final AuditService audit;

    public RouteAssignmentService(JdbcTemplate jdbc, AuditService audit, SchoolClock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    /** Every assignment of the school's routes, current and past, newest window first per route. */
    public List<RouteAssignmentDto> list(UUID schoolId, UUID routeId) {
        if (routeId != null) {
            return jdbc.query(SELECT + "WHERE r.school_id = ? AND ra.route_id = ? " +
                "ORDER BY ra.effective_from DESC", MAPPER, schoolId, routeId);
        }
        return jdbc.query(SELECT + "WHERE r.school_id = ? ORDER BY r.code, ra.effective_from DESC",
            MAPPER, schoolId);
    }

    /**
     * The first day from {@code today} on which each active route has nobody
     * assigned — a route that never had a driver, one whose assignment was
     * ended with nothing after it, or a hole between two windows. Only the
     * first gap per route is reported; closing it brings the next one up.
     */
    public List<RouteGapDto> gaps(UUID schoolId, LocalDate today) {
        record Window(UUID routeId, String code, String name, LocalDate from, LocalDate to) {}
        List<Window> rows = jdbc.query(
            "SELECT r.id, r.code, r.name, ra.effective_from, ra.effective_to FROM transport_route r " +
            "LEFT JOIN route_assignment ra ON ra.route_id = r.id " +
            "  AND COALESCE(ra.effective_to, 'infinity'::date) >= ? " +
            "WHERE r.school_id = ? AND r.is_active ORDER BY r.code, ra.effective_from",
            (rs, i) -> new Window(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getString(3),
                rs.getDate(4) == null ? null : rs.getDate(4).toLocalDate(),
                rs.getDate(5) == null ? null : rs.getDate(5).toLocalDate()),
            Date.valueOf(today), schoolId);

        List<RouteGapDto> gaps = new ArrayList<>();
        int i = 0;
        while (i < rows.size()) {
            Window first = rows.get(i);
            // `covered` is the first day not yet known to have a driver; null once open-ended.
            LocalDate covered = today;
            RouteGapDto gap = null;
            for (; i < rows.size() && rows.get(i).routeId().equals(first.routeId()); i++) {
                Window w = rows.get(i);
                if (w.from() == null || covered == null || gap != null) continue;
                if (w.from().isAfter(covered)) {
                    gap = new RouteGapDto(w.routeId(), w.code(), w.name(), covered, w.from());
                    continue;
                }
                if (w.to() == null) covered = null;
                else if (!w.to().isBefore(covered)) covered = w.to().plusDays(1);
            }
            if (gap == null && covered != null) {
                gap = new RouteGapDto(first.routeId(), first.code(), first.name(), covered, null);
            }
            if (gap != null) gaps.add(gap);
        }
        return gaps;
    }

    /** One assignment window, with what a clash check compares. {@code id} is null for a proposal. */
    private record Slot(UUID id, UUID routeId, String code, String direction, UUID driverId, String driverName,
                        UUID vehicleId, String registrationNo, LocalDate from, LocalDate to) {}

    /** Every clash between saved assignments that is still to come, from {@code today}. */
    public List<RouteClashDto> clashes(UUID schoolId, LocalDate today) {
        List<Slot> slots = slotsFrom(schoolId, today);
        List<RouteClashDto> out = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            for (int j = i + 1; j < slots.size(); j++) {
                clash(slots.get(i), slots.get(j), today, out);
            }
        }
        return out;
    }

    /**
     * The clashes a proposed assignment would make, before it is saved — what
     * the office is warned of ahead of pressing Assign. The route's own
     * current window is left out: the proposal replaces it.
     */
    public List<RouteClashDto> clashesFor(UUID schoolId, UUID routeId, UUID vehicleId, UUID driverId,
                                          LocalDate effectiveFrom, LocalDate today) {
        if (effectiveFrom == null) throw new IllegalArgumentException("An assignment needs a first day");
        var route = jdbc.query("SELECT code, direction FROM transport_route WHERE id = ? AND school_id = ?",
            (rs, i) -> new String[]{ rs.getString(1), rs.getString(2) }, routeId, schoolId);
        if (route.isEmpty()) throw new NotFoundException("Route " + routeId + " is not in school " + schoolId);
        String driverName = jdbc.query("SELECT name FROM driver WHERE id = ? AND school_id = ?",
            (rs, i) -> rs.getString(1), driverId, schoolId).stream().findFirst()
            .orElseThrow(() -> new NotFoundException("Driver " + driverId + " is not in school " + schoolId));
        String registrationNo = jdbc.query("SELECT registration_no FROM vehicle WHERE id = ? AND school_id = ?",
            (rs, i) -> rs.getString(1), vehicleId, schoolId).stream().findFirst()
            .orElseThrow(() -> new NotFoundException("Vehicle " + vehicleId + " is not in school " + schoolId));

        Slot proposed = new Slot(null, routeId, route.get(0)[0], route.get(0)[1], driverId, driverName,
            vehicleId, registrationNo, effectiveFrom, null);
        List<RouteClashDto> out = new ArrayList<>();
        for (Slot saved : slotsFrom(schoolId, today)) {
            if (!saved.routeId().equals(routeId)) clash(saved, proposed, today, out);
        }
        return out;
    }

    private List<Slot> slotsFrom(UUID schoolId, LocalDate today) {
        return jdbc.query(
            "SELECT ra.id, ra.route_id, r.code, r.direction, ra.driver_id, d.name, ra.vehicle_id, " +
            "  v.registration_no, ra.effective_from, ra.effective_to " +
            "FROM route_assignment ra " +
            "JOIN transport_route r ON r.id = ra.route_id " +
            "JOIN vehicle v ON v.id = ra.vehicle_id " +
            "JOIN driver d ON d.id = ra.driver_id " +
            "WHERE r.school_id = ? AND r.is_active AND COALESCE(ra.effective_to, 'infinity'::date) >= ? " +
            "ORDER BY r.code, ra.effective_from",
            (rs, i) -> new Slot(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)),
                rs.getString(3), rs.getString(4), UUID.fromString(rs.getString(5)), rs.getString(6),
                UUID.fromString(rs.getString(7)), rs.getString(8), rs.getDate(9).toLocalDate(),
                rs.getDate(10) == null ? null : rs.getDate(10).toLocalDate()),
            schoolId, Date.valueOf(today));
    }

    /** Adds the driver clash and the vehicle clash between {@code a} and {@code b}, if any. */
    private static void clash(Slot a, Slot b, LocalDate today, List<RouteClashDto> out) {
        if (a.routeId().equals(b.routeId())) return;
        if (!sameWay(a.direction(), b.direction())) return;
        LocalDate from = max(max(a.from(), b.from()), today);
        LocalDate to = a.to() == null ? b.to() : b.to() == null ? a.to() : (a.to().isBefore(b.to()) ? a.to() : b.to());
        if (to != null && to.isBefore(from)) return;
        if (a.driverId().equals(b.driverId())) {
            out.add(new RouteClashDto("driver", a.driverId(), a.driverName(), a.id(), a.code(), a.direction(),
                b.id(), b.code(), b.direction(), from, to));
        }
        if (a.vehicleId().equals(b.vehicleId())) {
            out.add(new RouteClashDto("vehicle", a.vehicleId(), a.registrationNo(), a.id(), a.code(), a.direction(),
                b.id(), b.code(), b.direction(), from, to));
        }
    }

    private static boolean sameWay(String a, String b) {
        return a.equals(b) || "both".equals(a) || "both".equals(b);
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    /**
     * Rosters {@code driverId} in {@code vehicleId} to the route from
     * {@code effectiveFrom}, closing whatever was running on the route that
     * day. Refused when the route already has an assignment starting on or
     * after that day — replacing a future window is ending it first.
     */
    @Transactional
    public RouteAssignmentDto assign(UUID schoolId, UUID routeId, UUID vehicleId, UUID driverId,
                                     LocalDate effectiveFrom) {
        if (effectiveFrom == null) throw new IllegalArgumentException("An assignment needs a first day");
        // Serialises two offices assigning the same route at once.
        var route = jdbc.query("SELECT code FROM transport_route WHERE id = ? AND school_id = ? FOR UPDATE",
            (rs, i) -> rs.getString(1), routeId, schoolId);
        if (route.isEmpty()) throw new NotFoundException("Route " + routeId + " is not in school " + schoolId);
        String code = route.get(0);
        requireIn(schoolId, "vehicle", vehicleId, "Vehicle");
        Boolean active = jdbc.query("SELECT is_active FROM driver WHERE id = ? AND school_id = ?",
            (rs, i) -> rs.getBoolean(1), driverId, schoolId).stream().findFirst()
            .orElseThrow(() -> new NotFoundException("Driver " + driverId + " is not in school " + schoolId));
        if (!active) throw new IllegalArgumentException("That driver is no longer active");

        Date from = Date.valueOf(effectiveFrom);
        Integer later = jdbc.queryForObject(
            "SELECT count(*) FROM route_assignment WHERE route_id = ? AND effective_from >= ?",
            Integer.class, routeId, from);
        if (later != null && later > 0) {
            throw new ConflictException(code + " already has an assignment starting on or after "
                + effectiveFrom + "; end or delete that one first");
        }

        // The one running on the day closes the day before.
        var closed = jdbc.query(
            "UPDATE route_assignment ra SET effective_to = ? WHERE ra.route_id = ? AND " + IN_FORCE +
            " RETURNING ra.id",
            (rs, i) -> rs.getString(1), Date.valueOf(effectiveFrom.minusDays(1)), routeId, from, from);

        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO route_assignment (id, route_id, vehicle_id, driver_id, effective_from) " +
            "VALUES (?, ?, ?, ?, ?)", id, routeId, vehicleId, driverId, from);

        audit.record("transport.route.assign", "transport_route", routeId, null,
            Map.of("assignmentId", id.toString(), "driverId", driverId.toString(),
                "vehicleId", vehicleId.toString(), "effectiveFrom", effectiveFrom.toString(),
                "replaced", closed));
        return get(schoolId, id);
    }

    /**
     * Sets the last day the assignment counts. Shortening only: an end that
     * would reach past the current one puts a driver back on days already
     * handed to somebody else. Ending on the day it already ends is a retry,
     * not an error.
     */
    @Transactional
    public RouteAssignmentDto end(UUID schoolId, UUID id, LocalDate lastDay) {
        if (lastDay == null) throw new IllegalArgumentException("Ending an assignment needs its last day");
        RouteAssignmentDto current = get(schoolId, id);
        if (lastDay.equals(current.effectiveTo())) return current;
        if (lastDay.isBefore(current.effectiveFrom())) {
            throw new IllegalArgumentException("It starts on " + current.effectiveFrom()
                + "; an assignment that never ran is deleted, not ended");
        }
        int moved = jdbc.update(
            "UPDATE route_assignment SET effective_to = ? WHERE id = ? " +
            "  AND (effective_to IS NULL OR effective_to > ?)",
            Date.valueOf(lastDay), id, Date.valueOf(lastDay));
        if (moved == 0) {
            throw new ConflictException("It already ends on " + current.effectiveTo()
                + "; an assignment can be shortened but not lengthened");
        }
        audit.record("transport.route.end", "transport_route", current.routeId(),
            Map.of("effectiveTo", String.valueOf(current.effectiveTo())),
            Map.of("assignmentId", id.toString(), "effectiveTo", lastDay.toString()));
        return get(schoolId, id);
    }

    /** Removes an assignment made by mistake. One that has started is ended instead. */
    @Transactional
    public void delete(UUID schoolId, UUID id) {
        RouteAssignmentDto current = get(schoolId, id);
        int removed = jdbc.update(
            "DELETE FROM route_assignment WHERE id = ? AND effective_from > ?", id, java.sql.Date.valueOf(clock.today(schoolId)));
        if (removed == 0) {
            throw new ConflictException("That assignment started on " + current.effectiveFrom()
                + "; end it instead, so the days it ran still show who drove");
        }
        audit.record("transport.route.unassign", "transport_route", current.routeId(),
            Map.of("assignmentId", id.toString(), "driverId", current.driverId().toString(),
                "effectiveFrom", current.effectiveFrom().toString()), null);
    }

    private RouteAssignmentDto get(UUID schoolId, UUID id) {
        return jdbc.query(SELECT + "WHERE r.school_id = ? AND ra.id = ?", MAPPER, schoolId, id)
            .stream().findFirst()
            .orElseThrow(() -> new NotFoundException("Route assignment " + id + " is not in school " + schoolId));
    }

    private void requireIn(UUID schoolId, String table, UUID id, String what) {
        Integer found = jdbc.queryForObject(
            "SELECT count(*) FROM " + table + " WHERE id = ? AND school_id = ?", Integer.class, id, schoolId);
        if (found == null || found == 0) throw new NotFoundException(what + " " + id + " is not in school " + schoolId);
    }
}
