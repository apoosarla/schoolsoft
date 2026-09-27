package com.schoolsoft.iam.internal;

import java.sql.Timestamp;
import java.time.Instant;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Which refresh tokens have been spent (V006 of the platform migrations).
 *
 * <p>A refresh is a rotation: the presented token is spent and a new one is
 * issued. Presenting a spent token again is refused — the second holder of a
 * copied token gets nothing once the first has used it, and a signed-out
 * token is dead everywhere. Two tabs refreshing the same token at the same
 * moment are the one honest reuse, so a token spent by rotation in the last
 * {@link #GRACE_SECONDS} seconds is still honoured.</p>
 */
@Service
public class RefreshTokenLedger {

    static final int GRACE_SECONDS = 30;

    private final JdbcTemplate jdbc;

    public RefreshTokenLedger(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /**
     * Spends {@code jti} by rotation. Returns false when it was already spent
     * — signed out, or rotated longer ago than the grace window.
     */
    public boolean spendForRotation(String jti, Instant expiresAt) {
        if (jti == null) return true;           // minted before tokens carried an id; spent by expiry only
        int inserted = jdbc.update(
            "INSERT INTO platform.spent_refresh_token (jti, expires_at, reason) VALUES (?, ?, 'rotated') " +
            "ON CONFLICT (jti) DO NOTHING",
            jti, Timestamp.from(expiresAt));
        if (inserted == 1) {
            sweep();
            return true;
        }
        Integer recent = jdbc.queryForObject(
            "SELECT count(*) FROM platform.spent_refresh_token WHERE jti = ? AND reason = 'rotated' " +
            "AND spent_at > now() - make_interval(secs => ?)",
            Integer.class, jti, GRACE_SECONDS);
        return recent != null && recent > 0;
    }

    /** Spends {@code jti} for good. Signing out twice is not an error. */
    public void signOut(String jti, Instant expiresAt) {
        if (jti == null) return;
        jdbc.update(
            "INSERT INTO platform.spent_refresh_token (jti, expires_at, reason) VALUES (?, ?, 'signed_out') " +
            "ON CONFLICT (jti) DO UPDATE SET reason = 'signed_out', spent_at = now()",
            jti, Timestamp.from(expiresAt));
    }

    private void sweep() {
        // Cheap and occasional: a row is useless once its token has expired.
        if (Math.random() < 0.01) {
            jdbc.update("DELETE FROM platform.spent_refresh_token WHERE expires_at < now()");
        }
    }
}
