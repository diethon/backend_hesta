package com.hesta.backend.enums;

/**
 * Trạng thái xử lý các bất thường của thiết bị (Bảng: device_anomalies.status).
 * - OPEN: Bất thường mới được hệ thống phát hiện, chưa ai xử lý.
 * - ACKNOWLEDGED: Người dùng đã bấm xác nhận biết lỗi này.
 * - RESOLVED: Lỗi đã được khắc phục xong.
 */
public enum AnomalyStatus {
    OPEN, ACKNOWLEDGED, RESOLVED
}
