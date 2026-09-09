package com.hesta.backend.enums;

/**
 * Trạng thái kết quả sau khi thực thi hành động (Bảng: gesture_events, automation_executions, proactive_actions).
 * - SUCCESS: Chạy thành công toàn bộ.
 * - PARTIAL: Chỉ thành công một phần (vd: bật 3 đèn nhưng 1 đèn bị lỗi).
 * - FAILED: Thất bại hoàn toàn.
 * - SKIPPED: Bị bỏ qua do điều kiện không thỏa mãn.
 */
public enum ExecutionStatus {
    SUCCESS, PARTIAL, FAILED, SKIPPED
}
