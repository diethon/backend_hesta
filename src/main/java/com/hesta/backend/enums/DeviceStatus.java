package com.hesta.backend.enums;

/**
 * Trạng thái hoạt động của thiết bị hoặc bộ điều khiển trung tâm (Bảng: edge_nodes.status, devices.status).
 * - ONLINE: Đang hoạt động và kết nối bình thường.
 * - OFFLINE: Mất kết nối.
 * - ERROR: Đang gặp lỗi hoặc hỏng hóc.
 * - UNKNOWN: Không xác định được trạng thái.
 */
public enum DeviceStatus {
    ONLINE, OFFLINE, ERROR, UNKNOWN
}
