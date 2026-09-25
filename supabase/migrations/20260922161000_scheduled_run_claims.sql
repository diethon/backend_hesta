CREATE TABLE scheduled_run_claims (
    schedule_type VARCHAR(20) NOT NULL,
    schedule_id UUID NOT NULL,
    local_date DATE NOT NULL,
    claimed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (schedule_type, schedule_id, local_date)
);

CREATE INDEX idx_scheduled_run_claims_date ON scheduled_run_claims(local_date);
