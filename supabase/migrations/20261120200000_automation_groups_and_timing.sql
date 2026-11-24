-- Automation: device groups as targets/conditions, wait steps, and "true for X seconds" rules.
ALTER TABLE rule_actions ADD COLUMN IF NOT EXISTS group_id UUID REFERENCES device_groups(id);
ALTER TABLE rule_conditions ADD COLUMN IF NOT EXISTS group_id UUID REFERENCES device_groups(id);
ALTER TABLE automation_rules ADD COLUMN IF NOT EXISTS hold_seconds INTEGER NOT NULL DEFAULT 0
    CHECK (hold_seconds BETWEEN 0 AND 3600);

-- A step now targets a device, a scene, a group, or is a DELAY (wait) step with no target.
ALTER TABLE rule_actions DROP CONSTRAINT IF EXISTS rule_actions_check;
ALTER TABLE rule_actions ADD CONSTRAINT rule_actions_target_check
    CHECK (device_id IS NOT NULL OR scene_id IS NOT NULL OR group_id IS NOT NULL OR action_command = 'DELAY');

CREATE INDEX IF NOT EXISTS idx_rule_actions_group ON rule_actions(group_id);
CREATE INDEX IF NOT EXISTS idx_rule_conditions_group ON rule_conditions(group_id);
