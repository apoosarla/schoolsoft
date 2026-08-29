package com.schoolsoft.certification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.schoolsoft.audit.api.AuditChainVerifier;
import com.schoolsoft.certification.support.AbstractCertificationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * That the audit log's hash chain does the one thing it exists for.
 *
 * <p>A chain nobody has watched break is a chain nobody knows works. Each test
 * here tampers the way a real attacker with database access would — the
 * threat model is somebody who reached the database, because somebody who did
 * not cannot alter the log at all — and asserts the verifier says so.</p>
 *
 * <p>Runs in {@code harness}, with the structural rules, so the blocking gate
 * covers it.</p>
 */
@Tag("harness")
class AuditChainTest extends AbstractCertificationTest {

    /**
     * Each test starts and ends with an empty log.
     *
     * <p>Two of these deliberately break the chain, and a broken chain stays
     * broken — left alone it would fail every later test in this class and any
     * audit assertion in a class that runs after it, all pointing at the wrong
     * cause. Truncating restarts the chain rather than forking it, which is why
     * it is the right reset and a partial delete is not.</p>
     */
    @BeforeEach
    @AfterEach
    void emptyTheLog() {
        inChainDo(jdbc -> jdbc.update("TRUNCATE TABLE audit_log RESTART IDENTITY"));
    }

    @Test
    @DisplayName("the chain holds over the log the suite has been writing")
    void chainIsIntact() {
        writeAuditedEvent();

        var result = verify();

        assertThat(result.intact())
            .describedAs("chain broken at row %s: %s", result.brokenAtId(), result.detail())
            .isTrue();
        assertThat(result.rowsChecked()).isPositive();
    }

    @Test
    @DisplayName("every row records the hash of the row before it")
    void everyRowLinksToItsPredecessor() {
        writeAuditedEvent();
        writeAuditedEvent();

        long unlinked = count(
            "SELECT count(*) FROM audit_log a "
          + "WHERE a.prev_hash IS DISTINCT FROM ("
          + "  SELECT b.entry_hash FROM audit_log b WHERE b.id < a.id ORDER BY b.id DESC LIMIT 1)");

        assertThat(unlinked).isZero();
    }

    /**
     * The log records what happened, and what happened does not change. An
     * update is refused outright rather than allowed to break the chain
     * quietly for somebody to find months later.
     */
    @Test
    @DisplayName("an update to a written row is refused")
    void updatesAreRefused() {
        writeAuditedEvent();

        assertThatThrownBy(() -> inChainDo(jdbc -> jdbc.update(
                "UPDATE audit_log SET reason = 'rewritten' WHERE id = (SELECT max(id) FROM audit_log)")))
            .hasStackTraceContaining("append-only");

        assertThat(verify().intact()).isTrue();
    }

    /**
     * Simulates the only attacker who can get this far: one with enough
     * database access to disable the trigger, edit a row, and put the trigger
     * back. The chain cannot stop them. It can make sure the next person to
     * look knows.
     */
    @Test
    @DisplayName("a field edited behind the trigger's back is detected")
    void tamperingWithARowIsDetected() {
        writeAuditedEvent();
        writeAuditedEvent();
        long victim = queryOne("SELECT min(id) FROM audit_log", Long.class);

        assertThat(verify().intact()).isTrue();

        inChainDo(jdbc -> {
            jdbc.execute("ALTER TABLE audit_log DISABLE TRIGGER audit_log_no_update_trg");
            jdbc.update("UPDATE audit_log SET reason = 'a story about what happened' WHERE id = ?", victim);
            jdbc.execute("ALTER TABLE audit_log ENABLE TRIGGER audit_log_no_update_trg");
        });

        var result = verify();
        assertThat(result.intact()).isFalse();
        assertThat(result.brokenAtId()).isEqualTo(victim);
        assertThat(result.detail()).contains("altered after it was written");
    }

    /**
     * Deletion stays legal — retention policy needs it — so the chain's job is
     * to make it visible rather than to stop it.
     */
    @Test
    @DisplayName("a row removed from the middle of the log is detected")
    void removingARowIsDetected() {
        writeAuditedEvent();
        writeAuditedEvent();
        writeAuditedEvent();
        long first = queryOne("SELECT min(id) FROM audit_log", Long.class);
        long second = queryOne("SELECT min(id) FROM audit_log WHERE id > ?", Long.class, first);

        inChainDo(jdbc -> jdbc.update("DELETE FROM audit_log WHERE id = ?", second));

        var result = verify();
        assertThat(result.intact()).isFalse();
        assertThat(result.detail()).contains("removed");
    }

    // ===== helpers =====

    /**
     * Writes through the real audited path — a role grant, which is the widest
     * change anybody makes and the reason SEC-08 exists — so the test verifies
     * rows the application wrote rather than rows it inserted itself.
     */
    private void writeAuditedEvent() {
        post("/v1/iam/staff-roles/assign", body(
            "staffId", cbse().librarianStaffId(),
            "schoolId", cbse().id(),
            "roleCode", "librarian",
            "scopeType", "school",
            "scopeId", cbse().id(),
            "reason", "audit chain test"), principalToken(cbse()));
    }

    private AuditChainVerifier.Result verify() {
        var response = get("/v1/audit/chain", principalToken(cbse()));
        var body = response.getBody();
        return new AuditChainVerifier.Result(
            body.get("rowsChecked").asLong(),
            body.get("intact").asBoolean(),
            body.hasNonNull("brokenAtId") ? body.get("brokenAtId").asLong() : null,
            body.hasNonNull("detail") ? body.get("detail").asText() : null);
    }
}
