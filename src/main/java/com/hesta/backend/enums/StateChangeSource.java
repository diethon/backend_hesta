package com.hesta.backend.enums;

/**
 * Lưu lại lý do khiến thiết bị thay đổi trạng thái (Bảng: device_state_history.source).
 * - MANUAL: Bật/tắt thủ công (App Mobile, Web Dashboard, hoặc công tắc vật lý). Kích hoạt Manual Override.
 * - VOICE, GESTURE: Giọng nói, cử chỉ AI.
 * - SCENE, AUTOMATION, SCHEDULE: Chạy theo kịch bản, tự động hóa, hẹn giờ.
 * - DEMAND_RESPONSE: Sa thải phụ tải / tối ưu hóa tiết kiệm điện giờ cao điểm.
 * - OTA: Khởi động lại hoặc đổi trạng thái do nạp Firmware từ xa.
 * - SHADOW_SYNC: Hạ tầng Digital Twin tự động đối soát và bù đắp trạng thái (Reconcile).
 * - TEST: Chạy kiểm thử tự động.
 */
public enum StateChangeSource {
    MANUAL,
    VOICE,
    GESTURE,
    SCENE,
    AUTOMATION,
    SCHEDULE,
    DEMAND_RESPONSE,
    OTA,
    SHADOW_SYNC,
    TEST
}
