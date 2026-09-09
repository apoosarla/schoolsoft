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
    void cert_COMM_06_emergencyBroadcastReachesAllGuardiansWithStats() {
        String token = principalToken(cbse());

        var created = post("/v1/comms/announcements", body(
            "schoolId", cbse().id(), "scopeType", "school",
            "title", "School closed tomorrow",
            "body", "Cyclone warning: the school will remain closed on 12 August. Buses will not run.",
            "channels", List.of("email", "push"), "priority", "emergency",
            "createdByUserId", cbse().principalUserId()), token);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(created.getBody().get("priority").asText()).isEqualTo("emergency");
        UUID announcementId = UUID.fromString(created.getBody().get("id").asText());

        // Nothing goes out until it is published — a draft closure notice that
        // reached the school would be worse than one that never went.
        assertThat(count("SELECT count(*) FROM notification_dispatch WHERE related_type = 'announcement' "
            + "AND related_id = ?", announcementId)).isZero();

        // Every guardian of a currently enrolled child, reachable on one of the
        // channels the announcement names. Computed from the data rather than
        // hard-coded: the point is that nobody in scope is left out.
        // "Currently enrolled" is the active-on-date predicate, the same one the
        // fan-out uses — a child whose exit is filed for the end of the month is
        // still at the school today, and still gets told the school is closed
        // tomorrow (COMM-08 is the other half of this).
        long reachable = count(
            "SELECT count(DISTINCT gs.guardian_id) FROM guardian_student gs "
            + "JOIN enrolment e ON e.student_id = gs.student_id "
            + "  AND e.starts_on <= CURRENT_DATE "
            + "  AND (e.ends_on IS NULL OR e.ends_on >= CURRENT_DATE) "
            + "JOIN guardian g ON g.id = gs.guardian_id "
            + "WHERE e.school_id = ? AND gs.is_communications_recipient "
            + "  AND g.opt_in_email AND g.email IS NOT NULL", cbse().id());
        assertThat(reachable).isGreaterThan(50);      // a school-wide fan-out, not a section's

        java.time.Instant beforePublish = java.time.Instant.now();
        var published = post("/v1/comms/announcements/" + announcementId + "/publish", null, token);
        assertThat(published.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(published.getBody().get("publishedAt").asText()).isNotBlank();

        var stats = get("/v1/comms/announcements/" + announcementId + "/delivery", token);
        assertThat(stats.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stats.getBody().get("recipients").asInt()).isEqualTo((int) reachable);
        assertThat(stats.getBody().get("byChannel").get("email").asInt()).isEqualTo((int) reachable);
        assertThat(stats.getBody().get("sent").asInt()).isEqualTo((int) reachable);
        assertThat(stats.getBody().get("failed").asInt()).isZero();
        assertThat(stats.getBody().get("pending").asInt()).isZero();

        // The SLA is read off the fan-out itself, not inferred from the publish
        // timestamp: "published" and "delivered" are the two things this
        // scenario exists to keep apart.
        assertThat(stats.getBody().get("elapsedMs").asLong()).isLessThan(60_000);
        assertThat(java.time.Instant.parse(stats.getBody().get("lastSentAt").asText()))
            .isAfterOrEqualTo(beforePublish.minusSeconds(1));

        // An emergency is its own template — on WhatsApp it has to be, and a
        // closure notice should not read like a newsletter anywhere else.
        assertThat(queryOne("SELECT DISTINCT template_code FROM notification_dispatch "
            + "WHERE related_id = ?", String.class, announcementId)).isEqualTo("emergency_broadcast");

        // Publishing again is a retry, not a second siren.
        int dispatches = stats.getBody().get("dispatches").asInt();
        var republished = post("/v1/comms/announcements/" + announcementId + "/publish", null, token);
        assertThat(republished.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(republished.getBody().get("publishedAt").asText())
            .isEqualTo(published.getBody().get("publishedAt").asText());
        assertThat(get("/v1/comms/announcements/" + announcementId + "/delivery", token)
            .getBody().get("dispatches").asInt()).isEqualTo(dispatches);

        // And it reached this school only.
        assertThat(count("SELECT count(*) FROM notification_dispatch WHERE related_id = ? "
            + "AND school_id <> ?", announcementId, cbse().id())).isZero();
    }

    @Test @Tag("P2")
    @Disabled("WhatsApp BSP adapter is stubbed pending credentials (already in the backlog).")
    void cert_COMM_07_whatsappTemplateMessageUsesTheApprovedTemplate() {
    }

    @Test @Tag("P1")
    void cert_COMM_08_withdrawnStudentsParentStopsReceivingSectionComms() {
        String token = principalToken(cbse());
        String suffix = UUID.randomUUID().toString().substring(0, 6);

        // A child and a parent of this scenario's own, addressed directly, so the
        // assertion is about one family rather than about a school-wide count
        // another scenario is also moving.
        // Its own admission number: the fixture smoke test counts the seeded
        // cohort by the `ADM%` prefix the number series issues by default.
        UUID studentId = UUID.fromString(post("/v1/people/students", body(
            "schoolId", cbse().id(), "admissionNo", "COMM08-" + suffix,
            "firstName", "COMM08", "lastName", "Leaver-" + suffix,
            "dob", "2015-02-02", "gender", "male"), token).getBody().get("id").asText());
        UUID enrolmentId = UUID.fromString(post("/v1/enrolment", body(
            "schoolId", cbse().id(), "studentId", studentId, "sectionId", currentFocusSection(cbse()),
            "academicYearId", cbse().currentAy().id(), "startsOn", "2026-04-01",
            "overCapacityReason", "Certification scenario COMM-08"), token).getBody().get("id").asText());
        inChainDo(jdbc -> {
            UUID guardianId = UUID.randomUUID();
            jdbc.update("INSERT INTO guardian (id, school_id, first_name, last_name, email) " +
                "VALUES (?, ?, 'COMM08', 'Parent', ?)",
                guardianId, cbse().id(), "comm08-" + suffix + "@cert.test");
            jdbc.update("INSERT INTO guardian_student (guardian_id, student_id, relation, is_primary) " +
                "VALUES (?, ?, 'mother', TRUE)", guardianId, studentId);
        });

        // While they are here, a circular reaches them.
        assertThat(recipientsOf(circularTo(studentId, "Sports day", token))).isEqualTo(1);

        // The exit is filed today for a last working day a fortnight out, and
        // until that day nothing changes: a family does not stop being told about
        // sports day because the paperwork has started.
        java.time.LocalDate lastDay = java.time.LocalDate.now().plusDays(14);
        UUID withdrawalId = UUID.fromString(post("/v1/enrolment/withdrawals", body(
            "enrolmentId", enrolmentId, "reasonCode", "relocation",
            "reason", "Moving to Chennai", "lastWorkingDate", lastDay.toString()), token)
            .getBody().get("id").asText());
        for (String area : List.of("fees", "library", "transport", "assets")) {
            post("/v1/enrolment/withdrawals/" + withdrawalId + "/clearance/" + area,
                body("reason", "Checked"), token);
        }
        post("/v1/enrolment/withdrawals/" + withdrawalId + "/complete", body("reason", "Cleared"), token);
        assertThat(recipientsOf(circularTo(studentId, "Uniform change", token))).isEqualTo(1);

        // Once the last working day has passed, they are off the list — and the
        // cutover is the date, so it needs nothing to have been run.
        inChainDo(jdbc -> jdbc.update(
            "UPDATE enrolment SET ends_on = CURRENT_DATE - 1 WHERE id = ?", enrolmentId));
        assertThat(recipientsOf(circularTo(studentId, "Term 2 dates", token))).isZero();

        // Their history is untouched: the circulars they were sent while enrolled
        // still name them.
        assertThat(count("SELECT count(*) FROM notification_dispatch d " +
            "JOIN guardian_student gs ON gs.guardian_id = d.recipient_id " +
            "WHERE gs.student_id = ? AND d.related_type = 'announcement'", studentId)).isEqualTo(2);
    }

    /** Publishes a circular addressed to one child, and returns its id. */
    private UUID circularTo(UUID studentId, String title, String token) {
        var created = post("/v1/comms/announcements", body(
            "schoolId", cbse().id(), "scopeType", "custom", "scopeIds", List.of(studentId),
            "title", title, "body", title + " — details attached.",
            "channels", List.of("email"), "createdByUserId", cbse().principalUserId()), token);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID id = UUID.fromString(created.getBody().get("id").asText());
        assertThat(post("/v1/comms/announcements/" + id + "/publish", null, token).getStatusCode())
            .isEqualTo(HttpStatus.OK);
        return id;
    }

    private long recipientsOf(UUID announcementId) {
        return count("SELECT count(*) FROM notification_dispatch WHERE related_type = 'announcement' " +
            "AND related_id = ?", announcementId);
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
