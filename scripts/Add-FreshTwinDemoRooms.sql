-- Local-only, repeatable rooms for fresh@hesta.local.
-- Rooms are created without Twin coordinates so the owner can design the
-- floor plan in 2D and then view the saved layout in 3D.

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
        SELECT 1 FROM public.rooms
        WHERE id IN (
            'f0000000-0000-4000-8000-000000000201',
            'f0000000-0000-4000-8000-000000000202',
            'f0000000-0000-4000-8000-000000000203',
            'f0000000-0000-4000-8000-000000000204',
            'f0000000-0000-4000-8000-000000000205'
        ) AND home_id <> target_home
    ) THEN
        RAISE EXCEPTION 'A fresh demo room ID already belongs to another home';
    END IF;

    INSERT INTO public.rooms (id, home_id, name, icon)
    VALUES
        ('f0000000-0000-4000-8000-000000000201', target_home, 'Phòng khách', 'sofa'),
        ('f0000000-0000-4000-8000-000000000202', target_home, 'Phòng ngủ', 'bed'),
        ('f0000000-0000-4000-8000-000000000203', target_home, 'Nhà bếp', 'cooking'),
        ('f0000000-0000-4000-8000-000000000204', target_home, 'Phòng tắm', 'bath'),
        ('f0000000-0000-4000-8000-000000000205', target_home, 'Phòng làm việc', 'desk')
    ON CONFLICT (id) DO NOTHING;
END $$;

COMMIT;
