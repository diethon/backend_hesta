-- Repair databases where the earlier Scene CRUD migration was recorded but
-- scene_actions.target_state still has the original NOT NULL constraint.
-- TURN_ON and TURN_OFF legitimately have no target value.
ALTER TABLE scene_actions
    ALTER COLUMN target_state DROP NOT NULL;
