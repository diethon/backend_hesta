-- Thêm cột is_deleted vào bảng devices để hỗ trợ Soft Delete
ALTER TABLE devices ADD COLUMN is_deleted BOOLEAN NOT NULL DEFAULT false;

-- Tạo index để truy vấn nhanh hơn vì hầu hết các query đều đi kèm điều kiện is_deleted = false
CREATE INDEX IF NOT EXISTS idx_devices_is_deleted ON devices(is_deleted);