-- ============================================================================
-- HESTA local demo data
--
-- This file is executed by `npx supabase db reset` and may also be applied
-- directly to the local PostgreSQL container. It contains no production data
-- and no reusable plaintext credential.
--
-- LOCAL accounts receive an intentionally unknown random BCrypt password here.
-- Run `scripts/Initialize-LocalDemo.ps1` to choose one password interactively.
-- ============================================================================

BEGIN;

-- --------------------------------------------------------------------------
-- Users
-- --------------------------------------------------------------------------
INSERT INTO public.users (
    id, full_name, email, password_hash, phone_number, provider,
    platform_role, status, failed_login_attempts, last_active_at,
    created_at, updated_at
) VALUES
    ('00000000-0000-4000-8000-000000000101', 'Nguyễn Minh Anh', 'owner@hesta.local',
     crypt(gen_random_uuid()::text, gen_salt('bf', 10)), '0900000001', 'LOCAL',
     'USER', 'ACTIVE', 0, now() - interval '8 minutes', now() - interval '120 days', now()),
    ('00000000-0000-4000-8000-000000000102', 'Trần Quốc Bình', 'member@hesta.local',
     crypt(gen_random_uuid()::text, gen_salt('bf', 10)), '0900000002', 'LOCAL',
     'USER', 'ACTIVE', 0, now() - interval '25 minutes', now() - interval '90 days', now()),
    ('00000000-0000-4000-8000-000000000103', 'Lê Thu Hà', 'guest@hesta.local',
     crypt(gen_random_uuid()::text, gen_salt('bf', 10)), '0900000003', 'LOCAL',
     'USER', 'ACTIVE', 0, now() - interval '2 hours', now() - interval '60 days', now()),
    ('00000000-0000-4000-8000-000000000104', 'Phạm Gia Huy', 'second.owner@hesta.local',
     crypt(gen_random_uuid()::text, gen_salt('bf', 10)), '0900000004', 'LOCAL',
     'USER', 'ACTIVE', 0, now() - interval '1 day', now() - interval '45 days', now()),
    ('00000000-0000-4000-8000-000000000105', 'HESTA Local Admin', 'admin@hesta.local',
     crypt(gen_random_uuid()::text, gen_salt('bf', 10)), NULL, 'LOCAL',
     'ADMIN', 'ACTIVE', 0, now() - interval '3 days', now() - interval '30 days', now())
ON CONFLICT DO NOTHING;

-- --------------------------------------------------------------------------
-- Homes and memberships
-- --------------------------------------------------------------------------
INSERT INTO public.homes (id, name, address, created_by, created_at, updated_at) VALUES
    ('00000000-0000-4000-8000-000000000201', 'Nhà HESTA Demo',
     '123 Đường Công Nghệ, TP. Hồ Chí Minh',
     '00000000-0000-4000-8000-000000000101', now() - interval '110 days', now()),
    ('00000000-0000-4000-8000-000000000202', 'Căn hộ Gia Huy',
     '88 Đường Bình Minh, TP. Hồ Chí Minh',
     '00000000-0000-4000-8000-000000000104', now() - interval '40 days', now())
ON CONFLICT DO NOTHING;

INSERT INTO public.home_members (
    id, home_id, user_id, role, status,
    allow_voice_override, allow_scene_creation, allow_remote_control,
    invited_by, joined_at
) VALUES
    ('00000000-0000-4000-8000-000000000301',
     '00000000-0000-4000-8000-000000000201',
     '00000000-0000-4000-8000-000000000101',
     'OWNER', 'ACTIVE', true, true, true, NULL, now() - interval '110 days'),
    ('00000000-0000-4000-8000-000000000302',
     '00000000-0000-4000-8000-000000000201',
     '00000000-0000-4000-8000-000000000102',
     'MEMBER', 'ACTIVE', true, true, false,
     '00000000-0000-4000-8000-000000000101', now() - interval '80 days'),
    ('00000000-0000-4000-8000-000000000303',
     '00000000-0000-4000-8000-000000000201',
     '00000000-0000-4000-8000-000000000103',
     'MEMBER', 'ACTIVE', false, false, false,
     '00000000-0000-4000-8000-000000000101', now() - interval '50 days'),
    ('00000000-0000-4000-8000-000000000304',
     '00000000-0000-4000-8000-000000000202',
     '00000000-0000-4000-8000-000000000104',
     'OWNER', 'ACTIVE', true, true, true, NULL, now() - interval '40 days')
