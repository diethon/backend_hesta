package com.hesta.backend.enums;

/**
 * Vòng đời của một sự kiện an ninh (Bảng: security_events.status).
 * - DETECTED: Hệ thống vừa phát hiện sự kiện.
 * - CONFIRMED: Đã được xác nhận là có nguy hiểm thật.
 * - DISMISSED: Đã bị bỏ qua (báo động giả).
 */
public enum SecurityEventStatus {
    DETECTED, CONFIRMED, DISMISSED
}
