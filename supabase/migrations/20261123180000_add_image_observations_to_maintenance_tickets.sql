-- ==============================================================================
-- HESTA SMART HOME - MIGRATION: SEPARATE IMAGE OBSERVATIONS ON MAINTENANCE TICKETS
-- ==============================================================================

ALTER TABLE public.maintenance_tickets
    ADD COLUMN IF NOT EXISTS image_observations TEXT;
