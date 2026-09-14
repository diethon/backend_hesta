-- Thêm cột capabilities lưu mảng các thuộc tính (keys) mà thiết bị hỗ trợ
ALTER TABLE devices ADD COLUMN capabilities JSONB DEFAULT '[]'::jsonb;