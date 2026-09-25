-- Complete the Automation and Behavior prototypes on top of the initial schema.

ALTER TABLE automation_rules
    ADD COLUMN description VARCHAR(2000);

ALTER TABLE automation_rules
    ADD CONSTRAINT uq_automation_rules_home_name UNIQUE (home_id, name),
    ADD CONSTRAINT chk_automation_rules_trigger_type
        CHECK (trigger_type IN ('SENSOR', 'EVENT', 'SCHEDULE'));

ALTER TABLE rule_conditions
    ADD COLUMN attribute VARCHAR(100),
    ADD COLUMN expected_value JSONB;

UPDATE rule_conditions
SET attribute = 'value',
    expected_value = to_jsonb(value),
    operator = CASE operator
        WHEN '=' THEN 'EQ'
        WHEN '!=' THEN 'NE'
        WHEN '>' THEN 'GT'
        WHEN '>=' THEN 'GTE'
        WHEN '<' THEN 'LT'
        WHEN '<=' THEN 'LTE'
        ELSE upper(operator)
    END;

WITH ranked_conditions AS (
    SELECT id,
           (ROW_NUMBER() OVER (PARTITION BY rule_id ORDER BY order_index, id) - 1)::SMALLINT AS normalized_order
    FROM rule_conditions
)
UPDATE rule_conditions AS condition
SET order_index = ranked_conditions.normalized_order
FROM ranked_conditions
WHERE condition.id = ranked_conditions.id;

ALTER TABLE rule_conditions
    ALTER COLUMN attribute SET NOT NULL,
    ALTER COLUMN expected_value SET NOT NULL,
    ALTER COLUMN logical_group SET NOT NULL,
    DROP COLUMN value,
    ADD CONSTRAINT chk_rule_conditions_operator CHECK (operator IN ('EQ', 'NE', 'GT', 'GTE', 'LT', 'LTE')),
    ADD CONSTRAINT chk_rule_conditions_logical_group CHECK (logical_group IN ('AND', 'OR')),
    ADD CONSTRAINT chk_rule_conditions_order_nonnegative CHECK (order_index >= 0),
    ADD CONSTRAINT uq_rule_conditions_rule_order UNIQUE (rule_id, order_index);

WITH ranked_actions AS (
    SELECT id,
           (ROW_NUMBER() OVER (PARTITION BY rule_id ORDER BY order_index, id) - 1)::SMALLINT AS normalized_order
    FROM rule_actions
)
UPDATE rule_actions AS action
SET order_index = ranked_actions.normalized_order
FROM ranked_actions
WHERE action.id = ranked_actions.id;

ALTER TABLE rule_actions
    ADD CONSTRAINT chk_rule_actions_order_nonnegative CHECK (order_index >= 0),
    ADD CONSTRAINT uq_rule_actions_rule_order UNIQUE (rule_id, order_index);

CREATE INDEX idx_rule_conditions_device ON rule_conditions(device_id);
CREATE INDEX idx_rule_actions_device ON rule_actions(device_id);
CREATE INDEX idx_automation_rules_home_enabled_trigger
    ON automation_rules(home_id, enabled, trigger_type);

ALTER TABLE behavior_events
    ADD COLUMN room_id UUID REFERENCES rooms(id) ON DELETE SET NULL,
    ADD COLUMN dataset_key VARCHAR(100);

CREATE INDEX idx_behavior_events_dataset ON behavior_events(home_id, dataset_key)
    WHERE dataset_key IS NOT NULL;

-- Persisted protection for all device references that must stay inside a Home.
CREATE OR REPLACE FUNCTION enforce_automation_device_same_home() RETURNS TRIGGER AS $$
DECLARE
    rule_home_id UUID;
    device_home_id UUID;
BEGIN
    IF NEW.device_id IS NULL THEN
        RETURN NEW;
    END IF;
    SELECT home_id INTO rule_home_id FROM automation_rules WHERE id = NEW.rule_id;
    SELECT home_id INTO device_home_id FROM devices WHERE id = NEW.device_id;
    IF rule_home_id IS DISTINCT FROM device_home_id THEN
        RAISE EXCEPTION 'Automation device must belong to the rule home' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_rule_conditions_same_home
    BEFORE INSERT OR UPDATE OF rule_id, device_id ON rule_conditions
    FOR EACH ROW EXECUTE FUNCTION enforce_automation_device_same_home();

CREATE TRIGGER trg_rule_actions_same_home
    BEFORE INSERT OR UPDATE OF rule_id, device_id ON rule_actions
    FOR EACH ROW EXECUTE FUNCTION enforce_automation_device_same_home();

CREATE OR REPLACE FUNCTION enforce_behavior_location_same_home() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.device_id IS NOT NULL AND EXISTS (
        SELECT 1 FROM devices WHERE id = NEW.device_id AND home_id IS DISTINCT FROM NEW.home_id
    ) THEN
        RAISE EXCEPTION 'Behavior event device must belong to the event home' USING ERRCODE = '23514';
    END IF;
    IF NEW.room_id IS NOT NULL AND EXISTS (
        SELECT 1 FROM rooms WHERE id = NEW.room_id AND home_id IS DISTINCT FROM NEW.home_id
    ) THEN
        RAISE EXCEPTION 'Behavior event room must belong to the event home' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_behavior_events_same_home
    BEFORE INSERT OR UPDATE OF home_id, device_id, room_id ON behavior_events
    FOR EACH ROW EXECUTE FUNCTION enforce_behavior_location_same_home();
