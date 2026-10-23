-- Persist the storey used by the Digital Twin room layout. Existing layouts
-- remain on floor 1, preserving the previous single-floor API behavior.
ALTER TABLE public.twin_room_layouts
    ADD COLUMN floor_number SMALLINT NOT NULL DEFAULT 1,
    ADD CONSTRAINT chk_twin_room_layout_floor
        CHECK (floor_number BETWEEN 1 AND 100);

CREATE INDEX idx_twin_room_layouts_layout_floor
    ON public.twin_room_layouts(layout_id, floor_number, room_id);
