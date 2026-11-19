-- Migration: Fix functions referencing dropped devices.home_id
-- Description: After devices.home_id was dropped in 20260925142119_iot_device_refactor,
-- several trigger functions still queried devices.home_id. This migration rewrites them
-- to derive home_id via COALESCE(rooms.home_id, edge_nodes.home_id).

-- 1. Behavior events home consistency
CREATE OR REPLACE FUNCTION enforce_behavior_location_same_home() RETURNS TRIGGER AS $$
DECLARE
    dev_home_id UUID;
BEGIN
    IF NEW.device_id IS NOT NULL THEN
        SELECT COALESCE(r.home_id, en.home_id) INTO dev_home_id
        FROM devices d
        LEFT JOIN rooms r ON d.room_id = r.id
        LEFT JOIN edge_nodes en ON d.node_id = en.id
        WHERE d.id = NEW.device_id;

        IF dev_home_id IS NOT NULL AND dev_home_id IS DISTINCT FROM NEW.home_id THEN
            RAISE EXCEPTION 'Behavior event device must belong to the event home' USING ERRCODE = '23514';
        END IF;
    END IF;

    IF NEW.room_id IS NOT NULL AND EXISTS (
        SELECT 1 FROM rooms WHERE id = NEW.room_id AND home_id IS DISTINCT FROM NEW.home_id
    ) THEN
        RAISE EXCEPTION 'Behavior event room must belong to the event home' USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- 2. Automation rule actions & conditions home consistency
CREATE OR REPLACE FUNCTION enforce_automation_device_same_home() RETURNS TRIGGER AS $$
DECLARE
    rule_home_id UUID;
    device_home_id UUID;
BEGIN
    IF NEW.device_id IS NULL THEN
        RETURN NEW;
    END IF;

    SELECT home_id INTO rule_home_id FROM automation_rules WHERE id = NEW.rule_id;

    SELECT COALESCE(r.home_id, en.home_id) INTO device_home_id
    FROM devices d
    LEFT JOIN rooms r ON d.room_id = r.id
    LEFT JOIN edge_nodes en ON d.node_id = en.id
    WHERE d.id = NEW.device_id;

    IF device_home_id IS NOT NULL AND rule_home_id IS DISTINCT FROM device_home_id THEN
        RAISE EXCEPTION 'Automation device must belong to the rule home' USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- 3. Scene actions home consistency
CREATE OR REPLACE FUNCTION enforce_scene_action_same_home() RETURNS TRIGGER AS $$
DECLARE
    scene_home_id UUID;
    device_home_id UUID;
BEGIN
    IF NEW.device_id IS NULL THEN
        RETURN NEW;
    END IF;

    SELECT home_id INTO scene_home_id FROM scenes WHERE id = NEW.scene_id;

    SELECT COALESCE(r.home_id, en.home_id) INTO device_home_id
    FROM devices d
    LEFT JOIN rooms r ON d.room_id = r.id
    LEFT JOIN edge_nodes en ON d.node_id = en.id
    WHERE d.id = NEW.device_id;

    IF device_home_id IS NOT NULL AND scene_home_id IS DISTINCT FROM device_home_id THEN
        RAISE EXCEPTION 'Scene action device must belong to the scene home' USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- 4. Prevent scene home change mismatching action devices
CREATE OR REPLACE FUNCTION prevent_scene_home_mismatch() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.home_id IS DISTINCT FROM OLD.home_id
       AND EXISTS (
           SELECT 1
           FROM scene_actions AS scene_action
           JOIN devices AS device ON device.id = scene_action.device_id
           LEFT JOIN rooms AS r ON device.room_id = r.id
           LEFT JOIN edge_nodes AS en ON device.node_id = en.id
           WHERE scene_action.scene_id = NEW.id
             AND COALESCE(r.home_id, en.home_id) IS NOT NULL
             AND COALESCE(r.home_id, en.home_id) IS DISTINCT FROM NEW.home_id
       ) THEN
        RAISE EXCEPTION 'Scene home cannot differ from an existing action device home'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
