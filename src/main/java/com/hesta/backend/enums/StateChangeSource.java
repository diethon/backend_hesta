package com.hesta.backend.enums;

/**
 * Lưu lại lý do khiến thiết bị thay đổi trạng thái (Bảng: device_state_history.source).
 * - MANUAL: Bật bằng tay trên app.
 * - VOICE, GESTURE: Giọng nói, cử chỉ.
 * - SCENE, AUTOMATION, SCHEDULE: Chạy theo kịch bản, hẹn giờ.
 * - TEST: Chạy test.
 */
public enum StateChangeSource {
    MANUAL, VOICE, GESTURE, SCENE, AUTOMATION, SCHEDULE, TEST
}
