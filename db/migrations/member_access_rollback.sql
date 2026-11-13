-- ============================================================================
-- Rollback Migration: Granular Member Access Control (Phase 1)
-- Description:
--   - Drops member_device_access and member_room_access tables
--   - Drops access_payload and access_scope columns from home_invitations
--   - Drops access_scope column from home_members
-- Safety:
--   - Idempotent (IF EXISTS)
--   - Transaction wrapped
-- ============================================================================

BEGIN;

-- 1. Drop access tables
DROP TABLE IF EXISTS member_device_access CASCADE;
DROP TABLE IF EXISTS member_room_access CASCADE;

-- 2. Drop columns from home_invitations
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'home_invitations' AND column_name = 'access_payload'
    ) THEN
        ALTER TABLE home_invitations DROP COLUMN access_payload;
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'home_invitations' AND column_name = 'access_scope'
    ) THEN
        ALTER TABLE home_invitations DROP COLUMN access_scope;
    END IF;
END $$;

-- 3. Drop column from home_members
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'home_members' AND column_name = 'access_scope'
    ) THEN
        ALTER TABLE home_members DROP COLUMN access_scope;
    END IF;
END $$;

COMMIT;

