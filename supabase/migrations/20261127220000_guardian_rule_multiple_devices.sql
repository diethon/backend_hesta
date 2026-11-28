-- Migration: Parental guardian rules can limit several devices.
-- Moves the single target_device_id into a rule-device join table; one shared time budget per rule.

CREATE TABLE IF NOT EXISTS parental_guardian_rule_devices (
    rule_id UUID NOT NULL REFERENCES parental_guardian_rules(id) ON DELETE CASCADE,
    device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    PRIMARY KEY (rule_id, device_id)
);

CREATE INDEX IF NOT EXISTS idx_parental_guardian_rule_devices_device
    ON parental_guardian_rule_devices(device_id);

INSERT INTO parental_guardian_rule_devices (rule_id, device_id)
SELECT id, target_device_id
FROM parental_guardian_rules
WHERE target_device_id IS NOT NULL
ON CONFLICT DO NOTHING;

DROP INDEX IF EXISTS idx_parental_guardian_target_device;
ALTER TABLE parental_guardian_rules DROP COLUMN IF EXISTS target_device_id;
