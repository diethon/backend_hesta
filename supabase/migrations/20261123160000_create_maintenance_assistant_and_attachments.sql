-- ==============================================================================
-- HESTA SMART HOME - MIGRATION: MAINTENANCE ASSISTANT & ATTACHMENTS
-- ==============================================================================

-- 1. Mở rộng bảng maintenance_tickets (backward-compatible)
ALTER TABLE public.maintenance_tickets
    ADD COLUMN IF NOT EXISTS source VARCHAR(20) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN IF NOT EXISTS diagnosis_summary TEXT,
    ADD COLUMN IF NOT EXISTS chat_conversation_id UUID;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_maintenance_tickets_source'
    ) THEN
        ALTER TABLE public.maintenance_tickets
            ADD CONSTRAINT chk_maintenance_tickets_source CHECK (source IN ('MANUAL', 'CHATBOT'));
    END IF;
END $$;

-- 2. Ảnh đính kèm cho ticket bảo trì (upload trước, gắn vào ticket khi tạo)
CREATE TABLE IF NOT EXISTS public.maintenance_ticket_attachments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    home_id UUID NOT NULL REFERENCES public.homes(id) ON DELETE CASCADE,
    ticket_id UUID REFERENCES public.maintenance_tickets(id) ON DELETE CASCADE,
    uploaded_by UUID NOT NULL REFERENCES public.users(id),
    storage_public_id VARCHAR(255) NOT NULL,
    url TEXT NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_maintenance_attachments_size CHECK (size_bytes > 0)
);

CREATE INDEX IF NOT EXISTS idx_maintenance_attachments_ticket
    ON public.maintenance_ticket_attachments (ticket_id);
CREATE INDEX IF NOT EXISTS idx_maintenance_attachments_orphan
    ON public.maintenance_ticket_attachments (created_at)
    WHERE ticket_id IS NULL;

-- 3. Hội thoại trợ lý chẩn đoán bảo trì
CREATE TABLE IF NOT EXISTS public.maintenance_chat_conversations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    home_id UUID NOT NULL REFERENCES public.homes(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES public.users(id),
    device_id UUID REFERENCES public.devices(id) ON DELETE SET NULL,
    state VARCHAR(40) NOT NULL,
    context_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    ticket_id UUID REFERENCES public.maintenance_tickets(id) ON DELETE SET NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_maintenance_chat_conv_status CHECK (status IN ('ACTIVE', 'RESOLVED', 'ESCALATED', 'ABANDONED'))
);

CREATE INDEX IF NOT EXISTS idx_maintenance_chat_conv_home_user_status
    ON public.maintenance_chat_conversations (home_id, user_id, status, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_maintenance_chat_conv_user_status
    ON public.maintenance_chat_conversations (user_id, status);

-- 4. Tin nhắn hội thoại trợ lý chẩn đoán
CREATE TABLE IF NOT EXISTS public.maintenance_chat_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL REFERENCES public.maintenance_chat_conversations(id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL,
    content_json JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_maintenance_chat_msg_role CHECK (role IN ('USER', 'ASSISTANT'))
);

CREATE INDEX IF NOT EXISTS idx_maintenance_chat_msg_conv_created
    ON public.maintenance_chat_messages (conversation_id, created_at ASC);

-- 5. Khóa ngoại từ maintenance_tickets -> maintenance_chat_conversations
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_maintenance_tickets_chat_conversation'
    ) THEN
        ALTER TABLE public.maintenance_tickets
            ADD CONSTRAINT fk_maintenance_tickets_chat_conversation
            FOREIGN KEY (chat_conversation_id)
            REFERENCES public.maintenance_chat_conversations(id)
            ON DELETE SET NULL;
    END IF;
END $$;

-- 6. Bật RLS giống 5 bảng maintenance hiện có (chỉ backend truy cập)
ALTER TABLE public.maintenance_ticket_attachments ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.maintenance_chat_conversations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.maintenance_chat_messages ENABLE ROW LEVEL SECURITY;
