package com.hesta.backend.enums;

/**
 * Mức độ ưu tiên của thông báo (Bảng: notifications.priority_level).
 * Dùng để UI biết cách hiển thị (vd: báo động đỏ vs thông báo ẩn).
 * - HIGH: Cần chú ý ngay lập tức (Xâm nhập).
 * - MEDIUM: Thông báo bình thường.
 * - LOW: Thông báo thông tin nhẹ.
 */
public enum NotificationPriority {
    HIGH, MEDIUM, LOW
}
