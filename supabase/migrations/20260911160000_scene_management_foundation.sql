-- Complete the Sprint 1 Scene persistence model on top of the initial schema.
ALTER TABLE scenes
    ADD COLUMN description VARCHAR(2000),
    ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT true;

ALTER TABLE scene_actions
    ADD COLUMN action VARCHAR(50),
    ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- Preserve any pre-foundation rows while giving their generic target_state a command name.
UPDATE scene_actions
SET action = 'SET_STATE'
WHERE action IS NULL;

ALTER TABLE scene_actions
    ALTER COLUMN action SET NOT NULL;

-- The original schema allowed duplicate default order 0 values. Normalize existing
-- rows deterministically before enforcing one order position per Scene.
WITH ranked_actions AS (
    SELECT id,
           (ROW_NUMBER() OVER (
               PARTITION BY scene_id
               ORDER BY order_index ASC, id ASC
           ) - 1)::SMALLINT AS normalized_order
    FROM scene_actions
)
UPDATE scene_actions AS scene_action
SET order_index = ranked_actions.normalized_order
FROM ranked_actions
WHERE scene_action.id = ranked_actions.id;

ALTER TABLE scene_actions
    ADD CONSTRAINT chk_scene_actions_order_nonnegative
        CHECK (order_index >= 0),
    ADD CONSTRAINT chk_scene_actions_action_not_blank
        CHECK (char_length(btrim(action)) > 0),
    ADD CONSTRAINT uq_scene_actions_scene_order
        UNIQUE (scene_id, order_index);

CREATE INDEX idx_scene_actions_device ON scene_actions(device_id);

CREATE TRIGGER trg_scene_actions_updated_at
    BEFORE UPDATE ON scene_actions
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Enforce the cross-Home invariant from authoritative persisted relationships.
CREATE OR REPLACE FUNCTION enforce_scene_action_same_home() RETURNS TRIGGER AS $$
DECLARE
    scene_home_id UUID;
    device_home_id UUID;
BEGIN
    SELECT home_id INTO scene_home_id FROM scenes WHERE id = NEW.scene_id;
    SELECT home_id INTO device_home_id FROM devices WHERE id = NEW.device_id;

    IF scene_home_id IS DISTINCT FROM device_home_id THEN
        RAISE EXCEPTION 'Scene action device must belong to the scene home'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_scene_actions_same_home
    BEFORE INSERT OR UPDATE OF scene_id, device_id ON scene_actions
    FOR EACH ROW EXECUTE FUNCTION enforce_scene_action_same_home();

CREATE OR REPLACE FUNCTION prevent_scene_home_mismatch() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.home_id IS DISTINCT FROM OLD.home_id
       AND EXISTS (
           SELECT 1
           FROM scene_actions AS scene_action
           JOIN devices AS device ON device.id = scene_action.device_id
           WHERE scene_action.scene_id = NEW.id
             AND device.home_id IS DISTINCT FROM NEW.home_id
       ) THEN
        RAISE EXCEPTION 'Scene home cannot differ from an existing action device home'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_scenes_preserve_action_home
    BEFORE UPDATE OF home_id ON scenes
    FOR EACH ROW EXECUTE FUNCTION prevent_scene_home_mismatch();

CREATE OR REPLACE FUNCTION prevent_device_home_mismatch() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.home_id IS DISTINCT FROM OLD.home_id
       AND EXISTS (
           SELECT 1
           FROM scene_actions AS scene_action
           JOIN scenes AS scene ON scene.id = scene_action.scene_id
           WHERE scene_action.device_id = NEW.id
             AND scene.home_id IS DISTINCT FROM NEW.home_id
       ) THEN
        RAISE EXCEPTION 'Device home cannot differ from an existing action scene home'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_devices_preserve_action_home
    BEFORE UPDATE OF home_id ON devices
    FOR EACH ROW EXECUTE FUNCTION prevent_device_home_mismatch();
