package com.schoolsoft.notification.api;

import com.schoolsoft.notification.internal.ChannelRouter;
import com.schoolsoft.notification.internal.NotificationRepository;
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

    public void notify(NotifyRequest req) {
        var resolved = repo.resolveRecipient(req.recipientType(), req.recipientId());
        if (resolved == null) {
            log.warn("Notify skipped: recipient not found {} / {}", req.recipientType(), req.recipientId());
            return;
        }
        List<String> channels = router.filterByOptIn(req.channels(), resolved);
        if (channels.isEmpty()) {
            log.info("Notify skipped: no opted-in channels for {} / {}", req.recipientType(), req.recipientId());
            return;
        }
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
                req.relatedId()
            );
            router.send(ch, resolved, req.templateCode(), req.variables(), dispatchId);
        }
    }

    public void notify(UUID schoolId, String recipientType, UUID recipientId,
                       String templateCode, Map<String, Object> vars) {
        notify(new NotifyRequest(
            schoolId, recipientType, recipientId, templateCode, "en", vars,
            List.of("whatsapp", "push", "email"), null, null
        ));
    }
}
