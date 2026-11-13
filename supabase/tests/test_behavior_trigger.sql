-- ============================================================================
-- Test Script: test_behavior_trigger.sql
-- Purpose: Verify trigger `enforce_behavior_location_same_home` on behavior_events
--
-- Expected Results:
--   CASE 1 (Same Home):
--     Device and event have the same home_id (via room.home_id).
--     Expected: INSERT succeeds without error.
--
--   CASE 2 (Different Home):
--     Device belongs to Home 2, but event is inserted for Home 1.
--     Expected: Trigger aborts transaction with SQLSTATE '23514'
--     Message: 'Behavior event device must belong to the event home'.
--
--   CASE 3 (Device without assigned room):
--     Device has room_id = NULL, but its edge_node belongs to Home 1.
--     Expected: INSERT succeeds (fallback to edge_nodes.home_id matches event home).
-- ============================================================================

DO $$
DECLARE
    u_test UUID := gen_random_uuid();
    h1 UUID := gen_random_uuid();
    h2 UUID := gen_random_uuid();
    r1 UUID := gen_random_uuid();
    r2 UUID := gen_random_uuid();
    node1 UUID := gen_random_uuid();
    node2 UUID := gen_random_uuid();
    d_same UUID := gen_random_uuid();
    d_diff UUID := gen_random_uuid();
    d_no_room UUID := gen_random_uuid();
    ev1 UUID := gen_random_uuid();
    ev2 UUID := gen_random_uuid();
    ev3 UUID := gen_random_uuid();
    caught_expected_err BOOLEAN := false;
BEGIN
    RAISE NOTICE 'Starting test_behavior_trigger.sql...';

    -- 1. Setup test fixture
    INSERT INTO users (id, email, password_hash, full_name, role)
    VALUES (u_test, 'trigger_test@hesta.internal', 'hash', 'Trigger Test User', 'USER');

    INSERT INTO homes (id, name, created_by) VALUES (h1, 'Trigger Test Home 1', u_test);
    INSERT INTO homes (id, name, created_by) VALUES (h2, 'Trigger Test Home 2', u_test);

    INSERT INTO rooms (id, home_id, name) VALUES (r1, h1, 'Test Room 1');
    INSERT INTO rooms (id, home_id, name) VALUES (r2, h2, 'Test Room 2');

    INSERT INTO edge_nodes (id, home_id, node_code, status) VALUES (node1, h1, 'NODE_T1', 'ONLINE');
    INSERT INTO edge_nodes (id, home_id, node_code, status) VALUES (node2, h2, 'NODE_T2', 'ONLINE');

    -- d_same belongs to h1 via r1 and node1
    INSERT INTO devices (id, room_id, node_id, local_id, name, device_type, status)
    VALUES (d_same, r1, node1, 'dev_same_01', 'Same Home Device', 'TEMP_SENSOR', 'ONLINE');

    -- d_diff belongs to h2 via r2 and node2
    INSERT INTO devices (id, room_id, node_id, local_id, name, device_type, status)
    VALUES (d_diff, r2, node2, 'dev_diff_01', 'Diff Home Device', 'GAS_SENSOR', 'ONLINE');

    -- d_no_room has room_id = NULL, but belongs to h1 via node1
    INSERT INTO devices (id, room_id, node_id, local_id, name, device_type, status)
    VALUES (d_no_room, NULL, node1, 'dev_noroom_01', 'No Room Device', 'SMOKE_SENSOR', 'ONLINE');

    -- ========================================================================
    -- CASE 1: Device in same home (h1) -> Should PASS
    -- ========================================================================
    INSERT INTO behavior_events (id, home_id, user_id, device_id, room_id, event_type, action)
    VALUES (ev1, h1, u_test, d_same, r1, 'DEVICE_STATE_CHANGE', 'POWER_ON');
    RAISE NOTICE 'CASE 1 PASSED: Same home insert succeeded';

    -- ========================================================================
    -- CASE 3: Device without assigned room in same home (h1 via node) -> Should PASS
    -- ========================================================================
    INSERT INTO behavior_events (id, home_id, user_id, device_id, room_id, event_type, action)
    VALUES (ev3, h1, u_test, d_no_room, NULL, 'DEVICE_STATE_CHANGE', 'POWER_ON');
    RAISE NOTICE 'CASE 3 PASSED: Device without room insert succeeded via node fallback';

    -- ========================================================================
    -- CASE 2: Device in different home (d_diff in h2, event for h1) -> Should FAIL with 23514
    -- ========================================================================
    BEGIN
        INSERT INTO behavior_events (id, home_id, user_id, device_id, room_id, event_type, action)
        VALUES (ev2, h1, u_test, d_diff, NULL, 'DEVICE_STATE_CHANGE', 'POWER_ON');
        RAISE EXCEPTION 'CASE 2 FAILED: Expected exception 23514 but insert succeeded';
    EXCEPTION WHEN SQLSTATE '23514' THEN
        caught_expected_err := true;
        RAISE NOTICE 'CASE 2 PASSED: Successfully intercepted cross-home mismatch with SQLSTATE 23514';
    END;

    IF NOT caught_expected_err THEN
        RAISE EXCEPTION 'CASE 2 FAILED: SQLSTATE 23514 was not caught';
    END IF;

    -- ========================================================================
    -- Teardown test fixture
    -- ========================================================================
    DELETE FROM behavior_events WHERE id IN (ev1, ev3);
    DELETE FROM devices WHERE id IN (d_same, d_diff, d_no_room);
    DELETE FROM edge_nodes WHERE id IN (node1, node2);
    DELETE FROM rooms WHERE id IN (r1, r2);
    DELETE FROM homes WHERE id IN (h1, h2);
    DELETE FROM users WHERE id = u_test;

    RAISE NOTICE 'ALL 3 TRIGGER TEST CASES PASSED SUCCESSFULLY!';
END $$;
