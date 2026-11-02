package com.hesta.backend.enums;

/**
 * Trạng thái phiên giám hộ của Chế độ Giám hộ Con trẻ (Parental Guardian Mode).
 * - IDLE: Thiết bị đang tắt, hệ thống ở trạng thái chờ/sẵn sàng.
 * - COUNTDOWN: Thiết bị đang bật và đồng hồ thời gian cho phép đang đếm ngược.
 * - WARNING: Sắp hết thời gian cho phép (phát cảnh báo đèn/thông báo).
 * - LOCKED_OUT: Hết thời gian cho phép, thiết bị bị ngắt nguồn và khóa cưỡng chế.
 */
public enum GuardianStatus {
    IDLE,
    COUNTDOWN,
    WARNING,
    LOCKED_OUT
}
