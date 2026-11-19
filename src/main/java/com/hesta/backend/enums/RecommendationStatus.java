package com.hesta.backend.enums;

/**
 * Trạng thái của một gợi ý kịch bản tự động do AI đề xuất (Bảng: automation_recommendations.status).
 * - PENDING: AI vừa đề xuất, đang chờ người dùng xem.
 * - ACCEPTED: Người dùng đã đồng ý áp dụng (hoặc APPROVED cho backward compatibility).
 * - REJECTED: Người dùng đã từ chối.
 * - DISMISSED: Người dùng tạm thời bỏ qua.
 * - EXPIRED: Đề xuất đã quá hạn mà không ai quan tâm.
 */
public enum RecommendationStatus {
    PENDING, ACCEPTED, APPROVED, REJECTED, DISMISSED, EXPIRED
}
