-- ============================================================================
-- Refresh tokens that may no longer be exchanged.
--
-- A refresh token was a 30-day bearer credential that nothing could take back:
-- signing out cleared the browser's copy and left every other copy working,
-- and the same token could be exchanged as often as anyone liked. Each refresh
-- token now carries an id (jti). Exchanging one spends it and issues the next;
-- signing out spends the current one. A spent id presented again is refused.
--
-- Platform-wide rather than per chain: the refresh endpoint knows the token
-- before it knows anything else, and jtis are random UUIDs, so one table serves
-- every chain and the platform console alike. Rows are only needed until the
-- token would have expired anyway, and are swept after that.
-- ============================================================================

CREATE TABLE IF NOT EXISTS platform.spent_refresh_token (
    jti         TEXT PRIMARY KEY,
    spent_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL,
    reason      TEXT NOT NULL CHECK (reason IN ('rotated', 'signed_out'))
);

CREATE INDEX IF NOT EXISTS spent_refresh_token_expires_idx ON platform.spent_refresh_token (expires_at);
