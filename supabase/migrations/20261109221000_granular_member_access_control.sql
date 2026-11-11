-- ============================================================================
-- Migration: Granular Member Access Control (Phase 1 & Phase 2)
-- Description:
--   - Adds access_scope to home_members ('ALL' | 'CUSTOM')
--   - Adds access_scope and access_payload to home_invitations
--   - Creates member_room_access table (member_id, room_id, access_level)
--   - Creates member_device_access table (member_id, device_id, access_level)
--   - Enables Row Level Security (RLS) on new tables (fail-closed for anon/auth)
-- Safety:
--   - Additive & Idempotent (IF NOT EXISTS / DO blocks)
--   - Backward compatible: existing records default to 'ALL'
--   - Transaction wrapped
-- ============================================================================

BEGIN;

-- 1. Ensure pgcrypto / gen_random_uuid() extension is available
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- 2. Add access_scope to home_members
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'home_members' AND column_name = 'access_scope'
    ) THEN
        ALTER TABLE home_members 
        ADD COLUMN access_scope VARCHAR(20) NOT NULL DEFAULT 'ALL';

        COMMENT ON COLUMN home_members.access_scope IS 'Access scope for member: ALL (full home access) or CUSTOM (granular per room/device)';
    END IF;
END $$;

-- 3. Add access_scope and access_payload to home_invitations
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'home_invitations' AND column_name = 'access_scope'
    ) THEN
        ALTER TABLE home_invitations 
        ADD COLUMN access_scope VARCHAR(20) NOT NULL DEFAULT 'ALL';

        COMMENT ON COLUMN home_invitations.access_scope IS 'Preset access scope for invitation: ALL or CUSTOM';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'home_invitations' AND column_name = 'access_payload'
    ) THEN
        ALTER TABLE home_invitations 
        ADD COLUMN access_payload TEXT;

        COMMENT ON COLUMN home_invitations.access_payload IS 'Optional preset room/device permissions JSON when access_scope is CUSTOM';
    END IF;
END $$;

-- 4. Create table member_room_access
CREATE TABLE IF NOT EXISTS member_room_access (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    member_id UUID NOT NULL REFERENCES home_members(id) ON DELETE CASCADE,
    room_id UUID NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
    access_level VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_member_room UNIQUE (member_id, room_id),
    CONSTRAINT chk_member_room_access_level CHECK (access_level IN ('NONE', 'VIEW', 'CONTROL'))
);

COMMENT ON TABLE member_room_access IS 'Room-level access permission for CUSTOM members';
COMMENT ON COLUMN member_room_access.access_level IS 'Access level: NONE, VIEW, or CONTROL';

CREATE INDEX IF NOT EXISTS idx_member_room_access_room_id ON member_room_access(room_id);

-- 5. Create table member_device_access
CREATE TABLE IF NOT EXISTS member_device_access (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    member_id UUID NOT NULL REFERENCES home_members(id) ON DELETE CASCADE,
    device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    access_level VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_member_device UNIQUE (member_id, device_id),
    CONSTRAINT chk_member_device_access_level CHECK (access_level IN ('NONE', 'VIEW', 'CONTROL'))
);

COMMENT ON TABLE member_device_access IS 'Device-level access override for CUSTOM members';
COMMENT ON COLUMN member_device_access.access_level IS 'Override access level: NONE, VIEW, or CONTROL';

CREATE INDEX IF NOT EXISTS idx_member_device_access_device_id ON member_device_access(device_id);

-- 6. Enable Row Level Security (RLS) on new access tables
ALTER TABLE member_room_access ENABLE ROW LEVEL SECURITY;
ALTER TABLE member_device_access ENABLE ROW LEVEL SECURITY;

COMMIT;

