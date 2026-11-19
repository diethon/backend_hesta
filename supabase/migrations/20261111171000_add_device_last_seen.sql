-- Migration: Restore devices.last_seen column
-- Description: Cột devices.last_seen được phục hồi sau khi bị drop ở 20260925142119, phục vụ Anomaly Engine và Twin Health.

ALTER TABLE devices ADD COLUMN IF NOT EXISTS last_seen TIMESTAMPTZ;
