-- Phase 4.5: Optional user opt-out for maintenance ticket email notifications (default enabled)

ALTER TABLE public.users
    ADD COLUMN IF NOT EXISTS maintenance_email_enabled BOOLEAN NOT NULL DEFAULT TRUE;