ON CONFLICT DO NOTHING;

-- --------------------------------------------------------------------------
-- Rooms and room-level member access
-- --------------------------------------------------------------------------
INSERT INTO public.rooms (
    id, home_id, name, layout_x, layout_y, icon, created_at
) VALUES
    ('00000000-0000-4000-8000-000000000401',
     '00000000-0000-4000-8000-000000000201', 'Phòng khách', 1.000, 1.000, 'sofa', now() - interval '100 days'),
    ('00000000-0000-4000-8000-000000000402',
     '00000000-0000-4000-8000-000000000201', 'Phòng ngủ', 5.000, 1.000, 'bed', now() - interval '100 days'),
    ('00000000-0000-4000-8000-000000000403',
     '00000000-0000-4000-8000-000000000201', 'Nhà bếp', 1.000, 5.000, 'cooking', now() - interval '100 days'),
    ('00000000-0000-4000-8000-000000000404',
     '00000000-0000-4000-8000-000000000201', 'Cửa chính', 0.000, 3.000, 'door', now() - interval '100 days'),
    ('00000000-0000-4000-8000-000000000405',
     '00000000-0000-4000-8000-000000000202', 'Studio', 1.000, 1.000, 'apartment', now() - interval '35 days')
ON CONFLICT DO NOTHING;

INSERT INTO public.home_member_room_access (home_member_id, room_id) VALUES
    ('00000000-0000-4000-8000-000000000302', '00000000-0000-4000-8000-000000000401'),
    ('00000000-0000-4000-8000-000000000302', '00000000-0000-4000-8000-000000000403'),
    ('00000000-0000-4000-8000-000000000303', '00000000-0000-4000-8000-000000000401'),
    ('00000000-0000-4000-8000-000000000303', '00000000-0000-4000-8000-000000000402')
ON CONFLICT DO NOTHING;

INSERT INTO public.user_preferences (
    user_id, preferred_temperature, preferred_brightness, default_room_id,
    theme, language, voice_feedback_enabled,
    notify_security, notify_automation, notify_system, updated_at
) VALUES
    ('00000000-0000-4000-8000-000000000101', 25.0, 80,
     '00000000-0000-4000-8000-000000000401', 'dark', 'vi', true, true, true, true, now()),
    ('00000000-0000-4000-8000-000000000102', 24.0, 70,
     '00000000-0000-4000-8000-000000000403', 'light', 'vi', true, true, true, true, now()),
    ('00000000-0000-4000-8000-000000000103', 26.0, 60,
     '00000000-0000-4000-8000-000000000402', 'system', 'en', false, true, false, true, now()),
    ('00000000-0000-4000-8000-000000000104', 24.5, 75,
     '00000000-0000-4000-8000-000000000405', 'dark', 'vi', true, true, true, true, now()),
    ('00000000-0000-4000-8000-000000000105', 25.0, 80,
     NULL, 'system', 'vi', true, true, true, true, now())
ON CONFLICT DO NOTHING;

-- --------------------------------------------------------------------------
-- Edge nodes and devices
-- --------------------------------------------------------------------------
INSERT INTO public.edge_nodes (
    id, home_id, node_code, mac_address, ip_address,
    firmware_version, status, last_ack_at, paired_at
) VALUES
    ('00000000-0000-4000-8000-000000000501',
     '00000000-0000-4000-8000-000000000201', 'HESTA-HUB-LIVING',
     '02:00:00:00:05:01', '192.168.1.20', '1.4.2', 'ONLINE', now() - interval '20 seconds', now() - interval '95 days'),
    ('00000000-0000-4000-8000-000000000502',
     '00000000-0000-4000-8000-000000000201', 'HESTA-HUB-BEDROOM',
     '02:00:00:00:05:02', '192.168.1.21', '1.4.2', 'ONLINE', now() - interval '35 seconds', now() - interval '92 days'),
    ('00000000-0000-4000-8000-000000000503',
     '00000000-0000-4000-8000-000000000202', 'HESTA-HUB-STUDIO',
     '02:00:00:00:05:03', '192.168.2.20', '1.3.9', 'OFFLINE', now() - interval '2 hours', now() - interval '30 days')
