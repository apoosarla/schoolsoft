package com.schoolsoft.notification.api;

import com.schoolsoft.notification.internal.ChannelRouter;
import com.schoolsoft.notification.internal.NotificationRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Public entry point for other modules. Synchronous facade; internally the
 * dispatch is recorded immediately and the actual send is fire-and-forget via
 * the router (which would push to a job queue in prod).
 *
 * <p>Producers are expected to hand over a {@link Notice} and let this class
 * work out the recipients. That is deliberate: "who hears about this child"
 * is one rule — the {@code guardian_student} join, the
 * communications-recipient flag, the per-channel opt-ins — and a rule copied
 * into four modules is a rule that will be four different rules by the end of
 * the year.</p>
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository repo;
    private final ChannelRouter router;

    public NotificationService(NotificationRepository repo, ChannelRouter router) {
        this.repo = repo;
        this.router = router;
    }

    /**
     * Dispatches to one resolved recipient.
     *
     * @return the number of channels this call actually sent on — zero when
     *         the recipient is unknown, opted out of everything, or has
     *         already been told (see {@link Notice#dedupeKey()}).
     */
    public int notify(NotifyRequest req) {
        var resolved = repo.resolveRecipient(req.recipientType(), req.recipientId());
        if (resolved == null) {
            log.warn("Notify skipped: recipient not found {} / {}", req.recipientType(), req.recipientId());
            return 0;
        }
        List<String> channels = router.filterByOptIn(req.channels(), resolved);
        if (channels.isEmpty()) {
            log.info("Notify skipped: no opted-in channels for {} / {}", req.recipientType(), req.recipientId());
            return 0;
        }
        int dispatched = 0;
        for (String ch : channels) {
            UUID dispatchId = repo.recordDispatch(
                req.schoolId(),
                req.recipientType(),
                req.recipientId(),
                ch,
                req.templateCode(),
                req.language(),
                req.variables(),
                req.relatedType(),
                req.relatedId(),
                req.dedupeKey()
            );
            if (dispatchId == null) {
                log.debug("Notify suppressed as duplicate: key={} to {}/{} on {}",
                    req.dedupeKey(), req.recipientType(), req.recipientId(), ch);
                continue;
            }
            router.send(ch, resolved, req.templateCode(), req.variables(), dispatchId);
            dispatched++;
        }
        return dispatched;
    }

    public void notify(UUID schoolId, String recipientType, UUID recipientId,
                       String templateCode, Map<String, Object> vars) {
        notify(new NotifyRequest(
            schoolId, recipientType, recipientId, templateCode, "en", vars,
            List.of("whatsapp", "push", "email"), null, null, null
        ));
    }

    /** Sends {@code notice} to a recipient the caller has already identified. */
    public int notify(Notice notice, String recipientType, UUID recipientId) {
        return notify(notice.to(recipientType, recipientId));
    }

    /**
     * Tells the guardians of one child. The child's name is added to the
     * template variables as {@code studentName} so every producer does not
     * have to look it up.
     *
     * @return recipients reached, not channels used
     */
    public int notifyGuardiansOfStudent(UUID studentId, Notice notice) {
        String name = repo.studentName(studentId);
        Map<String, Object> vars = new HashMap<>(notice.variables() == null ? Map.of() : notice.variables());
        vars.putIfAbsent("studentName", name == null ? "" : name);
        vars.putIfAbsent("studentId", studentId.toString());
        return notifyGuardiansOfStudents(List.of(studentId), notice.withVariables(vars));
    }

    /**
     * Tells the guardians of a cohort — an announcement's audience, a route's
     * riders. A parent of two children in scope hears once, which is why the
     * guardian set is resolved as a set before anything is dispatched.
     *
     * @return recipients reached, not channels used
     */
    public int notifyGuardiansOfStudents(Collection<UUID> studentIds, Notice notice) {
        List<UUID> guardians = repo.communicationsGuardiansOf(studentIds);
        int reached = 0;
        for (UUID guardianId : guardians) {
            if (notify(notice.to("guardian", guardianId)) > 0) reached++;
        }
        return reached;
    }

    /** What went out about one announcement, invoice or trip. */
    public DeliveryStats deliveryFor(String relatedType, UUID relatedId) {
        return repo.statsFor(relatedType, relatedId);
    }
}
