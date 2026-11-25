-- Phase 4.1: Rollout allowlist per home & dynamic DB kill switch for Maintenance AI Assistant

CREATE TABLE IF NOT EXISTS public.maintenance_assistant_rollout (
    home_id UUID PRIMARY KEY REFERENCES public.homes(id) ON DELETE CASCADE,
    ai_text_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ai_image_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    note VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS public.maintenance_assistant_settings (
    setting_key VARCHAR(100) PRIMARY KEY,
    setting_value VARCHAR(255) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO public.maintenance_assistant_settings (setting_key, setting_value)
VALUES ('ai_kill_switch', 'false')
ON CONFLICT (setting_key) DO NOTHING;
