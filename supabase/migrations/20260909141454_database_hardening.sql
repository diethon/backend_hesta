-- =====================================================================
-- HESTA SMART HOME SYSTEM — V2 schema hardening
-- Purely additive: CHECK constraints, guard rules, and missing indexes
-- on top of V1__init_database.sql. Does not alter existing data and is
-- safe to run even if V1 has already been applied to a live database.
-- =====================================================================

-- 1. USERS — constrain enum-like columns, tie provider to the right credential
ALTER TABLE users
    ADD CONSTRAINT chk_users_provider CHECK (provider IN ('LOCAL','GOOGLE')),
    ADD CONSTRAINT chk_users_platform_role CHECK (platform_role IN ('USER','ADMIN')),
    ADD CONSTRAINT chk_users_status CHECK (status IN ('ACTIVE','DISABLED','LOCKED'));

-- BR-AUTH-02/BR-AUTH-05/06: a LOCAL account must have a password hash,
-- a GOOGLE account must have a google_uid (mirrors the Login with Google spec)
ALTER TABLE users
    ADD CONSTRAINT chk_users_provider_fields CHECK (
        (provider = 'LOCAL'  AND password_hash IS NOT NULL)
     OR (provider = 'GOOGLE' AND google_uid    IS NOT NULL)
    );

-- 2. HOME_MEMBERS — constrain role/status, and add the missing reverse index
--    (idx_home_members_home already exists in V1; queries by user_id — e.g.
--    "list all homes this user belongs to" — currently do a full scan)
ALTER TABLE home_members
    ADD CONSTRAINT chk_home_members_role   CHECK (role   IN ('OWNER','MEMBER')),
    ADD CONSTRAINT chk_home_members_status CHECK (status IN ('ACTIVE','DISABLED'));

CREATE INDEX IF NOT EXISTS idx_home_members_user ON home_members(user_id);

-- 3. ROOMS — prevent two rooms with the same name inside one home
ALTER TABLE rooms
    ADD CONSTRAINT uq_rooms_home_name UNIQUE (home_id, name);

-- 4. NOTIFICATIONS — a notification should point to at most one source event
--    (today nothing stops all three source_* columns from being filled at once)
ALTER TABLE notifications
    ADD CONSTRAINT chk_notifications_single_source CHECK (
        (CASE WHEN source_security_event_id       IS NOT NULL THEN 1 ELSE 0 END
       + CASE WHEN source_anomaly_id              IS NOT NULL THEN 1 ELSE 0 END
       + CASE WHEN source_automation_execution_id IS NOT NULL THEN 1 ELSE 0 END) <= 1
    );

-- 5. PASSWORD_RESET_OTPS — fast lookup of the still-active OTP for a user
--    (used by the Verify OTP step of Forgot Password; BR-AUTH-07)
CREATE INDEX IF NOT EXISTS idx_otp_user_active
    ON password_reset_otps(user_id) WHERE is_used = false;

-- 6. REFRESH_TOKENS — fast lookup for the scheduled cleanup job that purges
--    expired/revoked sessions (BR-AUTH-08/09); V1 only indexes user_id
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_expiry
    ON refresh_tokens(expires_at) WHERE is_revoked = false;

-- =====================================================================
-- END OF V2
-- =====================================================================