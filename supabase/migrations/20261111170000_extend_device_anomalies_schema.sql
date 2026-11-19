-- Migration: Extend device_anomalies schema for Tier-1 rule-based anomaly detection
-- Description: Adds audit, lifecycle, resolution, and metric metadata columns, partial unique active index, and query indexes.

ALTER TABLE device_anomalies
    ADD COLUMN IF NOT EXISTS metric VARCHAR(50),
    ADD COLUMN IF NOT EXISTS source VARCHAR(10) NOT NULL DEFAULT 'RULE',
    ADD COLUMN IF NOT EXISTS score NUMERIC,
    ADD COLUMN IF NOT EXISTS reason TEXT,
    ADD COLUMN IF NOT EXISTS reading_id BIGINT REFERENCES sensor_readings(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS last_seen_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS occurrence_count INT NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS acknowledged_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS acknowledged_by UUID REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS resolved_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS resolved_by UUID REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS false_positive BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS resolution_note TEXT;

-- Enforce valid status values
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_device_anomalies_status'
    ) THEN
        ALTER TABLE device_anomalies
            ADD CONSTRAINT chk_device_anomalies_status
            CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED'));
    END IF;
END $$;

-- Enforce valid severity values
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_device_anomalies_severity'
    ) THEN
        ALTER TABLE device_anomalies
            ADD CONSTRAINT chk_device_anomalies_severity
            CHECK (severity IS NULL OR severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL'));
    END IF;
END $$;

-- Enforce valid source values
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_device_anomalies_source'
    ) THEN
        ALTER TABLE device_anomalies
            ADD CONSTRAINT chk_device_anomalies_source
            CHECK (source IN ('RULE', 'ML', 'MANUAL'));
    END IF;
END $$;

-- Partial UNIQUE index: at most one active anomaly per (device_id, anomaly_type)
CREATE UNIQUE INDEX IF NOT EXISTS idx_device_anomalies_active_unique
    ON device_anomalies (device_id, anomaly_type)
    WHERE status IN ('OPEN', 'ACKNOWLEDGED');

-- Query index for home filtering by status, severity, and time
CREATE INDEX IF NOT EXISTS idx_device_anomalies_home_status_severity
    ON device_anomalies (home_id, status, severity, detected_at DESC);
