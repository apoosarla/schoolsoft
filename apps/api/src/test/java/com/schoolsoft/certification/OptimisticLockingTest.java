package com.schoolsoft.certification;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolsoft.certification.support.AbstractCertificationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * That a second editor cannot silently overwrite the first.
 *
 * <p>Two records in this schema hold work somebody composed and are written
 * back whole: a role's screen list, and a fee structure's lines. Both were
 * blind overwrites — the later save won, the earlier one vanished, and nobody
 * was told. Both now carry a {@code version} the client sends back.</p>
 *
 * <p>The other contended writes deliberately do not: invoice arithmetic is
 * relative and already atomic, and status columns are state machines guarded by
 * conditional transitions instead. See {@code V028__optimistic_locking.sql}.</p>
 */
@Tag("harness")
class OptimisticLockingTest extends AbstractCertificationTest {

    // ===================== role =====================

    /**
     * The scenario, in order: two administrators open the same role, the first
     * saves, the second saves against what they loaded. The second is refused.
     */
    @Test
    @DisplayName("a role edit against a stale version is refused, and the first edit survives")
    void staleRoleEditIsRefused() {
        UUID roleId = createRole("cert-lock-a");
        try {
            long loadedByBoth = version(roleId);

            var first = put("/v1/iam/roles/" + roleId, body(
                "name", "Renamed by the first editor",
                "screenKeys", List.of("dashboard", "students"),
                "expectedVersion", loadedByBoth), staff());
            assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

            var second = put("/v1/iam/roles/" + roleId, body(
                "name", "Renamed by the second editor",
                "screenKeys", List.of("fees"),
                "expectedVersion", loadedByBoth), staff());

            assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(second.getBody().get("message").asText()).contains("changed by somebody else");

            // The first editor's work is intact — that is the point.
            assertThat(roleName(roleId)).isEqualTo("Renamed by the first editor");
        } finally {
            deleteRole(roleId);
        }
    }

    @Test
    @DisplayName("a role edit against the current version applies and moves the version on")
    void currentRoleEditApplies() {
        UUID roleId = createRole("cert-lock-b");
        try {
            long before = version(roleId);

            var saved = put("/v1/iam/roles/" + roleId, body(
                "name", "Edited once",
                "screenKeys", List.of("dashboard"),
                "expectedVersion", before), staff());

            assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(saved.getBody().get("version").asLong()).isEqualTo(before + 1);
            assertThat(version(roleId)).isEqualTo(before + 1);

            // And the version the save returned is usable for the next edit,
            // so an editor who stays on the page does not have to reload.
            var again = put("/v1/iam/roles/" + roleId, body(
                "name", "Edited twice",
                "screenKeys", List.of("dashboard"),
                "expectedVersion", before + 1), staff());
            assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        } finally {
            deleteRole(roleId);
        }
    }

    /**
     * The version is required, not optional. A client permitted to omit it is
     * a client that silently overwrites, which is the defect this closes — so
     * omitting it is a bad request, not a free pass.
     */
    @Test
    @DisplayName("a role edit with no version at all is rejected")
    void roleEditWithoutAVersionIsRejected() {
        UUID roleId = createRole("cert-lock-c");
        try {
            var sent = put("/v1/iam/roles/" + roleId, body(
                "name", "No version supplied",
                "screenKeys", List.of("dashboard")), staff());

            assertThat(sent.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(roleName(roleId)).isNotEqualTo("No version supplied");
        } finally {
            deleteRole(roleId);
        }
    }

    // ===================== fee structure =====================

    /**
     * Replacing the lines deletes every one and re-inserts, so an overlapping
     * save does not merge or partially lose — it drops a whole fee schedule.
     */
    @Test
    @DisplayName("a fee structure line edit against a stale version is refused")
    void staleFeeStructureEditIsRefused() {
        UUID headId = queryOne("SELECT id FROM fee_head WHERE school_id = ? LIMIT 1",
            UUID.class, cbse().id());
        UUID structureId = UUID.fromString(post("/v1/fees/structures", body(
            "schoolId", cbse().id(),
            "gradeId", gradeOf(cbse(), cbse().focusGradeCode()),
            "academicYearId", cbse().currentAy().id(),
            "name", "Cert optimistic locking",
            "lines", List.of(body("feeHeadId", headId, "amount", 1000.0))), staff())
            .getBody().get("id").asText());

        try {
            long loadedByBoth = queryOne("SELECT version FROM fee_structure WHERE id = ?",
                Long.class, structureId);

            var first = put("/v1/fees/structures/" + structureId + "/lines", body(
                "lines", List.of(body("feeHeadId", headId, "amount", 2500.0)),
                "expectedVersion", loadedByBoth), staff());
            assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

            var second = put("/v1/fees/structures/" + structureId + "/lines", body(
                "lines", List.of(body("feeHeadId", headId, "amount", 9999.0)),
                "expectedVersion", loadedByBoth), staff());
            assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

            // The first editor's amount stands, and there is exactly one line —
            // the refused save neither applied nor half-applied.
            assertThat(count("SELECT count(*) FROM fee_structure_line WHERE fee_structure_id = ?",
                structureId)).isEqualTo(1);
            assertThat(queryOne("SELECT amount FROM fee_structure_line WHERE fee_structure_id = ?",
                Double.class, structureId)).isEqualTo(2500.0);
        } finally {
            inChainDo(jdbc -> {
                jdbc.update("DELETE FROM fee_structure_line WHERE fee_structure_id = ?", structureId);
                jdbc.update("DELETE FROM fee_structure WHERE id = ?", structureId);
            });
        }
    }

    // ===================== helpers =====================

    private String staff() { return principalToken(cbse()); }

    private long version(UUID roleId) {
        return queryOne("SELECT version FROM role WHERE id = ?", Long.class, roleId);
    }

    private String roleName(UUID roleId) {
        return queryOne("SELECT name FROM role WHERE id = ?", String.class, roleId);
    }

    private UUID createRole(String code) {
        return UUID.fromString(post("/v1/iam/roles", body(
            "code", code,
            "name", "Certification lock role",
            "screenKeys", List.of("dashboard")), staff()).getBody().get("id").asText());
    }

    private void deleteRole(UUID roleId) {
        inChainDo(jdbc -> {
            jdbc.update("DELETE FROM role_perm WHERE role_code = (SELECT code FROM role WHERE id = ?)", roleId);
            jdbc.update("DELETE FROM role WHERE id = ?", roleId);
        });
    }
}
