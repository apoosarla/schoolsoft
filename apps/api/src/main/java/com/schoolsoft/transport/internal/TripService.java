package com.schoolsoft.transport.internal;

import com.schoolsoft.notification.api.Notice;
import com.schoolsoft.notification.api.NotificationService;
import com.schoolsoft.transport.api.TripDto;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The trip lifecycle as a use case rather than a manifest edit.
 *
 * <p>A check-in is the one moment the school knows something a parent does
 * not and cannot find out: the child is on the bus, or off it (TRN-03). The
 * driver taps it; the message is not a separate feature the driver has to
 * remember.</p>
 *
 * <p>Boarding and alighting are separate events with separate keys, so a
 * driver who taps twice on a patchy connection sends one message per event and
 * not one per tap.</p>
 */
@Service
public class TripService {

    /** Check-in statuses a family is told about, and the template each uses. */
    private static final Map<String, String> BOARDING_TEMPLATES = Map.of(
        "boarded", "transport_boarded",
        "dropped", "transport_alighted"
    );

    private final TransportRepository repo;
    private final NotificationService notifications;

    public TripService(TransportRepository repo, NotificationService notifications) {
        this.repo = repo;
        this.notifications = notifications;
    }

    public TripDto checkIn(UUID tripId, UUID studentId, String status) {
        TripDto trip = repo.checkIn(tripId, studentId, status);

        String template = BOARDING_TEMPLATES.get(status);
        if (template == null) return trip;   // 'absent' is a roster fact, not news for the parent

        Map<String, Object> vars = new HashMap<>();
        vars.put("status", status);
        vars.put("direction", trip.direction());
        vars.put("routeName", repo.routeName(trip.routeId()));
        vars.put("at", java.time.Instant.now().toString());

        notifications.notifyGuardiansOfStudent(studentId,
            Notice.of(trip.schoolId(), template, vars)
                .about("trip", tripId)
                .oncePer("trip:" + tripId + ":" + studentId + ":" + status));
        return trip;
    }
}
