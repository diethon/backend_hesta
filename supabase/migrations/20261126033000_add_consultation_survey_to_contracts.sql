-- Migration: Add structured consultation & technical survey fields to installation_contracts
-- Separates initial customer consultation/survey from formal quotation & e-contract.

ALTER TABLE installation_contracts
    ADD COLUMN IF NOT EXISTS floor_count INTEGER DEFAULT 1,
    ADD COLUMN IF NOT EXISTS bedroom_count INTEGER DEFAULT 2,
    ADD COLUMN IF NOT EXISTS building_condition VARCHAR(40) NOT NULL DEFAULT 'OCCUPIED',
    ADD COLUMN IF NOT EXISTS desired_timeline VARCHAR(60),
    ADD COLUMN IF NOT EXISTS requested_devices JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS survey_checklist JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS survey_flags JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS survey_notes TEXT,
    ADD COLUMN IF NOT EXISTS survey_completed_at TIMESTAMPTZ;
