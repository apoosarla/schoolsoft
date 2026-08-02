package com.schoolsoft.notification.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cross-module API for emitting a notification. Callers identify the recipient
 * by type + id; the notification module resolves contact info + opt-ins +
 * template, then fans out to the requested channels.
 *
 * Channels are advisory — if the recipient hasn't opted-in to a channel it
 * is skipped silently (logged in audit). 'whatsapp' additionally requires
 * an approved template inside the 24-hr window (per §10).
 */
public record NotifyRequest(
    UUID schoolId,
    String recipientType,           // 'guardian' | 'staff' | 'student'
    UUID recipientId,
    String templateCode,            // matches platform.whatsapp_template.code OR an email template code
    String language,                // 'en' | 'hi' | ...
    Map<String, Object> variables,
    List<String> channels,          // ordered preference, e.g. ['whatsapp','push','email']
    String relatedType,
    UUID relatedId
) {}
