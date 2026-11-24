-- Phase 4.2 & 4.3: Quality feedback columns + anonymous event log for metrics dashboard

ALTER TABLE public.maintenance_chat_conversations
    ADD COLUMN IF NOT EXISTS user_rating SMALLINT NULL CHECK (user_rating IN (1, 5)),
    ADD COLUMN IF NOT EXISTS user_feedback_note VARCHAR(500) NULL;

ALTER TABLE public.maintenance_tickets
    ADD COLUMN IF NOT EXISTS diagnosis_quality VARCHAR(20) NULL CHECK (diagnosis_quality IN ('HELPFUL', 'PARTIAL', 'MISLEADING')),
    ADD COLUMN IF NOT EXISTS diagnosis_quality_note VARCHAR(500) NULL;

CREATE TABLE IF NOT EXISTS public.maintenance_chat_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID REFERENCES public.maintenance_chat_conversations(id) ON DELETE SET NULL,
    home_id UUID NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_mchat_events_created_type
    ON public.maintenance_chat_events (created_at, event_type);

CREATE INDEX IF NOT EXISTS idx_mchat_events_conv
    ON public.maintenance_chat_events (conversation_id);

CREATE INDEX IF NOT EXISTS idx_mchat_conv_created_status
    ON public.maintenance_chat_conversations (created_at, status);

CREATE INDEX IF NOT EXISTS idx_mtickets_created_source_quality
    ON public.maintenance_tickets (created_at, source, diagnosis_quality);
