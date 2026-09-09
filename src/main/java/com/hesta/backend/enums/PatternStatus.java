package com.hesta.backend.enums;

/**
 * Trạng thái của một thói quen/hành vi người dùng do AI ghi nhận (Bảng: behavior_patterns.status).
 * - ACTIVE: Thói quen này vẫn đang đúng và được AI dùng để tiên đoán.
 * - INACTIVE: Thói quen đã cũ hoặc người dùng thay đổi, AI không dùng nữa.
 */
public enum PatternStatus {
    ACTIVE, INACTIVE
}
