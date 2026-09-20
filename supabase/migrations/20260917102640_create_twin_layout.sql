-- Digital Twin visual layout. Runtime state remains in homes/rooms/devices/
-- sensor_readings; these tables store only normalized visual placement.
CREATE TABLE twin_layouts (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    home_id    UUID NOT NULL UNIQUE REFERENCES homes(id) ON DELETE CASCADE,
    revision   BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE twin_room_layouts (
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    layout_id UUID NOT NULL REFERENCES twin_layouts(id) ON DELETE CASCADE,
    room_id   UUID NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
    x         NUMERIC(10,3) NOT NULL,
    y         NUMERIC(10,3) NOT NULL,
    width     NUMERIC(10,3) NOT NULL,
    height    NUMERIC(10,3) NOT NULL,
    UNIQUE (layout_id, room_id),
    CHECK (x >= 0 AND x <= 1),
    CHECK (y >= 0 AND y <= 1),
    CHECK (width > 0 AND width <= 1),
    CHECK (height > 0 AND height <= 1),
    CHECK (x + width <= 1),
    CHECK (y + height <= 1)
);
CREATE INDEX idx_twin_room_layouts_layout ON twin_room_layouts(layout_id);

CREATE TABLE twin_node_layouts (
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    layout_id UUID NOT NULL REFERENCES twin_layouts(id) ON DELETE CASCADE,
    node_type VARCHAR(20) NOT NULL,
    node_id   VARCHAR(255) NOT NULL,
    room_id   UUID REFERENCES rooms(id) ON DELETE SET NULL,
    x         NUMERIC(10,3) NOT NULL,
    y         NUMERIC(10,3) NOT NULL,
    UNIQUE (layout_id, node_type, node_id),
    CHECK (node_type IN ('DEVICE', 'SENSOR')),
    CHECK (x >= 0 AND x <= 1),
    CHECK (y >= 0 AND y <= 1)
);
CREATE INDEX idx_twin_node_layouts_layout ON twin_node_layouts(layout_id);

CREATE TRIGGER trg_twin_layouts_updated_at BEFORE UPDATE ON twin_layouts
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
