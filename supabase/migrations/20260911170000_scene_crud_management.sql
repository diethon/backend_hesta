-- Allow actions such as TURN_ON/TURN_OFF that do not carry a value.
ALTER TABLE scene_actions
    ALTER COLUMN target_state DROP NOT NULL;

-- Reordering updates several unique positions in one transaction. Deferring the
-- constraint avoids transient collisions while still checking the final state.
ALTER TABLE scene_actions
    DROP CONSTRAINT uq_scene_actions_scene_order,
    ADD CONSTRAINT uq_scene_actions_scene_order
        UNIQUE (scene_id, order_index)
        DEFERRABLE INITIALLY IMMEDIATE;
