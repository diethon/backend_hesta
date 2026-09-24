-- Manual rollback for:
--   20260911170000_scene_crud_management.sql
--   20260911160000_scene_management_foundation.sql
--
-- Run this file manually against the intended database. It deliberately lives
-- outside supabase/migrations so a normal `supabase db push` cannot apply it.

BEGIN;

-- Revert the CRUD migration first. Preserve rows created with a null value by
-- converting them to the empty JSON object required by the original schema.
UPDATE scene_actions
SET target_state = '{}'::jsonb
WHERE target_state IS NULL;

ALTER TABLE scene_actions
    ALTER COLUMN target_state SET NOT NULL;

-- Remove every object introduced by the foundation migration. IF EXISTS makes
-- this safe when the failed migration was only partially applied.
DROP TRIGGER IF EXISTS trg_devices_preserve_action_home ON devices;
DROP TRIGGER IF EXISTS trg_scenes_preserve_action_home ON scenes;
DROP TRIGGER IF EXISTS trg_scene_actions_same_home ON scene_actions;
DROP TRIGGER IF EXISTS trg_scene_actions_updated_at ON scene_actions;

DROP FUNCTION IF EXISTS prevent_device_home_mismatch();
DROP FUNCTION IF EXISTS prevent_scene_home_mismatch();
DROP FUNCTION IF EXISTS enforce_scene_action_same_home();

DROP INDEX IF EXISTS idx_scene_actions_device;

ALTER TABLE scene_actions
    DROP CONSTRAINT IF EXISTS uq_scene_actions_scene_order,
    DROP CONSTRAINT IF EXISTS chk_scene_actions_action_not_blank,
    DROP CONSTRAINT IF EXISTS chk_scene_actions_order_nonnegative;

ALTER TABLE scene_actions
    DROP COLUMN IF EXISTS updated_at,
    DROP COLUMN IF EXISTS created_at,
    DROP COLUMN IF EXISTS action;

ALTER TABLE scenes
    DROP COLUMN IF EXISTS enabled,
    DROP COLUMN IF EXISTS description;

COMMIT;

-- Expected original columns after rollback:
-- scenes: id, home_id, name, icon, created_by, created_at, updated_at
-- scene_actions: id, scene_id, device_id, target_state, order_index
