package com.hesta.backend.enums;

/**
 * Phân loại sự kiện an ninh (Bảng: security_events.event_type).
 * - MOTION: Phát hiện chuyển động đáng ngờ.
 * - INTRUSION: Phát hiện xâm nhập (như cạy cửa).
 */
public enum SecurityEventType {
    MOTION, INTRUSION
}