ON CONFLICT DO NOTHING;

INSERT INTO public.devices (
    id, home_id, room_id, node_id, name, device_type, gpio_pin,
    mqtt_topic, status, current_state, capabilities, icon,
    digital_twin_x, digital_twin_y, digital_twin_z,
    last_seen, created_at, updated_at, is_deleted
) VALUES
    ('00000000-0000-4000-8000-000000000601',
     '00000000-0000-4000-8000-000000000201', '00000000-0000-4000-8000-000000000401',
     '00000000-0000-4000-8000-000000000501', 'Đèn trần phòng khách', 'LIGHT', 2,
     'hesta/demo/living/light', 'ONLINE', '{"power":"ON","brightness":80,"colorTemperature":4200}',
     '["power","brightness","colorTemperature"]', 'lightbulb', 2.000, 1.000, 2.600,
     now() - interval '15 seconds', now() - interval '90 days', now() - interval '5 minutes', false),
    ('00000000-0000-4000-8000-000000000602',
     '00000000-0000-4000-8000-000000000201', '00000000-0000-4000-8000-000000000401',
     '00000000-0000-4000-8000-000000000501', 'Máy lạnh phòng khách', 'AC', 3,
     'hesta/demo/living/ac', 'ONLINE', '{"power":"ON","temperature":25,"mode":"COOL","fanSpeed":2}',
     '["power","temperature","mode","fanSpeed"]', 'air-conditioner', 4.000, 1.000, 2.100,
     now() - interval '25 seconds', now() - interval '88 days', now() - interval '12 minutes', false),
    ('00000000-0000-4000-8000-000000000603',
     '00000000-0000-4000-8000-000000000201', '00000000-0000-4000-8000-000000000404',
     '00000000-0000-4000-8000-000000000501', 'Cảm biến chuyển động cửa chính', 'SENSOR', 4,
     'hesta/demo/entrance/motion', 'ONLINE', '{"motion":true,"battery":87}',
     '["motion","battery"]', 'motion-sensor', 0.500, 3.000, 2.000,
     now() - interval '10 seconds', now() - interval '85 days', now() - interval '1 minute', false),
    ('00000000-0000-4000-8000-000000000604',
     '00000000-0000-4000-8000-000000000201', '00000000-0000-4000-8000-000000000404',
     '00000000-0000-4000-8000-000000000501', 'Khóa cửa chính', 'LOCK', 5,
     'hesta/demo/entrance/lock', 'ONLINE', '{"locked":true,"battery":74}',
     '["locked","battery"]', 'door-lock', 0.200, 3.000, 1.100,
     now() - interval '18 seconds', now() - interval '84 days', now() - interval '20 minutes', false),
    ('00000000-0000-4000-8000-000000000605',
     '00000000-0000-4000-8000-000000000201', '00000000-0000-4000-8000-000000000402',
     '00000000-0000-4000-8000-000000000502', 'Cảm biến nhiệt độ phòng ngủ', 'SENSOR', 6,
     'hesta/demo/bedroom/environment', 'ONLINE', '{"temperature":26.4,"humidity":61.0,"battery":92}',
     '["temperature","humidity","battery"]', 'thermometer', 6.000, 1.500, 1.500,
     now() - interval '30 seconds', now() - interval '82 days', now() - interval '3 minutes', false),
    ('00000000-0000-4000-8000-000000000606',
     '00000000-0000-4000-8000-000000000201', '00000000-0000-4000-8000-000000000403',
     '00000000-0000-4000-8000-000000000501', 'Quạt thông gió nhà bếp', 'FAN', 7,
     'hesta/demo/kitchen/fan', 'OFFLINE', '{"power":"OFF","speed":0}',
     '["power","speed"]', 'fan', 2.000, 5.000, 2.000,
     now() - interval '3 hours', now() - interval '75 days', now() - interval '3 hours', false),
    ('00000000-0000-4000-8000-000000000607',
     '00000000-0000-4000-8000-000000000201', '00000000-0000-4000-8000-000000000402',
     '00000000-0000-4000-8000-000000000502', 'Đèn ngủ', 'LIGHT', 8,
     'hesta/demo/bedroom/lamp', 'ONLINE', '{"power":"OFF","brightness":30}',
     '["power","brightness"]', 'bedside-lamp', 6.500, 1.200, 0.800,
     now() - interval '20 seconds', now() - interval '70 days', now() - interval '35 minutes', false),
    ('00000000-0000-4000-8000-000000000608',
     '00000000-0000-4000-8000-000000000202', '00000000-0000-4000-8000-000000000405',
     '00000000-0000-4000-8000-000000000503', 'Camera căn hộ', 'CAMERA', 9,
     'hesta/demo/studio/camera', 'ERROR', '{"recording":false,"connection":"LOST"}',
     '["recording","connection"]', 'camera', 2.000, 1.000, 2.200,
     now() - interval '2 hours', now() - interval '25 days', now() - interval '2 hours', false)
