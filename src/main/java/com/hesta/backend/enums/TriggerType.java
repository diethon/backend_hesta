package com.hesta.backend.enums;

/**
 * Loại điều kiện kích hoạt tự động hóa (Bảng: automation_rules.trigger_type).
 * - SENSOR: Kích hoạt dựa trên thay đổi của cảm biến.
 * - SCHEDULE: Kích hoạt dựa trên giờ hẹn.
 */
public enum TriggerType {
    SENSOR, SCHEDULE
}
