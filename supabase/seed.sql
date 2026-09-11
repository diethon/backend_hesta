-- Seed Data for Device Domain Feature

INSERT INTO users (id, email, password_hash, full_name, platform_role, account_status)
VALUES 
('11111111-1111-1111-1111-111111111111', 'test_user@hesta.com', '$2a$10$W...', 'Test User', 'USER', 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

INSERT INTO homes (id, name, address, created_by)
VALUES 
('22222222-2222-2222-2222-222222222222', 'Test Home', '123 Smart St.', '11111111-1111-1111-1111-111111111111')
ON CONFLICT (id) DO NOTHING;

INSERT INTO home_members (home_id, user_id, role)
VALUES 
('22222222-2222-2222-2222-222222222222', '11111111-1111-1111-1111-111111111111', 'OWNER')
ON CONFLICT (home_id, user_id) DO NOTHING;

INSERT INTO rooms (id, home_id, name, layout_x, layout_y, icon)
VALUES 
('33333333-3333-3333-3333-333333333333', '22222222-2222-2222-2222-222222222222', 'Living Room', 0, 0, 'sofa')
ON CONFLICT (id) DO NOTHING;

INSERT INTO edge_nodes (id, home_id, node_code, mac_address, status)
VALUES 
('44444444-4444-4444-4444-444444444444', '22222222-2222-2222-2222-222222222222', 'ESP32-LIVING', 'AA:BB:CC:DD:EE:FF', 'ONLINE')
ON CONFLICT (id) DO NOTHING;

INSERT INTO devices (id, home_id, room_id, node_id, name, device_type, gpio_pin, status, current_state)
VALUES 
('55555555-5555-5555-5555-555555555555', '22222222-2222-2222-2222-222222222222', '33333333-3333-3333-3333-333333333333', '44444444-4444-4444-4444-444444444444', 'Main Light', 'LIGHT', 2, 'ONLINE', '{"power": "OFF", "brightness": 0}')
ON CONFLICT (id) DO NOTHING;

INSERT INTO device_state_history (id, device_id, previous_state, new_state, source, changed_by)
VALUES 
('66666666-6666-6666-6666-666666666666', '55555555-5555-5555-5555-555555555555', '{}', '{"power": "OFF", "brightness": 0}', 'MANUAL', '11111111-1111-1111-1111-111111111111')
ON CONFLICT (id) DO NOTHING;
