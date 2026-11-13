-- Migration: Add generic settings JSONB column to devices table
-- Description: Stores customizable user configurations, presets (e.g. RGB custom colors),
-- and preferences independently of live telemetry currentState.
-- Author: Ta Tan Phong (Lead Backend)

ALTER TABLE devices ADD COLUMN IF NOT EXISTS settings JSONB DEFAULT '{}'::jsonb;

-- Populate existing rows where settings might be NULL to empty JSON object
UPDATE devices SET settings = '{}'::jsonb WHERE settings IS NULL;

COMMENT ON COLUMN devices.settings IS 'Extensible JSONB configuration for device settings and user presets (e.g. customColors)';
