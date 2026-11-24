-- Migration: Dedicated Energy Rollups & Home Energy Settings Tables + Historical Backfill
-- Description:
-- 1. Create device_energy_rollups for immutable hourly energy buckets (fixes Local/Prod drift & speeds up charts)
-- 2. Create home_energy_settings for persistent Demand Response & billing configuration
-- 3. Backfill home_energy_settings and last 60 days of completed hourly buckets from device_state_history

CREATE TABLE IF NOT EXISTS device_energy_rollups (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    home_id         UUID NOT NULL REFERENCES homes(id) ON DELETE CASCADE,
    device_id       UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    bucket_start    TIMESTAMPTZ NOT NULL,
    kwh             NUMERIC(12, 4) NOT NULL DEFAULT 0,
    on_seconds      INTEGER NOT NULL DEFAULT 0,
    effective_watts NUMERIC(10, 2) NOT NULL DEFAULT 0,
    source          VARCHAR(20) NOT NULL DEFAULT 'ESTIMATED',
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_device_energy_rollup_bucket UNIQUE (device_id, bucket_start)
);

CREATE INDEX IF NOT EXISTS idx_energy_rollups_home_bucket
    ON device_energy_rollups(home_id, bucket_start ASC);

CREATE INDEX IF NOT EXISTS idx_energy_rollups_device_bucket
    ON device_energy_rollups(device_id, bucket_start ASC);