ON CONFLICT DO NOTHING;

-- --------------------------------------------------------------------------
-- Device history and sensor readings
-- --------------------------------------------------------------------------
INSERT INTO public.device_state_history (
    id, device_id, previous_state, new_state, source,
    changed_by, is_test, changed_at
) VALUES
    ('00000000-0000-4000-8000-000000000701',
     '00000000-0000-4000-8000-000000000601', '{"power":"OFF","brightness":0}',
     '{"power":"ON","brightness":80,"colorTemperature":4200}', 'MANUAL',
     '00000000-0000-4000-8000-000000000101', false, now() - interval '5 hours'),
    ('00000000-0000-4000-8000-000000000702',
     '00000000-0000-4000-8000-000000000602', '{"power":"OFF","temperature":27}',
     '{"power":"ON","temperature":25,"mode":"COOL","fanSpeed":2}', 'VOICE',
     '00000000-0000-4000-8000-000000000102', false, now() - interval '3 hours'),
    ('00000000-0000-4000-8000-000000000703',
     '00000000-0000-4000-8000-000000000604', '{"locked":false,"battery":74}',
     '{"locked":true,"battery":74}', 'AUTOMATION',
     NULL, false, now() - interval '2 hours'),
    ('00000000-0000-4000-8000-000000000704',
     '00000000-0000-4000-8000-000000000607', '{"power":"ON","brightness":30}',
     '{"power":"OFF","brightness":30}', 'SCENE',
     '00000000-0000-4000-8000-000000000101', false, now() - interval '45 minutes'),
    ('00000000-0000-4000-8000-000000000705',
     '00000000-0000-4000-8000-000000000606', '{"power":"ON","speed":2}',
     '{"power":"OFF","speed":0}', 'MANUAL',
     '00000000-0000-4000-8000-000000000102', false, now() - interval '3 hours')
ON CONFLICT DO NOTHING;

INSERT INTO public.sensor_readings (
    id, device_id, metric_type, value, unit, recorded_at
) VALUES
    (900001, '00000000-0000-4000-8000-000000000605', 'TEMPERATURE', 25.800, '°C', now() - interval '2 hours'),
    (900002, '00000000-0000-4000-8000-000000000605', 'TEMPERATURE', 26.100, '°C', now() - interval '1 hour'),
    (900003, '00000000-0000-4000-8000-000000000605', 'TEMPERATURE', 26.400, '°C', now() - interval '5 minutes'),
    (900004, '00000000-0000-4000-8000-000000000605', 'HUMIDITY', 61.000, '%', now() - interval '5 minutes'),
    (900005, '00000000-0000-4000-8000-000000000603', 'MOTION', 1.000, 'boolean', now() - interval '1 minute')
ON CONFLICT DO NOTHING;

-- --------------------------------------------------------------------------
-- Scenes and automation
-- --------------------------------------------------------------------------
INSERT INTO public.scenes (id, home_id, name, icon, created_by, created_at, updated_at) VALUES
    ('00000000-0000-4000-8000-000000000801',
     '00000000-0000-4000-8000-000000000201', 'Chế độ ngủ', 'moon',
     '00000000-0000-4000-8000-000000000101', now() - interval '30 days', now()),
    ('00000000-0000-4000-8000-000000000802',
     '00000000-0000-4000-8000-000000000201', 'Chào mừng về nhà', 'home',
     '00000000-0000-4000-8000-000000000102', now() - interval '20 days', now())
