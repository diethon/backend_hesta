-- Quiet hours: non-HIGH notifications are still stored but delivered without popup/sound.
ALTER TABLE public.user_preferences
    ADD COLUMN IF NOT EXISTS quiet_hours_enabled BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS quiet_hours_start   TIME    NOT NULL DEFAULT '22:00',
    ADD COLUMN IF NOT EXISTS quiet_hours_end     TIME    NOT NULL DEFAULT '07:00';
