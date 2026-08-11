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
 * Push routes through FCM when a service account is configured. WhatsApp
 * (Gupshup/AiSensy) and email (SES) are still stubbed: we log and persist the
 * dispatch record so end-to-end flows are testable without a BSP account.
 */
@Component
public class ChannelRouter {

    private static final Logger log = LoggerFactory.getLogger(ChannelRouter.class);
    private final NotificationRepository repo;
    private final NotificationDeviceRepository devices;
    private final FcmSender fcm;

    public ChannelRouter(NotificationRepository repo, NotificationDeviceRepository devices, FcmSender fcm) {
        this.repo = repo;
        this.devices = devices;
        this.fcm = fcm;
    }

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
        if ("push".equals(channel)) {
            sendPush(r, templateCode, vars, dispatchId);
            return;
        }
        // Stub: production replaces this with channel-specific adapter beans.
        String providerId = "dev-" + UUID.randomUUID();
        log.info("[NOTIF/{}] template={} to={}/{} vars={} → providerMsgId={}",
            channel, templateCode, r.type(), r.id(), vars, providerId);
        repo.markSent(dispatchId, providerId);
    }

    private void sendPush(NotificationRepository.Recipient r, String templateCode,
                          Map<String, Object> vars, UUID dispatchId) {
        List<NotificationDeviceRepository.DeviceToken> tokens = devices.tokensForRecipient(r.type(), r.id());
        if (tokens.isEmpty()) {
            log.info("[NOTIF/push] skipped: no registered devices for {}/{} template={}",
                r.type(), r.id(), templateCode);
            repo.markFailed(dispatchId, "no registered device tokens");
            return;
        }

        if (!fcm.isEnabled()) {
            String providerId = "dev-" + UUID.randomUUID();
            log.info("[NOTIF/push] FCM credentials not configured — stubbing send to {} device(s) {} " +
                     "template={} to={}/{} vars={} → providerMsgId={}",
                tokens.size(), describe(tokens), templateCode, r.type(), r.id(), vars, providerId);
            repo.markSent(dispatchId, providerId);
            return;
        }

        List<String> providerIds = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (var device : tokens) {
            try {
                providerIds.add(fcm.send(device.token(), templateCode, vars));
            } catch (Exception e) {
                failures.add(device.id() + ": " + e.getMessage());
                log.warn("[NOTIF/push] FCM send failed for device {} ({}) to {}/{}",
                    device.id(), device.platform(), r.type(), r.id(), e);
            }
        }

        if (providerIds.isEmpty()) {
            repo.markFailed(dispatchId, String.join("; ", failures));
            return;
        }
        log.info("[NOTIF/push] template={} to={}/{} delivered to {}/{} device(s) → providerMsgIds={}",
            templateCode, r.type(), r.id(), providerIds.size(), tokens.size(), providerIds);
        repo.markSent(dispatchId, String.join(",", providerIds));
    }

    /** Device identity without the registration token, which stays out of the logs. */
    private static List<String> describe(List<NotificationDeviceRepository.DeviceToken> tokens) {
        return tokens.stream().map(d -> d.id() + "/" + d.platform()).toList();
    }
}
