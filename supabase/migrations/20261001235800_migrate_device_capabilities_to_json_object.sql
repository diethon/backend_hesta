-- Migration: Update devices with legacy array capabilities to jsonb object
-- Description: Aligns DB data with Java Map<String, List<String>> capabilities mapping

UPDATE devices
SET capabilities = '{}'::jsonb
WHERE capabilities IS NULL OR jsonb_typeof(capabilities) = 'array';

ALTER TABLE devices ALTER COLUMN capabilities SET DEFAULT '{}'::jsonb;