CREATE TABLE IF NOT EXISTS home_energy_settings (
    home_id              UUID PRIMARY KEY REFERENCES homes(id) ON DELETE CASCADE,
    dr_enabled           BOOLEAN NOT NULL DEFAULT true,
    peak_threshold_watts NUMERIC(10, 2) NOT NULL DEFAULT 4000.00,
    dr_mode              VARCHAR(30) NOT NULL DEFAULT 'AUTO_SHED',
    billing_start_day    INTEGER NOT NULL DEFAULT 1,
    timezone             VARCHAR(50) NOT NULL DEFAULT 'Asia/Ho_Chi_Minh',
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Seed home_energy_settings for all existing homes
INSERT INTO home_energy_settings (home_id)
SELECT id FROM homes
ON CONFLICT (home_id) DO NOTHING;

-- Backfill completed hourly buckets for the past 60 days in Asia/Ho_Chi_Minh
WITH current_hour AS (
    SELECT date_trunc('hour', now() AT TIME ZONE 'Asia/Ho_Chi_Minh') AT TIME ZONE 'Asia/Ho_Chi_Minh' AS cutoff
),
ordered_history AS (
    SELECT
        COALESCE(r.home_id, en.home_id) AS home_id,
        d.id AS device_id,
        d.device_type,
        d.status,
        d.last_seen,
        d.settings,
        h.changed_at AS raw_start,
        COALESCE(
            LEAD(h.changed_at) OVER (PARTITION BY h.device_id ORDER BY h.changed_at ASC),
            CASE
                WHEN d.status IN ('OFFLINE', 'UNKNOWN') THEN COALESCE(d.last_seen, h.changed_at)
                ELSE ch.cutoff
            END
        ) AS raw_end,
        h.new_state
    FROM device_state_history h
    JOIN devices d ON d.id = h.device_id
    LEFT JOIN rooms r ON r.id = d.room_id
    LEFT JOIN edge_nodes en ON en.id = d.node_id
    CROSS JOIN current_hour ch
    WHERE d.is_deleted = false
      AND COALESCE(r.home_id, en.home_id) IS NOT NULL
      AND h.changed_at >= (ch.cutoff - interval '60 days')
      AND h.changed_at < ch.cutoff
),
active_intervals AS (
    SELECT
        oh.home_id,
        oh.device_id,
        GREATEST(oh.raw_start, ch.cutoff - interval '60 days') AS seg_start,
        LEAST(oh.raw_end, ch.cutoff) AS seg_end,
        (
            CASE
                WHEN oh.new_state ? 'wattage' AND (oh.new_state->>'wattage') ~ '^[0-9]+(\.[0-9]+)?$'
                    THEN (oh.new_state->>'wattage')::numeric
                ELSE COALESCE(
                    NULLIF(CASE WHEN (oh.settings->>'wattage') ~ '^[0-9]+(\.[0-9]+)?$' THEN oh.settings->>'wattage' ELSE NULL END, '')::numeric,
                    CASE
                        WHEN UPPER(COALESCE(oh.device_type, '')) IN ('AIR_CONDITIONER', 'AC', 'AC_REMOTE') THEN 1500
                        WHEN UPPER(COALESCE(oh.device_type, '')) IN ('HEATER', 'WATER_HEATER') THEN 2500
                        WHEN UPPER(COALESCE(oh.device_type, '')) IN ('SMART_PLUG', 'SOCKET') THEN 500
                        WHEN UPPER(COALESCE(oh.device_type, '')) = 'WATER_PUMP' THEN 370
                        WHEN UPPER(COALESCE(oh.device_type, '')) IN ('GATE', 'GATE_MOTOR', 'ROLLING_DOOR', 'DOOR') THEN 350
                        WHEN UPPER(COALESCE(oh.device_type, '')) = 'FRIDGE' THEN 150
                        WHEN UPPER(COALESCE(oh.device_type, '')) = 'TV' THEN 120
                        WHEN UPPER(COALESCE(oh.device_type, '')) = 'FAN' THEN 65
                        WHEN UPPER(COALESCE(oh.device_type, '')) = 'CURTAIN' THEN 60
                        WHEN UPPER(COALESCE(oh.device_type, '')) IN ('LIGHT', 'LED') THEN 40
                        WHEN UPPER(COALESCE(oh.device_type, '')) = 'LED_RGB' THEN 24
                        WHEN UPPER(COALESCE(oh.device_type, '')) IN ('CAMERA_AI', 'CAMERA') THEN 15
                        WHEN UPPER(COALESCE(oh.device_type, '')) = 'ROUTER' THEN 12
                        WHEN UPPER(COALESCE(oh.device_type, '')) = 'IR_REMOTE' THEN 3
                        WHEN UPPER(COALESCE(oh.device_type, '')) LIKE '%SENSOR%' THEN 2
                        ELSE 60
                    END
                ) * CASE
                    WHEN UPPER(COALESCE(oh.device_type, '')) LIKE '%LIGHT%' OR UPPER(COALESCE(oh.device_type, '')) LIKE '%LED%' THEN
                        CASE
                            WHEN (oh.new_state->>'brightness') ~ '^[0-9]+(\.[0-9]+)?$' AND (oh.new_state->>'brightness')::numeric > 0
                                THEN LEAST(GREATEST((oh.new_state->>'brightness')::numeric, 1), 100) / 100.0
                            ELSE 1.0
                        END
                    WHEN UPPER(COALESCE(oh.device_type, '')) LIKE '%FAN%' AND (oh.new_state->>'speed') ~ '^[0-9]+$' THEN
                        CASE
                            WHEN (oh.new_state->>'speed')::int = 1 THEN 0.5
                            WHEN (oh.new_state->>'speed')::int = 2 THEN 0.75
                            ELSE 1.0
                        END
                    ELSE 1.0
                END
            END
        ) AS eff_watts
    FROM ordered_history oh
    CROSS JOIN current_hour ch
    WHERE oh.raw_end > oh.raw_start
      AND LEAST(oh.raw_end, ch.cutoff) > GREATEST(oh.raw_start, ch.cutoff - interval '60 days')
      AND (
          UPPER(COALESCE(oh.new_state->>'power', '')) IN ('ON', 'TRUE', '1')
          OR (
              UPPER(COALESCE(oh.device_type, '')) IN ('GATE', 'GATE_MOTOR', 'ROLLING_DOOR', 'DOOR', 'CURTAIN')
              AND UPPER(COALESCE(oh.new_state->>'state', '')) IN ('OPENING', 'CLOSING', 'RUNNING')
          )
          OR (
              UPPER(COALESCE(oh.device_type, '')) NOT IN ('GATE', 'GATE_MOTOR', 'ROLLING_DOOR', 'DOOR', 'CURTAIN')
              AND UPPER(COALESCE(oh.new_state->>'state', '')) IN ('OPENING', 'OPEN', 'RUNNING')
          )
      )
),
bucketed AS (
    SELECT
        ai.home_id,
        ai.device_id,
        gs AS bucket_start,
        GREATEST(0, EXTRACT(EPOCH FROM (LEAST(ai.seg_end, gs + interval '1 hour') - GREATEST(ai.seg_start, gs)))::int) AS sec_on,
        ai.eff_watts
    FROM active_intervals ai
    CROSS JOIN LATERAL generate_series(
        date_trunc('hour', ai.seg_start AT TIME ZONE 'Asia/Ho_Chi_Minh') AT TIME ZONE 'Asia/Ho_Chi_Minh',
        date_trunc('hour', (ai.seg_end - interval '1 microsecond') AT TIME ZONE 'Asia/Ho_Chi_Minh') AT TIME ZONE 'Asia/Ho_Chi_Minh',
        interval '1 hour'
    ) AS gs
    WHERE LEAST(ai.seg_end, gs + interval '1 hour') > GREATEST(ai.seg_start, gs)
),
aggregated AS (
    SELECT
        home_id,
        device_id,
        bucket_start,
        LEAST(3600, SUM(sec_on))::int AS on_seconds,
        ROUND(SUM((eff_watts * sec_on) / 3600000.0), 4) AS kwh,
        CASE
            WHEN SUM(sec_on) > 0 THEN ROUND(SUM(eff_watts * sec_on) / SUM(sec_on), 2)
            ELSE 0
        END AS effective_watts
    FROM bucketed
    GROUP BY home_id, device_id, bucket_start
    HAVING SUM(sec_on) > 0
)
INSERT INTO device_energy_rollups (home_id, device_id, bucket_start, kwh, on_seconds, effective_watts, source, updated_at)
SELECT home_id, device_id, bucket_start, kwh, on_seconds, effective_watts, 'ESTIMATED', now()
FROM aggregated
ON CONFLICT (device_id, bucket_start) DO UPDATE
SET kwh             = EXCLUDED.kwh,
    on_seconds      = EXCLUDED.on_seconds,
    effective_watts = EXCLUDED.effective_watts,
    updated_at      = now()
WHERE device_energy_rollups.source = 'ESTIMATED';