ON CONFLICT DO NOTHING;

INSERT INTO public.scene_actions (
    id, scene_id, device_id, action, target_state, order_index
) VALUES
    ('00000000-0000-4000-8000-000000000811',
     '00000000-0000-4000-8000-000000000801', '00000000-0000-4000-8000-000000000601',
     'SET_STATE', '{"power":"OFF"}', 1),
    ('00000000-0000-4000-8000-000000000812',
     '00000000-0000-4000-8000-000000000801', '00000000-0000-4000-8000-000000000604',
     'SET_STATE', '{"locked":true}', 2),
    ('00000000-0000-4000-8000-000000000813',
     '00000000-0000-4000-8000-000000000802', '00000000-0000-4000-8000-000000000601',
     'SET_STATE', '{"power":"ON","brightness":80}', 1),
    ('00000000-0000-4000-8000-000000000814',
     '00000000-0000-4000-8000-000000000802', '00000000-0000-4000-8000-000000000602',
     'SET_STATE', '{"power":"ON","temperature":25}', 2)
ON CONFLICT (id) DO NOTHING;

INSERT INTO public.scene_schedules (
    id, scene_id, scheduled_time, repeat_days, is_active, created_at
) VALUES
    ('00000000-0000-4000-8000-000000000821',
     '00000000-0000-4000-8000-000000000801', '23:00:00', ARRAY[1,2,3,4,5,6,7]::SMALLINT[], true, now())
ON CONFLICT DO NOTHING;

INSERT INTO public.automation_rules (
    id, home_id, name, trigger_type, enabled, created_by, created_at, updated_at
) VALUES
    ('00000000-0000-4000-8000-000000000901',
     '00000000-0000-4000-8000-000000000201', 'Làm mát khi phòng nóng',
     'SENSOR', true, '00000000-0000-4000-8000-000000000101', now() - interval '15 days', now())
ON CONFLICT DO NOTHING;

INSERT INTO public.rule_conditions (
    id, rule_id, device_id, operator, value, logical_group, order_index
) VALUES
    ('00000000-0000-4000-8000-000000000911',
     '00000000-0000-4000-8000-000000000901', '00000000-0000-4000-8000-000000000605',
     '>', '28', 'AND', 1)
ON CONFLICT DO NOTHING;

INSERT INTO public.rule_actions (
    id, rule_id, device_id, scene_id, action_command, parameters, order_index
) VALUES
    ('00000000-0000-4000-8000-000000000921',
     '00000000-0000-4000-8000-000000000901', '00000000-0000-4000-8000-000000000602', NULL,
     'TURN_ON', '{"temperature":25,"mode":"COOL"}', 1)
ON CONFLICT DO NOTHING;

-- --------------------------------------------------------------------------
-- Security, anomaly, and Notification Core examples
-- --------------------------------------------------------------------------
INSERT INTO public.security_events (
    id, home_id, device_id, event_type, location, severity, status, detected_at
) VALUES
    ('00000000-0000-4000-8000-000000001001',
     '00000000-0000-4000-8000-000000000201', '00000000-0000-4000-8000-000000000603',
     'MOTION', 'Cửa chính', 'HIGH', 'DETECTED', now() - interval '1 minute')
ON CONFLICT DO NOTHING;

INSERT INTO public.device_anomalies (
    id, home_id, device_id, anomaly_type, current_value,
    expected_range, severity, status, detected_at
) VALUES
    ('00000000-0000-4000-8000-000000001002',
     '00000000-0000-4000-8000-000000000202', '00000000-0000-4000-8000-000000000608',
     'CONNECTION_LOST', 'OFFLINE_2_HOURS', 'ONLINE', 'HIGH', 'OPEN', now() - interval '2 hours')
ON CONFLICT DO NOTHING;

