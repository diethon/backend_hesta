package com.hesta.backend.event;

import com.hesta.backend.enums.MaintenancePriority;
import com.hesta.backend.enums.MaintenanceTicketSource;
import com.hesta.backend.enums.MaintenanceTicketStatus;

import java.util.Set;
import java.util.UUID;

/**
 * Sự kiện gửi email thông báo ticket bảo trì (chỉ xuất bản trong transaction, gửi sau AFTER_COMMIT).
 * KHÔNG mang theo ảnh, mô tả chi tiết hay quan sát AI để bảo vệ quyền riêng tư.
 */
public record MaintenanceTicketEmailEvent(
        UUID ticketId,
        String ticketCode,
        UUID homeId,
        String title,
        MaintenancePriority priority,
        MaintenanceTicketSource source,
        MaintenanceTicketStatus status,
        EventKind kind,
        Set<String> recipientEmails
) {
    public enum EventKind {
        TICKET_CREATED_FOR_ADMIN,
        TICKET_SCHEDULED,
        TICKET_QUOTED,
        TICKET_COMPLETED,
        TICKET_REJECTED
    }
}
