package com.schoolsoft.notification.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Channel routing + opt-in filtering. Per §10 — WhatsApp requires explicit
 * opt-in; email/push have looser defaults.
 *
 * Channel adapters are stubbed: a real WhatsApp client (Gupshup/AiSensy),
 * FCM, and SES go here in Phase 1. For MVP we log and persist the dispatch
 * record so end-to-end flows are testable without a BSP account.
 */
@Component
public class ChannelRouter {

    private static final Logger log = LoggerFactory.getLogger(ChannelRouter.class);
    private final NotificationRepository repo;

    public ChannelRouter(NotificationRepository repo) { this.repo = repo; }

    public List<String> filterByOptIn(List<String> requested, NotificationRepository.Recipient r) {
        List<String> out = new ArrayList<>();
        for (String ch : requested) {
            boolean ok = switch (ch) {
                case "whatsapp" -> r.optInWhatsapp() && r.phone() != null;
                case "push"     -> r.optInPush();
                case "email"    -> r.optInEmail() && r.email() != null;
                case "sms"      -> r.optInSms() && r.phone() != null;
                default -> false;
            };
            if (ok) out.add(ch);
        }
        return out;
    }

    public void send(String channel, NotificationRepository.Recipient r, String templateCode,
                     Map<String, Object> vars, UUID dispatchId) {
        // Stub: production replaces this with channel-specific adapter beans.
        String providerId = "dev-" + UUID.randomUUID();
        log.info("[NOTIF/{}] template={} to={}/{} vars={} → providerMsgId={}",
            channel, templateCode, r.type(), r.id(), vars, providerId);
        repo.markSent(dispatchId, providerId);
    }
}