INSERT INTO public.notifications (
    id, recipient_id, home_id, type, title, message, priority_level,
    source_security_event_id, source_anomaly_id,
    is_read, delivery_status, created_at
) VALUES
    ('00000000-0000-4000-8000-000000001201',
     '00000000-0000-4000-8000-000000000101', '00000000-0000-4000-8000-000000000201',
     'SECURITY', 'Phát hiện chuyển động', 'Có chuyển động tại cửa chính lúc bạn vắng nhà.', 'HIGH',
     '00000000-0000-4000-8000-000000001001', NULL, false, 'SENT', now() - interval '1 minute'),
    ('00000000-0000-4000-8000-000000001202',
     '00000000-0000-4000-8000-000000000101', '00000000-0000-4000-8000-000000000201',
     'AUTOMATION', 'Automation đã chạy', 'Máy lạnh được bật để giữ nhiệt độ phòng dễ chịu.', 'MEDIUM',
     NULL, NULL, false, 'SENT', now() - interval '30 minutes'),
    ('00000000-0000-4000-8000-000000001203',
     '00000000-0000-4000-8000-000000000101', '00000000-0000-4000-8000-000000000201',
     'SYSTEM', 'HESTA đã sẵn sàng', 'Tất cả dịch vụ trong Nhà HESTA Demo đang hoạt động.', 'LOW',
     NULL, NULL, true, 'SENT', now() - interval '1 day'),
    ('00000000-0000-4000-8000-000000001204',
     '00000000-0000-4000-8000-000000000102', '00000000-0000-4000-8000-000000000201',
     'DEVICE', 'Quạt nhà bếp mất kết nối', 'Quạt thông gió nhà bếp đã offline hơn 3 giờ.', 'MEDIUM',
     NULL, NULL, false, 'SENT', now() - interval '3 hours'),
    ('00000000-0000-4000-8000-000000001205',
     '00000000-0000-4000-8000-000000000102', '00000000-0000-4000-8000-000000000201',
     'SECURITY', 'Khóa cửa đã đóng', 'Cửa chính đã được khóa tự động.', 'LOW',
     NULL, NULL, true, 'SENT', now() - interval '2 hours'),
    ('00000000-0000-4000-8000-000000001206',
     '00000000-0000-4000-8000-000000000103', '00000000-0000-4000-8000-000000000201',
     'SYSTEM', 'Bạn đã được thêm vào nhà', 'Bạn có quyền truy cập Phòng khách và Phòng ngủ.', 'LOW',
     NULL, NULL, false, 'SENT', now() - interval '12 hours'),
    ('00000000-0000-4000-8000-000000001207',
     '00000000-0000-4000-8000-000000000104', '00000000-0000-4000-8000-000000000202',
     'ANOMALY', 'Camera mất kết nối', 'Camera căn hộ đã mất kết nối trong 2 giờ.', 'HIGH',
     NULL, '00000000-0000-4000-8000-000000001002', false, 'SENT', now() - interval '2 hours'),
    ('00000000-0000-4000-8000-000000001208',
     '00000000-0000-4000-8000-000000000105', NULL,
     'SYSTEM', 'Tài khoản quản trị local', 'Tài khoản này chỉ dùng để kiểm thử chức năng ADMIN.', 'LOW',
     NULL, NULL, false, 'SENT', now() - interval '1 day')
ON CONFLICT DO NOTHING;

-- --------------------------------------------------------------------------
-- Refresh localized demo text
--
-- These updates keep the seed idempotent while also repairing demo rows that
-- may previously have been piped through Windows PowerShell using ASCII.
-- --------------------------------------------------------------------------
UPDATE public.users AS target
SET full_name = source.full_name
FROM (VALUES
    ('00000000-0000-4000-8000-000000000101'::UUID, 'Nguyễn Minh Anh'),
    ('00000000-0000-4000-8000-000000000102'::UUID, 'Trần Quốc Bình'),
    ('00000000-0000-4000-8000-000000000103'::UUID, 'Lê Thu Hà'),
    ('00000000-0000-4000-8000-000000000104'::UUID, 'Phạm Gia Huy')
) AS source(id, full_name)
WHERE target.id = source.id;

UPDATE public.homes AS target
SET name = source.name,
    address = source.address
