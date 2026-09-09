package com.schoolsoft.notification.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A message with no recipient yet — what a producing module knows before the
 * notification module works out who should receive it.
 *
 * <p>Producers describe the event ("this child was marked absent on this
 * date"); {@link NotificationService} turns that into one
 * {@link NotifyRequest} per recipient per channel. Fan-out to a student's
 * guardians is the common case and the reason this type exists: the caller
 * should not be repeating the {@code guardian_student} join, the
 * communications-recipient flag, or the child's name lookup.</p>
 *
 * <p>{@link #dedupeKey()} names the <em>event</em>, not the send. Two notices
 * carrying the same key reach a given recipient on a given channel exactly
 * once, however many times the producing path runs — a register re-saved, an
 * announcement re-published, a check-in tapped twice.</p>
 */
public record Notice(
    UUID schoolId,
    String templateCode,
    String language,
    Map<String, Object> variables,
    List<String> channels,
    String relatedType,
    UUID relatedId,
    String dedupeKey
) {
    /** Every channel the recipient has opted in to, in the house preference order. */
    public static final List<String> ALL_CHANNELS = List.of("whatsapp", "push", "email", "sms");

    public static Notice of(UUID schoolId, String templateCode, Map<String, Object> variables) {
        return new Notice(schoolId, templateCode, "en", variables, ALL_CHANNELS, null, null, null);
    }

    /** What this notice is about, so delivery can be reported per announcement, per invoice, per trip. */
    public Notice about(String relatedType, UUID relatedId) {
        return new Notice(schoolId, templateCode, language, variables, channels, relatedType, relatedId, dedupeKey);
    }

    /** Names the event so a repeat of the producing path sends nothing. */
    public Notice oncePer(String dedupeKey) {
        return new Notice(schoolId, templateCode, language, variables, channels, relatedType, relatedId, dedupeKey);
    }

    public Notice via(List<String> channels) {
        return new Notice(schoolId, templateCode, language, variables,
            channels == null || channels.isEmpty() ? ALL_CHANNELS : channels,
            relatedType, relatedId, dedupeKey);
    }

    public Notice withVariables(Map<String, Object> variables) {
        return new Notice(schoolId, templateCode, language, variables, channels, relatedType, relatedId, dedupeKey);
    }

    public NotifyRequest to(String recipientType, UUID recipientId) {
        return new NotifyRequest(schoolId, recipientType, recipientId, templateCode, language,
            variables, channels, relatedType, relatedId, dedupeKey);
    }
}
