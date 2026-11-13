-- ==============================================================================
-- HESTA SMART HOME - MIGRATION: FALL DETECTION & EMERGENCY SOS
-- ==============================================================================

CREATE TABLE IF NOT EXISTS fall_incidents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    home_id UUID NOT NULL,
    device_id VARCHAR(100),
    room_name VARCHAR(100),
    incident_type VARCHAR(50) NOT NULL DEFAULT 'ACCIDENTAL_FALL',
    state VARCHAR(50) NOT NULL DEFAULT 'DETECTED',
    confidence_score DOUBLE PRECISION,
    detected_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMP WITH TIME ZONE,
    resolution_note TEXT,
    snapshot_url VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_fall_incidents_home_id ON fall_incidents(home_id);
CREATE INDEX IF NOT EXISTS idx_fall_incidents_state ON fall_incidents(state);

CREATE TABLE IF NOT EXISTS fall_detection_zones (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    home_id UUID NOT NULL,
    room_name VARCHAR(100) NOT NULL,
    zone_type VARCHAR(50) NOT NULL DEFAULT 'CAMERA_VISION',
    device_id VARCHAR(100),
    sensitivity_level VARCHAR(20) DEFAULT 'BALANCED',
    countdown_seconds INT DEFAULT 30,
    has_rest_zone BOOLEAN DEFAULT FALSE,
    rest_zone_metadata JSONB,
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
