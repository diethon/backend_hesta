package com.hesta.backend.enums;

/**
 * Nguồn đăng nhập của user (Bảng: users.provider).
 * - LOCAL: Đăng nhập nội bộ qua Email và Mật khẩu.
 * - GOOGLE: Đăng nhập qua tài khoản Google OAuth.
 */
public enum AuthProvider {
    LOCAL, GOOGLE
}
