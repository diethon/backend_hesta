package com.hesta.backend.enums;

/**
 * Trạng thái của tài khoản người dùng (Bảng: users.status).
 * - ACTIVE: Đang hoạt động bình thường.
 * - LOCKED: Bị khóa tạm thời (vd: sai mật khẩu nhiều lần).
 * - DISABLED: Bị vô hiệu hóa vĩnh viễn (khóa tài khoản).
 */
public enum AccountStatus {
    ACTIVE, LOCKED, DISABLED
}
