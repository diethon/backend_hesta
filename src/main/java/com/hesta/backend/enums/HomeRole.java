package com.hesta.backend.enums;

/**
 * Phân quyền trong phạm vi 1 ngôi nhà (Bảng: home_members.role).
 * - OWNER: Chủ nhà, có full quyền quản lý nhà, thêm/xóa thành viên.
 * - MEMBER: Thành viên, quyền bị giới hạn (chỉ được bật tắt thiết bị theo phòng được phân).
 */
public enum HomeRole {
    OWNER, MEMBER
}
