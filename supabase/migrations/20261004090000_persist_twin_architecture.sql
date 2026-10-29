-- Additive contract: geometry shares the existing layout revision and OWNER transaction.
ALTER TABLE public.twin_layouts ADD COLUMN architecture jsonb;
ALTER TABLE public.twin_layouts ADD CONSTRAINT twin_architecture_version
    CHECK (architecture IS NULL OR COALESCE((jsonb_typeof(architecture) = 'object'
        AND architecture ->> 'version' = '1' AND jsonb_typeof(architecture -> 'rooms') = 'object'), false));
COMMENT ON COLUMN public.twin_layouts.architecture IS
    'Versioned floor/room outline, walls/openings/furniture and node rotations. No runtime device state or browser settings.';
