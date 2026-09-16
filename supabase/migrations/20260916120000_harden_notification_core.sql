-- Notification Core hardening on top of the existing notifications and
-- user_preferences tables. This is forward-only and does not recreate data.

ALTER TABLE public.notifications
    ADD CONSTRAINT chk_notifications_type CHECK (
        type IN ('SECURITY', 'AUTOMATION', 'ANOMALY', 'SYSTEM', 'DEVICE')
    ),
    ADD CONSTRAINT chk_notifications_priority CHECK (
        priority_level IN ('HIGH', 'MEDIUM', 'LOW')
    );

-- Supports the common unread inbox query while keeping the existing complete
-- recipient timeline index for all/read notification queries.
CREATE INDEX IF NOT EXISTS idx_notifications_recipient_unread_time
    ON public.notifications (recipient_id, created_at DESC)
    WHERE is_read = false;

-- Supports a recipient's notification list when filtered to one home.
CREATE INDEX IF NOT EXISTS idx_notifications_recipient_home_time
    ON public.notifications (recipient_id, home_id, created_at DESC)
    WHERE home_id IS NOT NULL;

-- BR-NOT-03 in HESTA_DB_DESIGN.md requires security notifications to remain on.
ALTER TABLE public.user_preferences
    ADD CONSTRAINT chk_user_preferences_notify_security CHECK (notify_security = true);
