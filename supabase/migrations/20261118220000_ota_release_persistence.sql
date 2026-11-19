-- ==============================================================================
-- HESTA SMART HOME - MIGRATION: RELEASE OTA FIRMWARE MANAGEMENT & NODE METADATA
-- ==============================================================================

-- 1. Restore/add firmware version & device type metadata on edge_nodes
ALTER TABLE edge_nodes
    ADD COLUMN IF NOT EXISTS firmware_version VARCHAR(50),
    ADD COLUMN IF NOT EXISTS device_type VARCHAR(50);

-- 2. OTA Firmware Packages registry (uploaded .bin files & external URLs)
CREATE TABLE IF NOT EXISTS ota_firmware_packages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_type VARCHAR(50) NOT NULL,
    version VARCHAR(50) NOT NULL,
    file_url TEXT NOT NULL,
    storage_key VARCHAR(255),
    original_file_name VARCHAR(255),
    is_uploaded BOOLEAN NOT NULL DEFAULT FALSE,
    checksum_sha256 VARCHAR(64) NOT NULL,
    file_size_bytes BIGINT NOT NULL DEFAULT 0,
    release_notes TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ota_packages_device_type_created
    ON ota_firmware_packages(device_type, created_at DESC);

-- 3. OTA Campaigns table
CREATE TABLE IF NOT EXISTS ota_campaigns (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(200) NOT NULL,
    firmware_package_id UUID NOT NULL REFERENCES ota_firmware_packages(id),
    device_type VARCHAR(50) NOT NULL,
    version VARCHAR(50) NOT NULL,
    status VARCHAR(40) NOT NULL,
    rollout_strategy VARCHAR(30) NOT NULL,
    failure_threshold_percent DOUBLE PRECISION NOT NULL,
    current_wave INTEGER NOT NULL DEFAULT 1,
    total_nodes INTEGER NOT NULL DEFAULT 0,
    attempted_nodes INTEGER NOT NULL DEFAULT 0,
    success_nodes INTEGER NOT NULL DEFAULT 0,
    failed_nodes INTEGER NOT NULL DEFAULT 0,
    abort_reason TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ota_campaigns_status_device_type
    ON ota_campaigns(status, device_type);

-- 4. Per-node OTA execution state inside each campaign (idempotent wave tracking)
CREATE TABLE IF NOT EXISTS ota_campaign_nodes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    campaign_id UUID NOT NULL REFERENCES ota_campaigns(id) ON DELETE CASCADE,
    node_id UUID REFERENCES edge_nodes(id) ON DELETE SET NULL,
    node_code VARCHAR(100) NOT NULL,
    wave INTEGER NOT NULL,
    status VARCHAR(30) NOT NULL,
    reported_version VARCHAR(50),
    error_message TEXT,
    dispatched_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_ota_campaign_nodes_campaign_node UNIQUE (campaign_id, node_code)
);

CREATE INDEX IF NOT EXISTS idx_ota_campaign_nodes_campaign_wave
    ON ota_campaign_nodes(campaign_id, wave);
CREATE INDEX IF NOT EXISTS idx_ota_campaign_nodes_node_status
    ON ota_campaign_nodes(node_code, status);
