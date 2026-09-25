CREATE TABLE scene_executions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scene_id UUID NOT NULL REFERENCES scenes(id) ON DELETE CASCADE,
    trigger_source VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    result_detail JSONB NOT NULL DEFAULT '[]'::jsonb
);

CREATE INDEX idx_scene_executions_scene_time
    ON scene_executions(scene_id, started_at DESC);
