-- Bootstrap platform admin. Without at least one row here, the OTP login
-- flow added alongside this migration has no account to resolve against —
-- there was previously no way to create the first one short of a manual
-- INSERT. Dev/staging convenience only; production provisioning should
-- manage this table directly.
INSERT INTO platform.platform_user (email, name, role, is_active)
VALUES ('admin@schoolsoft.dev', 'Platform Admin', 'platform_admin', TRUE)
ON CONFLICT (email) DO NOTHING;
