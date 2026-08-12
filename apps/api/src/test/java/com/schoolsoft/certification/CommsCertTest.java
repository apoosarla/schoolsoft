package com.schoolsoft.certification;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolsoft.certification.support.AbstractCertificationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** CERT-COMM — communications. */
class CommsCertTest extends AbstractCertificationTest {

    @Test @Tag("P1")
    void cert_COMM_01_targetedAnnouncementReachesExactlyItsAudienceWithReadReceipts() {
        String token = principalToken(cbse());
        UUID sectionId = currentFocusSection(cbse());

        var created = post("/v1/comms/announcements", body(
            "schoolId", cbse().id(), "scopeType", "section", "scopeIds", List.of(sectionId),
            "title", "Section outing", "body", "Bring a packed lunch.",
            "channels", List.of("push"), "createdByUserId", cbse().principalUserId()), token);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID announcementId = UUID.fromString(created.getBody().get("id").asText());

        var published = post("/v1/comms/announcements/" + announcementId + "/publish", null, token);
        assertThat(published.getBody().get("publishedAt").asText()).isNotBlank();

        assertThat(queryOne("SELECT scope_type FROM announcement WHERE id = ?", String.class, announcementId))
            .isEqualTo("section");
        assertThat(queryOne("SELECT scope_ids[1]::text FROM announcement WHERE id = ?", String.class, announcementId))
            .isEqualTo(sectionId.toString());

        UUID studentId = firstStudentIn(sectionId);
        UUID guardianUserId = queryOne(
            "SELECT ua.id FROM user_account ua JOIN guardian_student gs ON gs.guardian_id = ua.subject_id "
            + "WHERE ua.subject_type = 'guardian' AND gs.student_id = ? ORDER BY gs.is_primary DESC LIMIT 1",
            UUID.class, studentId);
        var read = post("/v1/comms/announcements/" + announcementId + "/read?userAccountId=" + guardianUserId,
            null, guardianTokenFor(cbse(), studentId));
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(count("SELECT count(*) FROM announcement_read WHERE announcement_id = ?", announcementId))
            .isEqualTo(1);

        // Another school in the chain never sees it.
        var otherSchoolView = get("/v1/comms/announcements?schoolId=" + cie().id(), principalToken(cie())).getBody();
        otherSchoolView.forEach(node -> assertThat(node.get("id").asText()).isNotEqualTo(announcementId.toString()));
    }

    @Test @Tag("P1")
    void cert_COMM_02_teacherParentThreadCarriesMessagesBothWays() {
        UUID sectionId = currentFocusSection(cbse());
        UUID studentId = firstStudentIn(sectionId);
        UUID teacherUserId = cbse().teacherUserIds().get(0);
        UUID guardianUserId = queryOne(
            "SELECT ua.id FROM user_account ua JOIN guardian_student gs ON gs.guardian_id = ua.subject_id "
            + "WHERE ua.subject_type = 'guardian' AND gs.student_id = ? ORDER BY gs.is_primary DESC LIMIT 1",
            UUID.class, studentId);

        var thread = post("/v1/comms/threads", Map.of("schoolId", cbse().id(),
            "subjectStudentId", studentId, "participants", List.of(teacherUserId, guardianUserId)),
            teacherToken(cbse(), 0));
        assertThat(thread.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID threadId = UUID.fromString(thread.getBody().get("id").asText());

        post("/v1/comms/threads/" + threadId + "/messages",
            Map.of("senderUserId", teacherUserId, "body", "Could we discuss last week's test?"),
            teacherToken(cbse(), 0));
        post("/v1/comms/threads/" + threadId + "/messages",
            Map.of("senderUserId", guardianUserId, "body", "Yes — Friday works."),
            guardianTokenFor(cbse(), studentId));

        var messages = get("/v1/comms/threads/" + threadId + "/messages", teacherToken(cbse(), 0)).getBody();
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).get("body").asText()).contains("last week's test");