FROM (VALUES
    ('00000000-0000-4000-8000-000000000201'::UUID, 'Nhà HESTA Demo',
     '123 Đường Công Nghệ, TP. Hồ Chí Minh'),
    ('00000000-0000-4000-8000-000000000202'::UUID, 'Căn hộ Gia Huy',
     '88 Đường Bình Minh, TP. Hồ Chí Minh')
) AS source(id, name, address)
WHERE target.id = source.id;

UPDATE public.rooms AS target
SET name = source.name
FROM (VALUES
    ('00000000-0000-4000-8000-000000000401'::UUID, 'Phòng khách'),
    ('00000000-0000-4000-8000-000000000402'::UUID, 'Phòng ngủ'),
    ('00000000-0000-4000-8000-000000000403'::UUID, 'Nhà bếp'),
    ('00000000-0000-4000-8000-000000000404'::UUID, 'Cửa chính')
) AS source(id, name)
WHERE target.id = source.id;

UPDATE public.devices AS target
SET name = source.name
FROM (VALUES
    ('00000000-0000-4000-8000-000000000601'::UUID, 'Đèn trần phòng khách'),
    ('00000000-0000-4000-8000-000000000602'::UUID, 'Máy lạnh phòng khách'),
    ('00000000-0000-4000-8000-000000000603'::UUID, 'Cảm biến chuyển động cửa chính'),
    ('00000000-0000-4000-8000-000000000604'::UUID, 'Khóa cửa chính'),
    ('00000000-0000-4000-8000-000000000605'::UUID, 'Cảm biến nhiệt độ phòng ngủ'),
    ('00000000-0000-4000-8000-000000000606'::UUID, 'Quạt thông gió nhà bếp'),
    ('00000000-0000-4000-8000-000000000607'::UUID, 'Đèn ngủ'),
    ('00000000-0000-4000-8000-000000000608'::UUID, 'Camera căn hộ')
) AS source(id, name)
WHERE target.id = source.id;

UPDATE public.sensor_readings
SET unit = '°C'
WHERE id IN (900001, 900002, 900003);

UPDATE public.scenes AS target
SET name = source.name
FROM (VALUES
    ('00000000-0000-4000-8000-000000000801'::UUID, 'Chế độ ngủ'),
    ('00000000-0000-4000-8000-000000000802'::UUID, 'Chào mừng về nhà')
) AS source(id, name)
WHERE target.id = source.id;

UPDATE public.automation_rules
SET name = 'Làm mát khi phòng nóng'
WHERE id = '00000000-0000-4000-8000-000000000901';

UPDATE public.security_events
SET location = 'Cửa chính'
WHERE id = '00000000-0000-4000-8000-000000001001';

UPDATE public.notifications AS target
SET title = source.title,
    message = source.message
FROM (VALUES
    ('00000000-0000-4000-8000-000000001201'::UUID,
     'Phát hiện chuyển động', 'Có chuyển động tại cửa chính lúc bạn vắng nhà.'),
    ('00000000-0000-4000-8000-000000001202'::UUID,
     'Automation đã chạy', 'Máy lạnh được bật để giữ nhiệt độ phòng dễ chịu.'),
    ('00000000-0000-4000-8000-000000001203'::UUID,
     'HESTA đã sẵn sàng', 'Tất cả dịch vụ trong Nhà HESTA Demo đang hoạt động.'),
    ('00000000-0000-4000-8000-000000001204'::UUID,
     'Quạt nhà bếp mất kết nối', 'Quạt thông gió nhà bếp đã offline hơn 3 giờ.'),
    ('00000000-0000-4000-8000-000000001205'::UUID,
     'Khóa cửa đã đóng', 'Cửa chính đã được khóa tự động.'),
    ('00000000-0000-4000-8000-000000001206'::UUID,
     'Bạn đã được thêm vào nhà', 'Bạn có quyền truy cập Phòng khách và Phòng ngủ.'),
    ('00000000-0000-4000-8000-000000001207'::UUID,
     'Camera mất kết nối', 'Camera căn hộ đã mất kết nối trong 2 giờ.'),
    ('00000000-0000-4000-8000-000000001208'::UUID,
     'Tài khoản quản trị local', 'Tài khoản này chỉ dùng để kiểm thử chức năng ADMIN.')
) AS source(id, title, message)
WHERE target.id = source.id;

COMMIT;
