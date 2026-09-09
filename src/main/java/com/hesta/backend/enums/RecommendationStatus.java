package com.hesta.backend.enums;

/**
 * Trạng thái của một gợi ý kịch bản tự động do AI đề xuất (Bảng: automation_recommendations.status).
 * - PENDING: AI vừa đề xuất, đang chờ người dùng xem.
 * - ACCEPTED: Người dùng đã đồng ý áp dụng.
 * - REJECTED: Người dùng đã từ chối.
 * - EXPIRED: Đề xuất đã quá hạn mà không ai quan tâm.
 */
public enum RecommendationStatus {
    PENDING, ACCEPTED, REJECTED, EXPIRED
}
