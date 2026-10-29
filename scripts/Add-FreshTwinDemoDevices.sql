-- Local-only, repeatable device palette for fresh@hesta.local.
-- The account must already own exactly one home. No room, hardware pairing,
-- runtime reading, or Twin layout is created or changed by this script.

BEGIN;

DO $$
DECLARE
    target_home UUID;
    owned_home_count INTEGER;
BEGIN
    SELECT COUNT(*), MIN(h.id::text)::UUID
    INTO owned_home_count, target_home
    FROM public.users u
    JOIN public.home_members hm ON hm.user_id = u.id
    JOIN public.homes h ON h.id = hm.home_id
    WHERE u.email = 'fresh@hesta.local'
      AND hm.role = 'OWNER'
      AND hm.status = 'ACTIVE';

    IF owned_home_count <> 1 THEN
        RAISE EXCEPTION 'fresh@hesta.local must own exactly one active home; found %', owned_home_count;
    END IF;

    IF EXISTS (
        SELECT 1 FROM public.devices
        WHERE id IN (
            'f0000000-0000-4000-8000-000000000101',
            'f0000000-0000-4000-8000-000000000102',
            'f0000000-0000-4000-8000-000000000103',
            'f0000000-0000-4000-8000-000000000104',
            'f0000000-0000-4000-8000-000000000105',
            'f0000000-0000-4000-8000-000000000106',
            'f0000000-0000-4000-8000-000000000107',
            'f0000000-0000-4000-8000-000000000108',
            'f0000000-0000-4000-8000-000000000109'
        ) AND home_id <> target_home
    ) THEN
        RAISE EXCEPTION 'A fresh demo device ID already belongs to another home';
    END IF;

    INSERT INTO public.devices (
        id, home_id, room_id, node_id, name, device_type, status,
        current_state, capabilities, last_seen, is_deleted
    ) VALUES
        ('f0000000-0000-4000-8000-000000000101', target_home, NULL, NULL,
         'Đèn trần mẫu', 'LIGHT', 'UNKNOWN', '{}'::jsonb, '[]'::jsonb, NULL, false),
        ('f0000000-0000-4000-8000-000000000102', target_home, NULL, NULL,
         'Đèn ngủ mẫu', 'LIGHT', 'UNKNOWN', '{}'::jsonb, '[]'::jsonb, NULL, false),
        ('f0000000-0000-4000-8000-000000000103', target_home, NULL, NULL,
         'Máy lạnh mẫu', 'AC', 'UNKNOWN', '{}'::jsonb, '[]'::jsonb, NULL, false),
        ('f0000000-0000-4000-8000-000000000104', target_home, NULL, NULL,
         'Quạt mẫu', 'FAN', 'UNKNOWN', '{}'::jsonb, '[]'::jsonb, NULL, false),
        ('f0000000-0000-4000-8000-000000000105', target_home, NULL, NULL,
         'Ổ cắm mẫu', 'SOCKET', 'UNKNOWN', '{}'::jsonb, '[]'::jsonb, NULL, false),
        ('f0000000-0000-4000-8000-000000000106', target_home, NULL, NULL,
         'Cảm biến nhiệt độ mẫu', 'SENSOR', 'UNKNOWN', '{}'::jsonb, '[]'::jsonb, NULL, false),
        ('f0000000-0000-4000-8000-000000000107', target_home, NULL, NULL,
         'Cảm biến chuyển động mẫu', 'SENSOR', 'UNKNOWN', '{}'::jsonb, '[]'::jsonb, NULL, false),
        ('f0000000-0000-4000-8000-000000000108', target_home, NULL, NULL,
         'Khóa cửa mẫu', 'LOCK', 'UNKNOWN', '{}'::jsonb, '[]'::jsonb, NULL, false),
        ('f0000000-0000-4000-8000-000000000109', target_home, NULL, NULL,
         'Camera mẫu', 'CAMERA', 'UNKNOWN', '{}'::jsonb, '[]'::jsonb, NULL, false)
    ON CONFLICT (id) DO NOTHING;
END $$;

COMMIT;