        var teacherThreads = get("/v1/comms/threads?userAccountId=" + teacherUserId, teacherToken(cbse(), 0)).getBody();
        var guardianThreads = get("/v1/comms/threads?userAccountId=" + guardianUserId,
            guardianTokenFor(cbse(), studentId)).getBody();
        assertThat(teacherThreads).isNotEmpty();
        assertThat(guardianThreads).isNotEmpty();
    }

    @Test @Tag("P1")
    void cert_COMM_03_pushTokenRegistrationAndInvalidationControlDelivery() {
        UUID sectionId = currentFocusSection(cbse());
        UUID studentId = firstStudentIn(sectionId);
        String guardianToken = guardianTokenFor(cbse(), studentId);

        var registered = post("/v1/notifications/devices", Map.of(
            "platform", "android", "token", "cert-fcm-token-" + UUID.randomUUID()), guardianToken);
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID deviceId = UUID.fromString(registered.getBody().get("id").asText());
        assertThat(count("SELECT count(*) FROM notification_device WHERE id = ?", deviceId)).isEqualTo(1);

        var revoked = delete("/v1/notifications/devices/" + deviceId, guardianToken);
        assertThat(revoked.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        // The registry drops the row outright, so a stale token can no longer be selected for delivery.
        assertThat(count("SELECT count(*) FROM notification_device WHERE id = ?", deviceId)).isZero();
    }

    @Test @Tag("P2")
    @Disabled("GAP-21 — notification_dispatch records a status but nothing retries a failure and there is "
        + "no dispatch-log surface (Phase 8).")
    void cert_COMM_04_notificationFailureIsRetriedAndVisible() {
    }

    @Test @Tag("P2")
    @Disabled("GAP-21 — guardian opt-in flags exist per channel, but there are no quiet hours, no category "
        + "mute and no emergency override (Phase 8).")
    void cert_COMM_05_channelPreferencesAndQuietHoursAreRespected() {
    }

    @Test @Tag("P1")
    @Disabled("An emergency announcement can be created and published, but no module fans it out to "
        + "channels: NotificationService has no callers, so there are no delivery stats to meet an SLA "
        + "against. New gap found in Phase 0.")
    void cert_COMM_06_emergencyBroadcastReachesAllGuardiansWithStats() {
    }

    @Test @Tag("P2")
    @Disabled("WhatsApp BSP adapter is stubbed pending credentials (already in the backlog).")
    void cert_COMM_07_whatsappTemplateMessageUsesTheApprovedTemplate() {
    }

    @Test @Tag("P1")
    @Disabled("GAP-03 — no withdrawal workflow and no single enrolment-active-on-date predicate, so a "
        + "withdrawn student's parent keeps receiving section communications (Phase 7).")
    void cert_COMM_08_withdrawnStudentsParentStopsReceivingSectionComms() {
    }

    @Test @Tag("P2")
    @Disabled("GAP-20 — no PTM slot publication or parent booking (Phase 8).")
    void cert_COMM_09_ptmSlotsArePublishedBookedAndProtectedFromDoubleBooking() {
    }

    @Test @Tag("P1")
    void cert_COMM_10_threadsAreStrictlyTenantScoped() {
        UUID sectionId = currentFocusSection(cbse());
        UUID studentId = firstStudentIn(sectionId);
        UUID teacherUserId = cbse().teacherUserIds().get(0);
        UUID guardianUserId = queryOne(
            "SELECT ua.id FROM user_account ua JOIN guardian_student gs ON gs.guardian_id = ua.subject_id "
            + "WHERE ua.subject_type = 'guardian' AND gs.student_id = ? ORDER BY gs.is_primary DESC LIMIT 1",
            UUID.class, studentId);

        var thread = post("/v1/comms/threads", Map.of("schoolId", cbse().id(),
            "subjectStudentId", studentId, "participants", List.of(teacherUserId, guardianUserId)),
            teacherToken(cbse(), 0));
        UUID threadId = UUID.fromString(thread.getBody().get("id").asText());

        // A principal of the other school in the same chain cannot read the thread or its messages.
        var crossSchoolMessages = get("/v1/comms/threads/" + threadId + "/messages", principalToken(cie()));
        assertThat(crossSchoolMessages.getBody()).isEmpty();
        var crossSchoolThreads = get("/v1/comms/threads?userAccountId=" + teacherUserId, principalToken(cie()));
        assertThat(crossSchoolThreads.getBody()).isEmpty();
    }
}
