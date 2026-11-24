-- ==============================================================================
-- HESTA SMART HOME - MIGRATION: LLM USAGE TRACKING FOR MAINTENANCE CHAT
-- ==============================================================================

ALTER TABLE public.maintenance_chat_conversations
    ADD COLUMN IF NOT EXISTS llm_call_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS llm_total_tokens INTEGER NOT NULL DEFAULT 0;
