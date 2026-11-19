-- Migration: Device Onboarding Foundation
-- Description: Allow UNCLAIMED devices (nullable room_id), add model & serial_number, and create device_registration_tokens table.

-- 1. Allow devices to exist in UNCLAIMED status before being assigned to a room
ALTER TABLE devices ALTER COLUMN room_id DROP NOT NULL;

-- 2. Add hardware identification fields to devices
ALTER TABLE devices ADD COLUMN IF NOT EXISTS model VARCHAR(100);
ALTER TABLE devices ADD COLUMN IF NOT EXISTS serial_number VARCHAR(100);

-- Unique constraint for serial_number when present
CREATE UNIQUE INDEX IF NOT EXISTS idx_devices_serial_number_unique ON devices(serial_number) WHERE serial_number IS NOT NULL;

-- 3. Create device_registration_tokens table for secure QR onboarding
CREATE TABLE IF NOT EXISTS device_registration_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID REFERENCES users(id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_device_reg_tokens_hash ON device_registration_tokens(token_hash);
CREATE INDEX IF NOT EXISTS idx_device_reg_tokens_device_id ON device_registration_tokens(device_id);
