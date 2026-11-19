-- Verification Script: Test Partial UNIQUE index and constraints on device_anomalies
-- Run manually on PostgreSQL/Supabase to verify unique active constraint and deduplication.

BEGIN;

DO $$
DECLARE
    v_home_id UUID;
    v_device_id UUID;
    v_first_anomaly_id UUID;
    v_second_anomaly_id UUID;
    v_error_caught BOOLEAN := false;
BEGIN
    -- 1. Get an existing home and device for testing
    SELECT id INTO v_home_id FROM homes LIMIT 1;
    IF v_home_id IS NULL THEN
        RAISE NOTICE 'No home found in database. Skipping test.';
        RETURN;
    END IF;

    SELECT id INTO v_device_id FROM devices WHERE room_id IS NOT NULL LIMIT 1;
    IF v_device_id IS NULL THEN
        RAISE NOTICE 'No device found in database. Skipping test.';
        RETURN;
    END IF;

    RAISE NOTICE 'Testing constraints with home=% and device=%', v_home_id, v_device_id;

    -- 2. Insert first active anomaly (status = OPEN)
    INSERT INTO device_anomalies (
        home_id, device_id, anomaly_type, metric, current_value, expected_range,
        severity, status, source, occurrence_count
    ) VALUES (
        v_home_id, v_device_id, 'GAS_LEAKAGE', 'gas_ppm', '1250 ppm', '< 400 ppm',
        'CRITICAL', 'OPEN', 'RULE', 1
    ) RETURNING id INTO v_first_anomaly_id;

    RAISE NOTICE 'First active anomaly inserted: id=%', v_first_anomaly_id;

    -- 3. Attempt to insert second active anomaly for SAME device_id and anomaly_type (Must FAIL)
    BEGIN
        INSERT INTO device_anomalies (
            home_id, device_id, anomaly_type, metric, current_value, expected_range,
            severity, status, source, occurrence_count
        ) VALUES (
            v_home_id, v_device_id, 'GAS_LEAKAGE', 'gas_ppm', '1300 ppm', '< 400 ppm',
            'CRITICAL', 'OPEN', 'RULE', 1
        ) RETURNING id INTO v_second_anomaly_id;
    EXCEPTION WHEN unique_violation THEN
        v_error_caught := true;
        RAISE NOTICE 'SUCCESS: Unique active anomaly constraint caught duplicate insertion!';
    END;

    IF NOT v_error_caught THEN
        RAISE EXCEPTION 'TEST FAILED: Duplicate active anomaly was unexpectedly allowed!';
    END IF;

    -- 4. Resolve the first anomaly
    UPDATE device_anomalies
    SET status = 'RESOLVED', resolved_at = now(), resolution_note = 'Auto-resolved hysteresis'
    WHERE id = v_first_anomaly_id;

    RAISE NOTICE 'First anomaly marked as RESOLVED.';

    -- 5. Now inserting a new active anomaly for the same (device_id, anomaly_type) MUST SUCCEED
    INSERT INTO device_anomalies (
        home_id, device_id, anomaly_type, metric, current_value, expected_range,
        severity, status, source, occurrence_count
    ) VALUES (
        v_home_id, v_device_id, 'GAS_LEAKAGE', 'gas_ppm', '1400 ppm', '< 400 ppm',
        'CRITICAL', 'OPEN', 'RULE', 1
    ) RETURNING id INTO v_second_anomaly_id;

    RAISE NOTICE 'SUCCESS: New active anomaly inserted after resolving previous one: id=%', v_second_anomaly_id;
    RAISE NOTICE 'All constraint tests passed successfully!';
END $$;

-- Rollback transaction so test data is not persisted
ROLLBACK;
