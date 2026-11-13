-- Migration to enhance automation_recommendations for Machine Learning user behavior recommendations
ALTER TABLE automation_recommendations
    ADD COLUMN IF NOT EXISTS engine_type VARCHAR(30) DEFAULT 'RULE_BASED',
    ADD COLUMN IF NOT EXISTS model_version VARCHAR(50);

CREATE INDEX IF NOT EXISTS idx_recommendations_device_status_created
    ON automation_recommendations(home_id, device_id, status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_behavior_events_user_action
    ON behavior_events(home_id, user_id, event_type, occurred_at DESC);
