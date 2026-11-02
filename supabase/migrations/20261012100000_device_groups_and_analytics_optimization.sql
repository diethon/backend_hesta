-- Migration: Device Groups and Advanced Analytics Optimization (Tasks PHO-032 to PHO-043)
-- Description:
-- 1. Create device_groups table for custom cross-room device grouping (PHO-041)
-- 2. Create device_group_members join table for many-to-many relationship
-- 3. Add performance indexes for device group lookups and energy Riemann timeseries queries (PHO-032 / PHO-034)
-- Author: Ta Tan Phong (Lead Backend)

-- 1. Table: device_groups (PHO-041)
CREATE TABLE IF NOT EXISTS device_groups (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    home_id UUID NOT NULL REFERENCES homes(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    icon VARCHAR(50) DEFAULT 'layers',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- 2. Table: device_group_members (PHO-041)
CREATE TABLE IF NOT EXISTS device_group_members (
    group_id UUID NOT NULL REFERENCES device_groups(id) ON DELETE CASCADE,
    device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    PRIMARY KEY (group_id, device_id)
);

-- 3. Indexes for Device Groups
CREATE INDEX IF NOT EXISTS idx_device_groups_home_id ON device_groups(home_id);
CREATE INDEX IF NOT EXISTS idx_device_group_members_device_id ON device_group_members(device_id);

-- 4. Optimization Index for Energy Analytics & Riemann Integration (PHO-032 / PHO-034)
-- Accelerates findByDeviceIdsAndChangedAtBetween queries on device_state_history
CREATE INDEX IF NOT EXISTS idx_device_state_history_device_changed 
ON device_state_history(device_id, changed_at ASC);

-- 5. Documentation comments
COMMENT ON TABLE device_groups IS 'Custom device groups across rooms with master dimmer control (PHO-041)';
COMMENT ON TABLE device_group_members IS 'Join table mapping devices to device groups (many-to-many)';
