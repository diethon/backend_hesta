-- Migration: Device Catalog Management & Batch QR Onboarding
-- Description: Creates device_catalogs table and adds catalog_id to devices.

CREATE TABLE IF NOT EXISTS device_catalogs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(150) NOT NULL,
    device_type VARCHAR(50) NOT NULL,
    model_code VARCHAR(100) NOT NULL UNIQUE,
    serial_prefix VARCHAR(50) NOT NULL,
    description TEXT,
    image_url TEXT,
    icon VARCHAR(50),
    default_capabilities JSONB DEFAULT '{}'::jsonb,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_device_catalogs_model_code ON device_catalogs(model_code);
CREATE INDEX IF NOT EXISTS idx_device_catalogs_device_type ON device_catalogs(device_type);

-- Ensure image_url column exists on devices
ALTER TABLE devices ADD COLUMN IF NOT EXISTS image_url TEXT;

-- Add optional catalog_id foreign key on devices
ALTER TABLE devices ADD COLUMN IF NOT EXISTS catalog_id UUID REFERENCES device_catalogs(id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_devices_catalog_id ON devices(catalog_id);

-- Insert default sample device catalogs if not already present
INSERT INTO device_catalogs (name, device_type, model_code, serial_prefix, description, image_url, icon, default_capabilities)
VALUES 
(
    'Đèn LED RGB HESTA Aura',
    'LED_RGB',
    'HESTA-RGB-01',
    'HESTA-RGB-',
    'Bóng đèn LED thông minh 16 triệu màu, hỗ trợ đồng bộ nhịp điệu âm nhạc và điều chỉnh nhiệt độ màu 2700K - 6500K.',
    'https://images.unsplash.com/photo-1550985616-10810253b84d?w=400&auto=format&fit=crop&q=80',
    'lightbulb',
    '{"power": ["ON", "OFF"], "brightness": [0, 100], "color": ["RGB", "HEX"]}'::jsonb
),
(
    'Công Tắc Cảm Ứng HESTA Touch',
    'LIGHT',
    'HESTA-SW-01',
    'HESTA-SW-',
    'Công tắc mặt kính cường lực chống xước 1-4 nút cảm ứng điện dung, phản hồi rung haptic và đèn nền ban đêm dịu mắt.',
    'https://images.unsplash.com/photo-1558002038-1055907df827?w=400&auto=format&fit=crop&q=80',
    'toggle-left',
    '{"power": ["ON", "OFF"]}'::jsonb
),
(
    'Ổ Cắm Thông Minh HESTA PowerPlug',
    'SMART_PLUG',
    'HESTA-PLUG-01',
    'HESTA-PLUG-',
    'Ổ cắm chịu tải 16A (3500W), tích hợp chip đo đếm công suất thực tế theo giờ (kWh) và ngắt quá tải thông minh.',
    'https://images.unsplash.com/photo-1544716278-ca5e3f4abd8c?w=400&auto=format&fit=crop&q=80',
    'plug',
    '{"power": ["ON", "OFF"], "metering": ["power_w", "voltage_v", "current_a"]}'::jsonb
),
(
    'Cảm Biến Nhiệt Ẩm HESTA Climate',
    'TEMP_HUMID_SENSOR',
    'HESTA-TH-01',
    'HESTA-TH-',
    'Cảm biến môi trường độ chính xác cao Thụy Sĩ Sensirion, màn hình E-ink tiết kiệm pin với thời lượng pin 2 năm.',
    'https://images.unsplash.com/photo-1585771724684-38269d6639fd?w=400&auto=format&fit=crop&q=80',
    'thermometer',
    '{"temperature": ["celsius"], "humidity": ["percentage"]}'::jsonb
),
(
    'Cảm Biến Hiện Diện HESTA Radar PIR',
    'MOTION_SENSOR',
    'HESTA-PIR-01',
    'HESTA-PIR-',
    'Cảm biến radar vi sóng 24GHz kết hợp hồng ngoại thụ động, phát hiện người ngồi yên đọc sách hoặc thở nhẹ.',
    'https://images.unsplash.com/photo-1508873696983-2df5293cb32f?w=400&auto=format&fit=crop&q=80',
    'radar',
    '{"motion": ["DETECTED", "CLEAR"], "illuminance": ["lux"]}'::jsonb
),
(
    'Camera Giám Sát AI HESTA Vision Pro',
    'CAMERA_AI',
    'HESTA-CAM-01',
    'HESTA-CAM-',
    'Camera 2K Ultra HD hỗ trợ AI nhận diện khuôn mặt người thân, phát hiện người lạ và phát cảnh báo xâm nhập theo thời gian thực.',
    'https://images.unsplash.com/photo-1557597774-9d273605dfa9?w=400&auto=format&fit=crop&q=80',
    'camera',
    '{"stream": ["RTSP", "WEBRTC"], "ptz": ["PAN", "TILT"], "night_vision": ["AUTO", "ON", "OFF"]}'::jsonb
)
ON CONFLICT (model_code) DO NOTHING;
