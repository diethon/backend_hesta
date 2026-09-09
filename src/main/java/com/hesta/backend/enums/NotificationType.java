package com.hesta.backend.enums;

/**
 * Phân loại loại thông báo gửi tới người dùng (Bảng: notifications.type).
 * Dành cho Bảo mật, Tự động hóa, Thiết bị lỗi, hay Hệ thống gửi.
 */
public enum NotificationType {
    SECURITY, AUTOMATION, ANOMALY, SYSTEM, DEVICE
}
