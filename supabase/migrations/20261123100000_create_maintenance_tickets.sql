-- ==============================================================================
-- HESTA SMART HOME - MIGRATION: MAINTENANCE TICKETS (báo hỏng / lịch kiểm tra / báo giá)
-- ==============================================================================

CREATE TABLE IF NOT EXISTS public.maintenance_tickets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(30) NOT NULL UNIQUE,
    home_id UUID NOT NULL REFERENCES public.homes(id) ON DELETE CASCADE,
    created_by UUID NOT NULL REFERENCES public.users(id),
    title VARCHAR(150) NOT NULL,
    description TEXT,
    contact_phone VARCHAR(20),
    priority VARCHAR(20) NOT NULL DEFAULT 'NORMAL',
    status VARCHAR(30) NOT NULL DEFAULT 'SUBMITTED',
    scheduled_start TIMESTAMP WITH TIME ZONE,
    scheduled_end TIMESTAMP WITH TIME ZONE,
    schedule_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    admin_note TEXT,
    cancel_reason TEXT,
    findings TEXT,
    warranty_months INTEGER,
    quote_total NUMERIC(14, 2),
    quote_submitted_at TIMESTAMP WITH TIME ZONE,
    quote_decision VARCHAR(20),
    quote_decided_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    rating SMALLINT,
    feedback TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_maintenance_tickets_priority CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    CONSTRAINT chk_maintenance_tickets_status CHECK (status IN (
        'SUBMITTED', 'SCHEDULED', 'RESCHEDULE_REQUESTED', 'INSPECTING', 'QUOTED',
        'REPAIRING', 'COMPLETED', 'CLOSED', 'CANCELLED', 'REJECTED')),
    CONSTRAINT chk_maintenance_tickets_quote_decision CHECK (quote_decision IS NULL OR quote_decision IN ('APPROVED', 'REJECTED')),
    CONSTRAINT chk_maintenance_tickets_schedule CHECK (scheduled_start IS NULL OR scheduled_end > scheduled_start),
    CONSTRAINT chk_maintenance_tickets_rating CHECK (rating IS NULL OR rating BETWEEN 1 AND 5),
    CONSTRAINT chk_maintenance_tickets_warranty CHECK (warranty_months IS NULL OR warranty_months BETWEEN 0 AND 60),
    CONSTRAINT chk_maintenance_tickets_quote_total CHECK (quote_total IS NULL OR quote_total >= 0)
);

CREATE INDEX IF NOT EXISTS idx_maintenance_tickets_home_created
    ON public.maintenance_tickets (home_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_maintenance_tickets_status_created
    ON public.maintenance_tickets (status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_maintenance_tickets_schedule
    ON public.maintenance_tickets (scheduled_start)
    WHERE scheduled_start IS NOT NULL;

-- Thiết bị trong ticket; lưu snapshot để lịch sử còn nguyên khi thiết bị bị xoá/đổi tên.
CREATE TABLE IF NOT EXISTS public.maintenance_ticket_devices (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_id UUID NOT NULL REFERENCES public.maintenance_tickets(id) ON DELETE CASCADE,
    device_id UUID REFERENCES public.devices(id) ON DELETE SET NULL,
    source_anomaly_id UUID REFERENCES public.device_anomalies(id) ON DELETE SET NULL,
    device_name VARCHAR(150) NOT NULL,
    device_type VARCHAR(50),
    model VARCHAR(100),
    serial_number VARCHAR(100),
    room_name VARCHAR(100),
    issue_note TEXT,
    outcome VARCHAR(30),
    outcome_note TEXT,
    CONSTRAINT chk_maintenance_ticket_devices_outcome CHECK (outcome IS NULL OR outcome IN (
        'FIXED', 'REPLACED', 'NOT_REPAIRABLE', 'NO_ISSUE_FOUND'))
);

CREATE INDEX IF NOT EXISTS idx_maintenance_ticket_devices_ticket
    ON public.maintenance_ticket_devices (ticket_id);
CREATE INDEX IF NOT EXISTS idx_maintenance_ticket_devices_device
    ON public.maintenance_ticket_devices (device_id);

-- Các khung giờ rảnh người dùng đề xuất.
CREATE TABLE IF NOT EXISTS public.maintenance_availability_slots (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_id UUID NOT NULL REFERENCES public.maintenance_tickets(id) ON DELETE CASCADE,
    start_at TIMESTAMP WITH TIME ZONE NOT NULL,
    end_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_maintenance_slots_range CHECK (end_at > start_at)
);

CREATE INDEX IF NOT EXISTS idx_maintenance_slots_ticket
    ON public.maintenance_availability_slots (ticket_id, start_at);

-- Hạng mục báo giá / sửa chữa.
CREATE TABLE IF NOT EXISTS public.maintenance_quote_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_id UUID NOT NULL REFERENCES public.maintenance_tickets(id) ON DELETE CASCADE,
    ticket_device_id UUID REFERENCES public.maintenance_ticket_devices(id) ON DELETE SET NULL,
    item_type VARCHAR(20) NOT NULL,
    description VARCHAR(255) NOT NULL,
    quantity INTEGER NOT NULL,
    unit_price NUMERIC(14, 2) NOT NULL,
    amount NUMERIC(14, 2) NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT chk_maintenance_quote_items_type CHECK (item_type IN ('PART', 'LABOR', 'FEE')),
    CONSTRAINT chk_maintenance_quote_items_quantity CHECK (quantity > 0),
    CONSTRAINT chk_maintenance_quote_items_price CHECK (unit_price >= 0 AND amount >= 0)
);

CREATE INDEX IF NOT EXISTS idx_maintenance_quote_items_ticket
    ON public.maintenance_quote_items (ticket_id, sort_order);

-- Timeline / audit log của ticket.
CREATE TABLE IF NOT EXISTS public.maintenance_ticket_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_id UUID NOT NULL REFERENCES public.maintenance_tickets(id) ON DELETE CASCADE,
    actor_id UUID REFERENCES public.users(id) ON DELETE SET NULL,
    actor_role VARCHAR(20) NOT NULL,
    from_status VARCHAR(30),
    to_status VARCHAR(30) NOT NULL,
    note TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_maintenance_ticket_events_actor_role CHECK (actor_role IN ('USER', 'ADMIN'))
);

CREATE INDEX IF NOT EXISTS idx_maintenance_ticket_events_ticket
    ON public.maintenance_ticket_events (ticket_id, created_at);

-- Chỉ backend (table owner) truy cập; chặn truy cập trực tiếp qua Supabase API.
ALTER TABLE public.maintenance_tickets ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.maintenance_ticket_devices ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.maintenance_availability_slots ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.maintenance_quote_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.maintenance_ticket_events ENABLE ROW LEVEL SECURITY;
