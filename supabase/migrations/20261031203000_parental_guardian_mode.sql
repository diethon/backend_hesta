-- Migration: Parental Guardian Mode / Child Screen-Time Protection
-- Description: Creates parental_guardian_rules and parental_guardian_logs tables.

CREATE TABLE IF NOT EXISTS parental_guardian_rules (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    home_id UUID NOT NULL REFERENCES homes(id) ON DELETE CASCADE,
    target_device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    camera_device_id UUID REFERENCES devices(id) ON DELETE SET NULL,
    name VARCHAR(150) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT true,
    parent_away BOOLEAN NOT NULL DEFAULT false,
    allowed_duration_minutes INT NOT NULL DEFAULT 45,
    warning_minutes_before INT NOT NULL DEFAULT 5,
    status VARCHAR(30) NOT NULL DEFAULT 'IDLE',
    session_started_at TIMESTAMPTZ,
    session_expires_at TIMESTAMPTZ,
    auto_lockout BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_parental_guardian_home_id ON parental_guardian_rules(home_id);
CREATE INDEX IF NOT EXISTS idx_parental_guardian_target_device ON parental_guardian_rules(target_device_id);

CREATE TABLE IF NOT EXISTS parental_guardian_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rule_id UUID NOT NULL REFERENCES parental_guardian_rules(id) ON DELETE CASCADE,
    home_id UUID NOT NULL REFERENCES homes(id) ON DELETE CASCADE,
    event_type VARCHAR(50) NOT NULL,
    message TEXT NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_parental_guardian_logs_rule ON parental_guardian_logs(rule_id);
CREATE INDEX IF NOT EXISTS idx_parental_guardian_logs_home ON parental_guardian_logs(home_id);
