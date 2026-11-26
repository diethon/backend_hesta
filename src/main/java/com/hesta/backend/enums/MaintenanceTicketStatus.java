package com.hesta.backend.enums;

import java.util.EnumSet;
import java.util.Set;

/**
 * Vòng đời ticket bảo trì (Bảng: maintenance_tickets.status).
 */
public enum MaintenanceTicketStatus {
    SUBMITTED,
    SCHEDULED,
    RESCHEDULE_REQUESTED,
    INSPECTING,
    QUOTED,
    REPAIRING,
    COMPLETED,
    CLOSED,
    CANCELLED,
    REJECTED;

    /** Ticket còn đang xử lý: một thiết bị chỉ được nằm trong một ticket active. */
    public static final Set<MaintenanceTicketStatus> ACTIVE = EnumSet.of(
            SUBMITTED, SCHEDULED, RESCHEDULE_REQUESTED, INSPECTING, QUOTED, REPAIRING);

    /** Ticket đang chiếm lịch kỹ thuật viên. */
    public static final Set<MaintenanceTicketStatus> OCCUPIES_SCHEDULE = EnumSet.of(SCHEDULED, INSPECTING);
}
