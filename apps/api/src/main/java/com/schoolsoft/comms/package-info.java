/**
 * Announcements &amp; 1:1 Messaging (per design doc §7 Layer 2). Announcements
 * fan out to push/email/WhatsApp per {@code channels} and are scoped to
 * school/grade/section/custom; read receipts are per user account. Messaging
 * is teacher&harr;parent 1:1, always inside a {@code message_thread} so the
 * conversation is audited (per §16 — every teacher-parent message is a
 * durable, queryable record, not ephemeral chat).
 */
package com.schoolsoft.comms;
